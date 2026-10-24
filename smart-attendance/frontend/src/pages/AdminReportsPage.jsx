import React, { useState, useEffect } from "react";
import {
  ResponsiveContainer, LineChart, Line, BarChart, Bar, Cell,
  CartesianGrid, XAxis, YAxis, Tooltip, ReferenceLine,
} from "recharts";
import { adminApi, analyticsApi, subjectsApi, studentsApi } from "../services/api";
import {
  COLORS, buttonStyle, selectStyle, Banner, Table, EmptyRow, pageStyle,
  StatCard, BRANCHES, SEMESTERS, SECTIONS,
} from "../theme";

const REPORT_TYPES = [
  ["college", "College Report", "Full-college attendance summary across all departments."],
  ["department", "Department Report", "Attendance summary for a selected department, semester and section."],
  ["subject", "Attendance Report", "Per-student attendance for a selected subject and date range."],
  ["low", "Low Attendance Report", "Students with attendance below the configured threshold."],
];

// Backend default (MIN_ATTENDANCE_PCT). Every overview response overwrites
// this with the threshold actually configured on the server.
const DEFAULT_THRESHOLD = 75;

const pctText = (v) => (v === null || v === undefined ? "—" : `${v}%`);

export default function AdminReportsPage() {
  const [reportType, setReportType] = useState("college");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [branch, setBranch] = useState("ISE");
  const [semester, setSemester] = useState("");
  const [section, setSection] = useState("");
  const [lowBranch, setLowBranch] = useState(""); // low report defaults to all departments
  const [subjects, setSubjects] = useState([]);
  const [subjectId, setSubjectId] = useState("");
  const [report, setReport] = useState(null);
  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [message, setMessage] = useState(null);

  const switchType = (key) => {
    setReportType(key);
    setReport(null);
    setMessage(null);
    if (key === "department") { setSemester(""); setSection(""); }
    if (key === "subject" && !semester) { setSemester(SEMESTERS[0]); setSection(""); }
  };

  useEffect(() => {
    adminApi.overview()
      .then((ov) => { if (ov?.threshold_pct != null) setThreshold(ov.threshold_pct); })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (reportType === "subject") {
      subjectsApi.list(branch, semester).then(setSubjects).catch(() => {});
    }
  }, [reportType, branch, semester]);

  const dateFilters = () => ({
    start_date: startDate || undefined,
    end_date: endDate || undefined,
  });

  const validateDates = () => {
    if (startDate && endDate && startDate > endDate) {
      setMessage({ tone: "danger", text: "Start date must be on or before end date." });
      return false;
    }
    return true;
  };

  const snapshotFilters = () => ({
    start_date: startDate, end_date: endDate,
    branch: reportType === "low" ? lowBranch : branch,
    semester, section, subject_id: subjectId,
  });

  const generate = async () => {
    setMessage(null);
    if (!validateDates()) return;
    setLoading(true);
    try {
      const dates = dateFilters();
      if (reportType === "college") {
        const data = await adminApi.overview({ ...dates, trend_days: 90 });
        if (data.threshold_pct != null) setThreshold(data.threshold_pct);
        setReport({ type: "college", data, filters: snapshotFilters() });
      } else if (reportType === "department") {
        const data = await adminApi.departmentDetail(branch, dates);
        const sem = semester
          ? await adminApi.semesterDetail(branch, semester, dates)
          : null;
        const cls = semester && section
          ? await adminApi.classOverview(branch, semester, section, dates)
          : null;
        setReport({ type: "department", data, sem, cls, filters: snapshotFilters() });
      } else if (reportType === "subject") {
        if (!subjectId) {
          setMessage({ tone: "warning", text: "Select a subject first." });
          return;
        }
        const data = await analyticsApi.reports({ subject_id: Number(subjectId), ...dates });
        const roster = await studentsApi.list({ branch, semester }).catch(() => []);
        const byPk = {};
        (roster || []).forEach((s) => { byPk[s.id] = s; });
        const subj = subjects.find((s) => String(s.id) === String(subjectId));
        setReport({ type: "subject", data, byPk, subject: subj, filters: snapshotFilters() });
      } else if (reportType === "low") {
        const rows = lowBranch
          ? await adminApi.lowAttendance(lowBranch, dates)
          : (await adminApi.overview(dates)).low_attendance_students;
        setReport({ type: "low", data: rows, filters: snapshotFilters() });
      }
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    } finally {
      setLoading(false);
    }
  };

  const exportExcel = async () => {
    if (!report) return;
    setExporting(true);
    setMessage(null);
    try {
      const f = report.filters || {};
      await adminApi.exportReport({
        report_type: report.type,
        branch: f.branch, semester: f.semester, section: f.section,
        subject_id: f.subject_id, start_date: f.start_date, end_date: f.end_date,
      });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || "Export failed." });
    } finally {
      setExporting(false);
    }
  };

  const printReport = () => window.print();

  const reportTitle = REPORT_TYPES.find(([k]) => k === reportType)?.[1];
  const windowLabel = report
    ? `${report.filters?.start_date || "beginning"} → ${report.filters?.end_date || "today"}`
    : `${startDate || "beginning"} → ${endDate || "today"}`;

  // --- subject report rows: same formula as the backend (present / total) ---
  const subjectRows = report?.type === "subject"
    ? Object.entries(report.data?.records || {}).map(([pk, recs]) => {
        const total = recs.length;
        const present = recs.filter((r) => r.status === "present").length;
        const absent = recs.filter((r) => r.status === "absent").length;
        const pct = total ? Math.round((present / total) * 1000) / 10 : 0;
        const st = report.byPk?.[Number(pk)] || report.byPk?.[pk];
        return {
          pk, usn: st?.student_id || pk, name: st?.name || "",
          total, present, absent, other: total - present - absent, pct,
        };
      }).sort((a, b) => a.pct - b.pct || String(a.usn).localeCompare(String(b.usn)))
    : [];

  const subjectSummary = subjectRows.length
    ? {
        students: subjectRows.length,
        records: subjectRows.reduce((n, r) => n + r.total, 0),
        present: subjectRows.reduce((n, r) => n + r.present, 0),
        absent: subjectRows.reduce((n, r) => n + r.absent, 0),
        other: subjectRows.reduce((n, r) => n + r.other, 0),
        pct: (() => {
          const t = subjectRows.reduce((n, r) => n + r.total, 0);
          const p = subjectRows.reduce((n, r) => n + r.present, 0);
          return t ? Math.round((p / t) * 1000) / 10 : 0;
        })(),
      }
    : null;

  // --- chart datasets (null percentages = no records in window, never plotted as 0) ---
  const trend = report?.type === "college" ? (report.data?.monthly_trend || []) : [];
  const collegeDepts = report?.type === "college"
    ? (report.data?.branch_stats || [])
        .filter((b) => b.attendance_pct !== null && b.attendance_pct !== undefined)
        .map((b) => ({ name: b.branch, pct: b.attendance_pct, students: b.students }))
    : [];
  const semesterBars = report?.type === "department"
    ? (report.data?.semesters || [])
        .filter((s) => s.attendance_pct !== null && s.attendance_pct !== undefined)
        .map((s) => ({ name: s.semester, pct: s.attendance_pct, students: s.students }))
    : [];
  const sectionBars = report?.type === "department" && report.sem
    ? (report.sem.sections || [])
        .filter((s) => s.attendance_pct !== null && s.attendance_pct !== undefined)
        .map((s) => ({ name: s.section, pct: s.attendance_pct, students: s.students }))
    : [];
  const lowBars = report?.type === "low"
    ? (report.data || []).slice(0, 10).map((s) => ({ name: s.student_id, pct: s.percentage }))
    : [];

  const barChart = (data, xKey, height = 240) => (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
        <XAxis dataKey={xKey} tick={{ fontSize: 11 }} />
        <YAxis unit="%" domain={[0, 100]} tick={{ fontSize: 11 }} />
        <Tooltip formatter={(v) => [`${v}%`, "Attendance"]} />
        <ReferenceLine y={threshold} stroke={COLORS.danger} strokeDasharray="4 4" />
        <Bar dataKey="pct" radius={[4, 4, 0, 0]}>
          {data.map((entry, i) => (
            <Cell key={i} fill={entry.pct < threshold ? COLORS.danger : COLORS.success} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );

  const controls = (
    <div className="no-print" style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1rem", alignItems: "end" }}>
      <div>
        <label style={{ fontSize: "0.75rem", color: "#666" }}>From</label>
        <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} style={selectStyle} />
      </div>
      <div>
        <label style={{ fontSize: "0.75rem", color: "#666" }}>To</label>
        <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} style={selectStyle} />
      </div>

      {reportType === "department" && (
        <>
          <div>
            <label style={{ fontSize: "0.75rem", color: "#666" }}>Department</label>
            <select value={branch} onChange={(e) => { setBranch(e.target.value); setSection(""); }} style={selectStyle}>
              {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
          </div>
          <div>
            <label style={{ fontSize: "0.75rem", color: "#666" }}>Semester</label>
            <select value={semester} onChange={(e) => { setSemester(e.target.value); setSection(""); }} style={selectStyle}>
              <option value="">All semesters</option>
              {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          {semester && (
            <div>
              <label style={{ fontSize: "0.75rem", color: "#666" }}>Section</label>
              <select value={section} onChange={(e) => setSection(e.target.value)} style={selectStyle}>
                <option value="">All sections</option>
                {SECTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
          )}
        </>
      )}

      {reportType === "subject" && (
        <>
          <div>
            <label style={{ fontSize: "0.75rem", color: "#666" }}>Branch</label>
            <select value={branch} onChange={(e) => setBranch(e.target.value)} style={selectStyle}>
              {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
          </div>
          <div>
            <label style={{ fontSize: "0.75rem", color: "#666" }}>Semester</label>
            <select value={semester} onChange={(e) => setSemester(e.target.value)} style={selectStyle}>
              {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div>
            <label style={{ fontSize: "0.75rem", color: "#666" }}>Subject</label>
            <select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} style={selectStyle}>
              <option value="">Select subject</option>
              {subjects.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}
            </select>
          </div>
        </>
      )}

      {reportType === "low" && (
        <div>
          <label style={{ fontSize: "0.75rem", color: "#666" }}>Department</label>
          <select value={lowBranch} onChange={(e) => setLowBranch(e.target.value)} style={selectStyle}>
            <option value="">All departments</option>
            {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
          </select>
        </div>
      )}

      <button onClick={generate} disabled={loading} style={buttonStyle(COLORS.primary)}>
        {loading ? "Generating..." : "Generate Report"}
      </button>
      {report && (
        <button onClick={exportExcel} disabled={exporting} style={buttonStyle(COLORS.success)}>
          {exporting ? "Exporting..." : "Export Excel"}
        </button>
      )}
      {report && <button onClick={printReport} style={buttonStyle("#95a5a6")}>Print / PDF</button>}
    </div>
  );

  const cell = { padding: "0.6rem", fontSize: "0.85rem" };

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Reports</h2>
      <p style={{ color: "#666" }}>Generate college-wide, department, subject, and low-attendance reports.</p>

      <div className="no-print" style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1rem" }}>
        {REPORT_TYPES.map(([key, label, desc]) => (
          <button key={key} onClick={() => switchType(key)} style={{ ...selectStyle, background: reportType === key ? COLORS.primary : "#fff", color: reportType === key ? "#fff" : "#333", cursor: "pointer" }} title={desc}>
            {label}
          </button>
        ))}
      </div>

      {controls}
      {message && <Banner tone={message.tone}>{message.text}</Banner>}

      {report && (
        <p style={{ color: "#777", fontSize: "0.78rem", marginTop: 0 }}>
          {reportTitle} · window {windowLabel} · eligibility threshold {threshold}% ·
          generated {new Date().toLocaleString()} · days with no attendance are never counted as absences.
        </p>
      )}

      {report?.type === "college" && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>College Report — {windowLabel}</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(140px, 1fr))", gap: "1rem", marginBottom: "1.5rem" }}>
            <StatCard label="Students" value={report.data.total_students} color={COLORS.info} />
            <StatCard label="Teachers" value={report.data.total_teachers} color={COLORS.success} />
            <StatCard label="Departments" value={report.data.total_departments} color={COLORS.purple || COLORS.primary} />
            <StatCard label="Attendance in window" value={pctText(report.data.overall_attendance_pct)} color={COLORS.primary} />
            <StatCard label="Recorded classes" value={report.data.records_in_window} color={COLORS.info} />
            <StatCard label={`Below ${threshold}%`} value={report.data.low_attendance_count} color={COLORS.warning} />
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))", gap: "1.5rem", marginBottom: "1.5rem" }}>
            <div>
              <h4 style={{ marginBottom: "0.25rem" }}>Attendance trend (days with records)</h4>
              {trend.length === 0 ? (
                <Banner tone="info">No attendance recorded in this window.</Banner>
              ) : (
                <ResponsiveContainer width="100%" height={240}>
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
              <h4 style={{ marginBottom: "0.25rem" }}>Department comparison</h4>
              {collegeDepts.length === 0 ? (
                <Banner tone="info">No department has records for this window.</Banner>
              ) : barChart(collegeDepts, "name")}
            </div>
          </div>

          <h4>Departments</h4>
          <Table head={["Department", "Students", "Teachers", "Subjects", "Attendance %", "Records", `Below ${threshold}%`]}>
            {(report.data.branch_stats || []).map((b) => (
              <tr key={b.branch} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={cell}>{b.branch}</td>
                <td style={cell}>{b.students}</td>
                <td style={cell}>{b.teachers}</td>
                <td style={cell}>{b.subjects}</td>
                <td style={{ ...cell, color: b.attendance_pct == null ? "#999" : (b.attendance_pct < threshold ? COLORS.danger : COLORS.success), fontWeight: 600 }}>
                  {pctText(b.attendance_pct)}
                </td>
                <td style={cell}>{b.records}</td>
                <td style={cell}>{b.below_threshold_students}</td>
              </tr>
            ))}
            {(report.data.branch_stats || []).length === 0 && <EmptyRow colSpan={7} />}
          </Table>
        </div>
      )}

      {report?.type === "department" && report.data?.semesters && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>Department Report — {report.filters.branch} · {windowLabel}</h3>

          {semesterBars.length > 0 && (
            <div style={{ marginBottom: "1.25rem" }}>
              <h4 style={{ marginBottom: "0.25rem" }}>Semester comparison</h4>
              {barChart(semesterBars, "name")}
            </div>
          )}

          <h4>Semesters</h4>
          <Table head={["Semester", "Students", "Attendance %"]}>
            {report.data.semesters.map((s) => (
              <tr key={s.semester} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={cell}>{s.semester}</td>
                <td style={cell}>{s.students}</td>
                <td style={{ ...cell, color: s.attendance_pct == null ? "#999" : (s.attendance_pct < threshold ? COLORS.danger : COLORS.success), fontWeight: 600 }}>
                  {pctText(s.attendance_pct)}
                </td>
              </tr>
            ))}
            {report.data.semesters.length === 0 && <EmptyRow colSpan={3} />}
          </Table>

          {report.sem && (
            <>
              <h4>{report.filters.semester} — sections</h4>
              {sectionBars.length > 0 && <div style={{ marginBottom: "1rem" }}>{barChart(sectionBars, "name", 200)}</div>}
              <Table head={["Section", "Students", "Attendance %"]}>
                {report.sem.sections.map((s) => (
                  <tr key={s.section} style={{ borderBottom: "1px solid #dee2e6" }}>
                    <td style={cell}>{s.section}</td>
                    <td style={cell}>{s.students}</td>
                    <td style={{ ...cell, color: s.attendance_pct == null ? "#999" : (s.attendance_pct < threshold ? COLORS.danger : COLORS.success), fontWeight: 600 }}>
                      {pctText(s.attendance_pct)}
                    </td>
                  </tr>
                ))}
                {report.sem.sections.length === 0 && <EmptyRow colSpan={3} />}
              </Table>
            </>
          )}

          {report.cls && (
            <>
              <h4>{report.filters.section} — students</h4>
              <p style={{ color: "#777", fontSize: "0.78rem" }}>
                Attendance in window; students with no records in the window show 0.0%.
              </p>
              <Table head={["USN", "Name", "Roll No", "Attendance %", "Today"]}>
                {report.cls.students.map((s) => (
                  <tr key={s.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                    <td style={cell}>{s.student_id}</td>
                    <td style={cell}><strong>{s.name}</strong></td>
                    <td style={cell}>{s.roll_number}</td>
                    <td style={{ ...cell, color: s.attendance_pct < threshold ? COLORS.danger : COLORS.success, fontWeight: 600 }}>
                      {s.attendance_pct}%
                    </td>
                    <td style={cell}>{s.today_status}</td>
                  </tr>
                ))}
                {report.cls.students.length === 0 && <EmptyRow colSpan={5} />}
              </Table>
            </>
          )}
        </div>
      )}

      {report?.type === "subject" && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>
            Attendance Report — {report.subject?.name} ({report.subject?.code}) · {windowLabel}
          </h3>
          {subjectSummary && (
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: "0.75rem", marginBottom: "1rem" }}>
              <StatCard label="Students" value={subjectSummary.students} color={COLORS.info} />
              <StatCard label="Recorded classes" value={subjectSummary.records} color={COLORS.primary} />
              <StatCard label="Present" value={subjectSummary.present} color={COLORS.success} />
              <StatCard label="Absent" value={subjectSummary.absent} color={COLORS.danger} />
              <StatCard label="Other marks" value={subjectSummary.other} color={COLORS.warning} />
              <StatCard label="Attendance" value={`${subjectSummary.pct}%`} color={COLORS.primary} />
            </div>
          )}
          <Table head={["USN", "Name", "Total Classes", "Present", "Absent", "Other", "Attendance %"]}>
            {subjectRows.map((r) => (
              <tr key={r.pk} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={cell}>{r.usn}</td>
                <td style={cell}>{r.name}</td>
                <td style={cell}>{r.total}</td>
                <td style={cell}>{r.present}</td>
                <td style={cell}>{r.absent}</td>
                <td style={cell}>{r.other}</td>
                <td style={{ ...cell, color: r.pct < threshold ? COLORS.danger : COLORS.success, fontWeight: 600 }}>
                  {r.pct}%
                </td>
              </tr>
            ))}
            {subjectRows.length === 0 && <EmptyRow colSpan={7}>No attendance recorded for this subject in the selected window.</EmptyRow>}
          </Table>
        </div>
      )}

      {report?.type === "low" && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>Low Attendance Report — {windowLabel}</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(140px, 1fr))", gap: "1rem", marginBottom: "1rem" }}>
            <StatCard label="Students below threshold" value={(report.data || []).length} color={COLORS.warning} />
            <StatCard label="Lowest attendance" value={report.data?.length ? `${report.data[0].percentage}%` : "—"} color={COLORS.danger} />
            <StatCard label="Threshold" value={`${threshold}%`} color={COLORS.primary} />
            <StatCard label="Scope" value={report.filters.branch || "All depts"} color={COLORS.info} />
          </div>
          <Banner tone="warning">
            Students listed below have attendance under {threshold}% in the selected window and may be at risk.
            Unmarked days are never counted as absences.
          </Banner>
          {lowBars.length > 0 && (
            <div style={{ margin: "1rem 0" }}>
              <h4 style={{ marginBottom: "0.25rem" }}>Lowest 10</h4>
              {barChart(lowBars, "name", 220)}
            </div>
          )}
          <Table head={["USN", "Name", "Branch", "Attendance %"]}>
            {(report.data || []).map((s) => (
              <tr key={s.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={cell}>{s.student_id}</td>
                <td style={cell}><strong>{s.name}</strong></td>
                <td style={cell}>{s.branch}</td>
                <td style={{ ...cell, color: COLORS.danger, fontWeight: 600 }}>{s.percentage}%</td>
              </tr>
            ))}
            {(report.data || []).length === 0 && <EmptyRow colSpan={4}>No student is below {threshold}% in this window.</EmptyRow>}
          </Table>
        </div>
      )}
    </div>
  );
}
