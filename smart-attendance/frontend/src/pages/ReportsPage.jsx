import React, { useState, useEffect, useCallback } from "react";
import { useAuth } from "../services/authContext";
import { subjectsApi, attendanceApi, studentsApi, teacherApi, hodReportApi } from "../services/api";
import ParentReportNotify from "../components/ParentReportNotify";
import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import { COLORS, SEMESTERS, BRANCHES, pageStyle, selectStyle, buttonStyle as btnStyle, Table, EmptyRow, Banner, StatCard } from "../theme";

const STATUS_LABEL = {
  ok: "Healthy",
  below_threshold: "Below threshold",
  no_data: "No records",
};

const chartCell = (status) =>
  status === "below_threshold" ? COLORS.danger : status === "no_data" ? "#b6bec9" : COLORS.success;

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function HodComparisonReport() {
  const { user } = useAuth();
  const [semester, setSemester] = useState("");           // "" = all semesters
  const [section, setSection] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [subjects, setSubjects] = useState([]);
  const [sections, setSections] = useState([]);
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    subjectsApi
      .list(user?.branch || "", semester)
      .then(setSubjects)
      .catch(() => setSubjects([]));
  }, [user?.branch, semester]);

  useEffect(() => {
    studentsApi
      .list({ branch: user?.branch || "", semester: semester || undefined })
      .then((list) => {
        const found = [];
        (list || []).forEach((s) => {
          if (s.section && !found.includes(s.section)) found.push(s.section);
        });
        setSections(found.sort());
      })
      .catch(() => setSections([]));
  }, [user?.branch, semester]);

  const filters = useCallback(
    () => ({
      semester: semester || null,
      subject_id: subjectId || null,
      section: section || null,
      start_date: startDate || null,
      end_date: endDate || null,
    }),
    [semester, subjectId, section, startDate, endDate]
  );

  const generate = async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await hodReportApi.comparison(filters()));
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Could not generate the report");
    } finally {
      setLoading(false);
    }
  };

  const exportFile = async (kind) => {
    setExporting(true);
    setError(null);
    try {
      const f = filters();
      const scope = (f.semester || "all-semesters").replace(/\s+/g, "-");
      if (kind === "pdf") {
        downloadBlob(await hodReportApi.downloadPdf(f), `hod-comparison-${scope}.pdf`);
      } else {
        downloadBlob(await hodReportApi.downloadXlsx(f), `hod-comparison-${scope}.xlsx`);
      }
    } catch (err) {
      setError(err?.response?.data?.detail || "Export failed. Please try again.");
    } finally {
      setExporting(false);
    }
  };

  const summary = report?.summary;
  const subjectChart = (report?.subject_rows || [])
    .filter((r) => r.pct !== null)
    .sort((a, b) => a.pct - b.pct)
    .slice(0, 20)
    .map((r) => ({ name: r.code, pct: r.pct, status: r.status }));
  const semesterChart = (report?.semester_rows || [])
    .filter((r) => r.pct !== null)
    .map((r) => ({ name: r.semester, pct: r.pct }));

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Subject &amp; Semester Comparison</h2>
      <p style={{ color: "#666" }}>
        Attendance comparison for <strong>{user?.branch}</strong>. Every number comes from
        recorded attendance; classes that were never marked are not counted as absent.
      </p>

      <div style={filterBar}>
        <div>
          <label style={labelStyle}>Semester</label>
          <select value={semester} onChange={(e) => setSemester(e.target.value)} style={selectStyle}>
            <option value="">All semesters</option>
            {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Subject</label>
          <select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} style={{ ...selectStyle, minWidth: "200px" }}>
            <option value="">All subjects</option>
            {subjects.map((s) => <option key={s.id} value={s.id}>{s.code} - {s.name}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Section</label>
          <select value={section} onChange={(e) => setSection(e.target.value)} style={selectStyle}>
            <option value="">All sections</option>
            {sections.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
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
        <button onClick={generate} disabled={loading} style={{ ...btnStyle(COLORS.primary), height: "36px" }}>
          {loading ? "Generating..." : "Generate Report"}
        </button>
        {report && (
          <>
            <button onClick={() => exportFile("pdf")} disabled={exporting}
              style={{ ...btnStyle(COLORS.danger), height: "36px" }}>
              {exporting ? "Exporting..." : "Export PDF"}
            </button>
            <button onClick={() => exportFile("xlsx")} disabled={exporting}
              style={{ ...btnStyle(COLORS.success), height: "36px" }}>
              Export Excel
            </button>
          </>
        )}
      </div>

      {error && <Banner tone="danger">{error}</Banner>}

      {!report && !loading && !error && (
        <Banner tone="info">Choose your filters and select Generate Report.</Banner>
      )}

      {report && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>
            {report.branch}
            {semester ? ` / ${semester}` : " / all semesters"}
            {section ? ` / ${section}` : ""}
          </h3>
          <p style={{ color: "#666", fontSize: "0.85rem", marginTop: 0 }}>
            Generated on {report.generated_at}
            {(startDate || endDate)
              ? ` · ${startDate || "beginning"} to ${endDate || "today"}`
              : " · all dates"}
          </p>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.75rem", marginBottom: "1.25rem" }}>
            <StatCard label="Subjects in scope" value={summary.total_subjects} color={COLORS.primary} />
            <StatCard label="Semesters represented" value={summary.semesters_represented} color={COLORS.accent} />
            <StatCard
              label="Department attendance"
              value={summary.attendance_pct === null ? "—" : `${summary.attendance_pct}%`}
              color={summary.attendance_pct !== null && summary.attendance_pct < summary.threshold_pct ? COLORS.danger : COLORS.success}
            />
            <StatCard label={`Subjects below ${summary.threshold_pct}%`} value={summary.subjects_below_threshold} color={COLORS.danger} />
          </div>

          <div style={{ marginBottom: "1.5rem" }}>
            <h4 style={{ marginBottom: "0.25rem" }}>Insights</h4>
            <ul style={{ margin: 0, paddingLeft: "1.1rem", color: "#444", fontSize: "0.85rem" }}>
              {report.insights.map((note) => <li key={note}>{note}</li>)}
            </ul>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))", gap: "1.5rem" }}>
            <div>
              <h4 style={{ marginTop: 0 }}>Subject-wise attendance</h4>
              {subjectChart.length === 0 ? (
                <Banner tone="info">No attendance recorded for these filters yet.</Banner>
              ) : (
                <ResponsiveContainer width="100%" height={280}>
                  <BarChart data={subjectChart} margin={{ top: 8, right: 12, bottom: 8, left: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                    <XAxis dataKey="name" tick={{ fontSize: 11 }} interval={0} angle={-25} textAnchor="end" height={60} />
                    <YAxis unit="%" domain={[0, 100]} tick={{ fontSize: 11 }} />
                    <Tooltip formatter={(v) => [`${v}%`, "Attendance"]} />
                    <Bar dataKey="pct" radius={[4, 4, 0, 0]}>
                      {subjectChart.map((entry) => (
                        <Cell key={entry.name} fill={chartCell(entry.status)} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>

            <div>
              <h4 style={{ marginTop: 0 }}>Semester-wise attendance</h4>
              {semesterChart.length < 2 ? (
                <Banner tone="info">
                  {semesterChart.length === 0
                    ? "No attendance recorded for these filters yet."
                    : "Select all semesters to compare more than one."}
                </Banner>
              ) : (
                <ResponsiveContainer width="100%" height={280}>
                  <BarChart data={semesterChart} margin={{ top: 8, right: 12, bottom: 8, left: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                    <XAxis dataKey="name" tick={{ fontSize: 11 }} interval={0} />
                    <YAxis unit="%" domain={[0, 100]} tick={{ fontSize: 11 }} />
                    <Tooltip formatter={(v) => [`${v}%`, "Attendance"]} />
                    <Bar dataKey="pct" fill={COLORS.primary} radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>

            <div>
              <h4 style={{ marginTop: 0 }}>Attendance distribution (subjects)</h4>
              <ResponsiveContainer width="100%" height={240}>
                <BarChart data={report.distribution} layout="vertical" margin={{ top: 8, right: 24, bottom: 8, left: 24 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                  <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11 }} />
                  <YAxis type="category" dataKey="bucket" width={110} tick={{ fontSize: 11 }} />
                  <Tooltip formatter={(v) => [`${v} subject(s)`, "Subjects"]} />
                  <Bar dataKey="count" fill={COLORS.info} radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

          <h4 style={{ marginTop: "1.5rem" }}>Detailed report</h4>
          <div style={{ overflowX: "auto" }}>
            <Table head={["Subject", "Semester", "Section", "Records", "Present", "Attendance %", "Status", "Action"]}>
              {report.subject_rows.map((r) => (
                <tr key={r.subject_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={tdStyle}>
                    <strong>{r.code}</strong> {r.name}
                  </td>
                  <td style={tdStyle}>{r.semester || "—"}</td>
                  <td style={tdStyle}>{r.sections.length ? r.sections.join(", ") : "—"}</td>
                  <td style={tdStyle}>{r.records}</td>
                  <td style={tdStyle}>{r.present}</td>
                  <td style={tdStyle}>
                    <strong style={{ color: r.pct === null ? "#6c757d" : chartCell(r.status) }}>
                      {r.pct === null ? "—" : `${r.pct}%`}
                    </strong>
                  </td>
                  <td style={tdStyle}>{STATUS_LABEL[r.status]}</td>
                  <td style={tdStyle}>
                    {r.status === "below_threshold"
                      ? <a href="/hod/attendance" style={{ color: COLORS.danger }}>Review class</a>
                      : "—"}
                  </td>
                </tr>
              ))}
              {report.subject_rows.length === 0 && (
                <EmptyRow colSpan={8}>No subjects match the selected filters.</EmptyRow>
              )}
            </Table>
          </div>
        </div>
      )}
    </div>
  );
}

export default function ReportsPage() {
  const { user } = useAuth();
  const isTeacher = user?.role === "teacher";
  if (user?.role === "hod") return <HodComparisonReport />;
  const myBranch = user?.branch || "";
  const [branch, setBranch] = useState(myBranch || "ISE");
  const [semester, setSemester] = useState("5th Semester");
  const [subjects, setSubjects] = useState([]);
  const [teacherSubjects, setTeacherSubjects] = useState([]);
  const [subjectId, setSubjectId] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [notifyMenu, setNotifyMenu] = useState(false);
  const [notifyMode, setNotifyMode] = useState(null);

  useEffect(() => {
    setSubjectId("");
    setReport(null);
    if (isTeacher) {
      teacherApi
        .dashboard()
        .then((d) => {
          setTeacherSubjects(
            (d.subjects || []).map((s) => ({
              id: s.subject_id,
              code: s.code,
              name: s.name,
              branch: s.branch,
              semester: s.semester,
            }))
          );
        })
        .catch((err) => setError(err?.response?.data?.detail || "Unable to load your assigned subjects"));
      return;
    }
    subjectsApi.list(branch, semester).then(setSubjects).catch(() => {});
  }, [branch, semester, isTeacher]);

  const byUsn = (a, b) => String(a.student_id || "").localeCompare(String(b.student_id || ""));

  const generate = async () => {
    setLoading(true);
    setError(null);
    setReport(null);
    try {
      if (isTeacher) {
        if (startDate && endDate && startDate > endDate) {
          setError("The start date must be on or before the end date.");
          return;
        }
        const data = await teacherApi.reports({
          subject_id: subjectId ? Number(subjectId) : null,
          start_date: startDate || null,
          end_date: endDate || null,
        });
        const subject = teacherSubjects.find((s) => String(s.id) === String(subjectId)) || null;
        setReport({
          type: "attendance",
          teacher: true,
          branch: data.branch || myBranch,
          semester: subject ? subject.semester : null,
          subject,
          rows: [...(data.rows || [])].sort(byUsn),
          summary: data.summary,
          startDate,
          endDate,
        });
        return;
      }

      const data = await attendanceApi.list(subjectId ? Number(subjectId) : null, null);
      const allStudents = await studentsApi.list({ branch, semester });
      const studentMap = {};
      allStudents.forEach((s) => { studentMap[s.id] = s; });

      const byStudent = {};
      data.forEach((r) => {
        const sid = r.student_id;
        if (!byStudent[sid]) byStudent[sid] = { present: 0, total: 0 };
        byStudent[sid].total++;
        if (r.status === "present") byStudent[sid].present++;
      });

      const rows = Object.entries(byStudent).map(([sid, d]) => {
        const s = studentMap[sid] || {};
        return {
          student_id: s.student_id || `#${sid}`,
          name: s.name || `Student #${sid}`,
          semester: s.semester || "—",
          section: s.section || "—",
          present: d.present,
          total: d.total,
          pct: d.total > 0 ? Math.round((d.present / d.total) * 100) : 0,
        };
      }).sort((a, b) => a.pct - b.pct);

      setReport({ type: "attendance", branch, semester, subject: subjects.find((s) => String(s.id) === String(subjectId)), rows });
    } catch (err) {
      setError(err?.response?.data?.detail || err.message);
    } finally {
      setLoading(false);
    }
  };

  const printReport = () => window.print();

  const filterSubjects = isTeacher ? teacherSubjects : subjects;
  const head = report?.teacher
    ? ["USN", "Name", "Sem", "Section", "Present", "Absent", "Total", "Attendance %"]
    : ["Student ID", "Name", "Sem", "Section", "Present", "Total", "Attendance %"];
  const emptyText = report?.teacher ? "No attendance records for the selected period" : "No data";
  const period = report?.teacher
    ? (report.startDate || report.endDate
        ? `Period: ${report.startDate || "beginning"} to ${report.endDate || "today"}`
        : "Period: all dates")
    : null;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Reports</h2>

      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1rem", alignItems: "flex-end" }}>
        {!myBranch && !isTeacher && (
          <div>
            <label style={labelStyle}>Branch</label>
            <select value={branch} onChange={(e) => setBranch(e.target.value)} style={selectStyle}>
              {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
          </div>
        )}
        {!isTeacher && (
          <div>
            <label style={labelStyle}>Semester</label>
            <select value={semester} onChange={(e) => setSemester(e.target.value)} style={selectStyle}>
              {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
        )}
        <div>
          <label style={labelStyle}>{isTeacher ? "Subject" : "Subject (optional)"}</label>
          <select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} style={{ ...selectStyle, minWidth: "200px" }}>
            <option value="">{isTeacher ? "All my subjects" : "All Subjects"}</option>
            {filterSubjects.map((s) => <option key={s.id} value={s.id}>{s.code} - {s.name}</option>)}
          </select>
        </div>
        {isTeacher && (
          <div>
            <label style={labelStyle}>From</label>
            <input
              type="date"
              value={startDate}
              max={endDate || undefined}
              onChange={(e) => setStartDate(e.target.value)}
              style={selectStyle}
            />
          </div>
        )}
        {isTeacher && (
          <div>
            <label style={labelStyle}>To</label>
            <input
              type="date"
              value={endDate}
              min={startDate || undefined}
              onChange={(e) => setEndDate(e.target.value)}
              style={selectStyle}
            />
          </div>
        )}
        <button onClick={generate} disabled={loading} style={{ ...btnStyle(COLORS.primary), height: "36px" }}>
          {loading ? "Generating..." : "Generate Report"}
        </button>
        {isTeacher && (
          <div style={{ position: "relative" }}>
            {notifyMenu && (
              <div
                style={{ position: "fixed", inset: 0, zIndex: 40 }}
                onClick={() => setNotifyMenu(false)}
              />
            )}
            <button
              onClick={() => setNotifyMenu((v) => !v)}
              style={{ ...btnStyle(COLORS.info), height: "36px", position: "relative", zIndex: 45 }}
            >
              Notification ▾
            </button>
            {notifyMenu && (
              <div style={dropdownStyle}>
                <button
                  style={dropdownItemStyle}
                  onClick={() => { setNotifyMenu(false); setNotifyMode("select"); }}
                >
                  Select Students
                </button>
                <button
                  style={dropdownItemStyle}
                  onClick={() => { setNotifyMenu(false); setNotifyMode("all"); }}
                >
                  Send All
                </button>
              </div>
            )}
          </div>
        )}
        {report && <button onClick={printReport} style={{ ...btnStyle("#95a5a6"), height: "36px" }}>Print</button>}
      </div>

      {isTeacher && (
        <p style={{ color: "#666", fontSize: "0.78rem", marginTop: "-0.5rem", marginBottom: "1rem" }}>
          Reports cover only the subjects assigned to you. Leave the dates empty to include every recorded session.
        </p>
      )}

      {error && <Banner tone="danger">{error}</Banner>}

      {report && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>
            Attendance Report — {report.branch}
            {report.semester ? ` / ${report.semester}` : ""}
            {report.subject ? ` / ${report.subject.code}` : ""}
          </h3>
          <p style={{ color: "#666", fontSize: "0.85rem", marginTop: 0 }}>
            {report.rows.length} students{period ? ` · ${period}` : ""} · Generated on {new Date().toLocaleDateString()}
          </p>

          {report.summary && (
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "0.75rem", marginBottom: "1rem" }}>
              <StatCard label="Students" value={report.summary.students} color={COLORS.accent} />
              <StatCard label="Records" value={report.summary.records} color={COLORS.primary} />
              <StatCard label="Present" value={report.summary.present} color={COLORS.success} />
              <StatCard label="Absent" value={report.summary.absent} color={COLORS.danger} />
              <StatCard
                label="Attendance %"
                value={`${report.summary.pct}%`}
                color={report.summary.pct >= 75 ? COLORS.success : COLORS.danger}
              />
            </div>
          )}

          <div style={{ overflowX: "auto" }}>
            <Table head={head}>
              {report.rows.map((r) => (
                <tr key={r.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={tdStyle}>{r.student_id}</td>
                  <td style={tdStyle}>{r.name}</td>
                  <td style={tdStyle}>{r.semester}</td>
                  <td style={tdStyle}>{r.section}</td>
                  <td style={tdStyle}>{r.present}</td>
                  {report.teacher && <td style={tdStyle}>{r.absent}</td>}
                  <td style={tdStyle}>{r.total}</td>
                  <td style={tdStyle}><strong style={{ color: r.pct >= 75 ? COLORS.success : COLORS.danger }}>{r.pct}%</strong></td>
                </tr>
              ))}
              {report.rows.length === 0 && <EmptyRow colSpan={head.length}>{emptyText}</EmptyRow>}
            </Table>
          </div>
        </div>
      )}
      {notifyMode && (
        <ParentReportNotify mode={notifyMode} onClose={() => setNotifyMode(null)} />
      )}
    </div>
  );
}

const filterBar = {
  display: "flex", gap: "0.5rem", flexWrap: "wrap",
  marginBottom: "1rem", alignItems: "flex-end",
};
const labelStyle = { display: "block", fontSize: "0.72rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };
const tdStyle = { padding: "0.6rem", fontSize: "0.85rem", borderBottom: "1px solid #dee2e6" };
const dropdownStyle = {
  position: "absolute", top: "40px", left: 0, zIndex: 50,
  background: "#fff", border: "1px solid #cbd5e1", borderRadius: "8px",
  boxShadow: "0 8px 20px rgba(0,0,0,0.12)", minWidth: "160px", overflow: "hidden",
};
const dropdownItemStyle = {
  display: "block", width: "100%", textAlign: "left", background: "none",
  border: "none", padding: "0.55rem 0.85rem", fontSize: "0.82rem",
  cursor: "pointer", color: "#1e293b",
};
