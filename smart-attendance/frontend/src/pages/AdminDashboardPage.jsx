import React, { useState, useEffect, useCallback } from "react";
import { adminApi } from "../services/api";
import {
  Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ReferenceLine,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import {
  COLORS, smallButton, StatCard, Banner, Table, EmptyRow,
  pageStyle, selectStyle, buttonStyle,
} from "../theme";

const pctText = (v) => (v === null || v === undefined ? "—" : `${v}%`);

export default function AdminDashboardPage() {
  const [overview, setOverview] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [lowBranch, setLowBranch] = useState(null);
  const [lowStudents, setLowStudents] = useState([]);
  const [lowLoading, setLowLoading] = useState(false);
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [trendDays, setTrendDays] = useState(14);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    adminApi
      .overview({ start_date: startDate || null, end_date: endDate || null, trend_days: trendDays })
      .then(setOverview)
      .catch((e) => setError(e?.response?.data?.detail || e.message))
      .finally(() => setLoading(false));
  }, [startDate, endDate, trendDays]);

  useEffect(() => { load(); }, [load]);

  const showLowAttendance = async (branch) => {
    setLowBranch(branch);
    setLowLoading(true);
    try {
      setLowStudents(await adminApi.lowAttendance(branch));
    } catch (_) {
      setLowStudents([]);
    } finally {
      setLowLoading(false);
    }
  };

  if (loading && !overview) return <div style={{ padding: "2rem" }}>Loading college overview...</div>;
  if (error) return <div style={{ padding: "2rem", color: "#c53030" }}>{error}</div>;
  if (!overview) return <div style={{ padding: "2rem" }}>No data available yet.</div>;

  const threshold = overview.threshold_pct ?? 75;
  const windowLabel =
    overview.window?.start_date || overview.window?.end_date
      ? `${overview.window.start_date || "beginning"} to ${overview.window.end_date || "today"}`
      : "All recorded dates";
  const deptChart = (overview.branch_stats || [])
    .filter((b) => b.attendance_pct !== null)
    .map((b) => ({ name: b.branch, pct: b.attendance_pct, students: b.students }));
  const trend = (overview.monthly_trend || []).map((t) => ({
    date: t.date.slice(5), pct: t.pct,
  }));

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>College Overview</h2>
      <p style={{ color: "#666" }}>Welcome, Principal. Here is a snapshot of the entire college.</p>

      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1rem", alignItems: "flex-end" }}>
        <div>
          <label style={labelStyle}>From</label>
          <input type="date" value={startDate} max={endDate || undefined}
            onChange={(e) => setStartDate(e.target.value)} style={selectStyle} />
        </div>
        <div>
          <label style={labelStyle}>To</label>
          <input type="date" value={endDate} min={startDate || undefined}
            onChange={(e) => setEndDate(e.target.value)} style={selectStyle} />
        </div>
        <div>
          <label style={labelStyle}>Trend window</label>
          <select value={trendDays} onChange={(e) => setTrendDays(Number(e.target.value))} style={selectStyle}>
            <option value={7}>Last 7 days</option>
            <option value={14}>Last 14 days</option>
            <option value={30}>Last 30 days</option>
          </select>
        </div>
        <button onClick={load} style={{ ...buttonStyle(COLORS.primary), height: "36px" }}>Apply</button>
        {(startDate || endDate) && (
          <button onClick={() => { setStartDate(""); setEndDate(""); }}
            style={{ ...buttonStyle("#95a5a6"), height: "36px" }}>Clear</button>
        )}
        {loading && <span style={{ fontSize: "0.8rem", color: "#666" }}>Refreshing...</span>}
      </div>
      <p style={{ color: "#666", fontSize: "0.78rem", marginTop: "-0.5rem", marginBottom: "1rem" }}>
        Attendance figures cover {windowLabel} · {overview.records_in_window ?? 0} recorded classes ·
        eligibility threshold {threshold}%. Days with no attendance are never counted as absences.
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "1rem", marginBottom: "1.5rem" }}>
        <StatCard label="Total Students" value={overview.total_students} color={COLORS.info} />
        <StatCard label="Total Teachers" value={overview.total_teachers} color={COLORS.success} />
        <StatCard label="Total Departments" value={overview.total_departments} color={COLORS.purple} />
        <StatCard label="Overall Attendance" value={pctText(overview.overall_attendance_pct)} color={COLORS.primary} />
        <StatCard label="Today's Present" value={overview.present_today} color={COLORS.success} />
        <StatCard label="Today's Absent" value={overview.absent_today} color={COLORS.danger} />
        <StatCard label={`Students below ${threshold}%`} value={overview.low_attendance_count} color={COLORS.warning} />
      </div>

      {(overview.attention || []).length > 0 && (
        <Banner tone="warning">
          Departments below the {threshold}% threshold:{" "}
          {overview.attention.map((a) => `${a.branch} (${a.attendance_pct}%)`).join(", ")}
        </Banner>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))", gap: "1.5rem", marginBottom: "1.5rem" }}>
        <div>
          <h3 style={{ marginBottom: "0.25rem" }}>Attendance trend ({trendDays} days)</h3>
          {trend.length === 0 ? (
            <Banner tone="info">No attendance has been recorded in this window yet.</Banner>
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <LineChart data={trend} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                <YAxis unit="%" domain={[0, 100]} tick={{ fontSize: 11 }} />
                <Tooltip formatter={(v) => [`${v}%`, "Attendance"]} />
                <ReferenceLine y={threshold} stroke={COLORS.danger} strokeDasharray="4 4"
                  label={{ value: `${threshold}%`, position: "insideTopRight", fontSize: 11, fill: COLORS.danger }} />
                <Line type="monotone" dataKey="pct" stroke={COLORS.primary} strokeWidth={2} dot={{ r: 3 }} />
              </LineChart>
            </ResponsiveContainer>
          )}
        </div>

        <div>
          <h3 style={{ marginBottom: "0.25rem" }}>Department comparison</h3>
          {deptChart.length === 0 ? (
            <Banner tone="info">No department has attendance records for these filters.</Banner>
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={deptChart} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis dataKey="name" tick={{ fontSize: 11 }} />
                <YAxis unit="%" domain={[0, 100]} tick={{ fontSize: 11 }} />
                <Tooltip formatter={(v, _n, item) => [`${v}%`, `${item.payload.students} students`]} />
                <ReferenceLine y={threshold} stroke={COLORS.danger} strokeDasharray="4 4" />
                <Bar dataKey="pct" radius={[4, 4, 0, 0]}>
                  {deptChart.map((entry) => (
                    <Cell key={entry.name} fill={entry.pct < threshold ? COLORS.danger : COLORS.success} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      <div style={{ marginBottom: "1.5rem" }}>
        <h3>Departments</h3>
        <div style={{ overflowX: "auto" }}>
          <Table head={["Department", "Students", "Teachers", "Subjects", "Attendance %", `Below ${threshold}%`]} >
            {(overview.branch_stats || []).map((b) => (
              <tr key={b.branch} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                  <button style={{ ...smallButton(COLORS.primary) }} onClick={() => showLowAttendance(b.branch)}>{b.branch}</button>
                </td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{b.students}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{b.teachers}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{b.subjects}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                  <strong style={{ color: b.attendance_pct === null ? "#6c757d" : b.attendance_pct < threshold ? COLORS.danger : COLORS.success }}>
                    {pctText(b.attendance_pct)}
                  </strong>
                </td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{b.below_threshold_students}</td>
              </tr>
            ))}
            {(overview.branch_stats || []).length === 0 && <EmptyRow colSpan={6} />}
          </Table>
        </div>
      </div>

      <h3>Students below {threshold}% attendance</h3>
      <Banner tone="warning">Click a department name above to see students below threshold.</Banner>

      {lowBranch && (
        <div style={{ marginTop: "1rem" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <h4 style={{ margin: 0 }}>{lowBranch} — Low Attendance Students</h4>
            <button onClick={() => { setLowBranch(null); setLowStudents([]); }} style={smallButton("#95a5a6")}>Close</button>
          </div>
          {lowLoading ? (
            <p>Loading...</p>
          ) : (
            <Table head={["Student ID", "Name", "Semester", "Section", "Attendance %"]}>
              {lowStudents.map((s) => (
                <tr key={s.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.student_id}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong>{s.name}</strong></td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.semester}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.section}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong style={{ color: COLORS.danger }}>{s.percentage}%</strong></td>
                </tr>
              ))}
              {lowStudents.length === 0 && <EmptyRow colSpan={5}>No students below threshold in this department.</EmptyRow>}
            </Table>
          )}
        </div>
      )}
    </div>
  );
}

const labelStyle = { display: "block", fontSize: "0.72rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };