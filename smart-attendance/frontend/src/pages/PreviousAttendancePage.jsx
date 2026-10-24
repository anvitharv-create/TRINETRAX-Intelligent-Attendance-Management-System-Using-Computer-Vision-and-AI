import React, { useState, useEffect } from "react";
import { useAuth } from "../services/authContext";
import { subjectsApi, attendanceApi, studentsApi, teacherApi } from "../services/api";
import { COLORS, SEMESTERS, buttonStyle, pageStyle, Table, EmptyRow } from "../theme";

export default function PreviousAttendancePage() {
  const { user } = useAuth();
  const isTeacher = user?.role === "teacher";
  const [semester, setSemester] = useState("");
  const [subjects, setSubjects] = useState([]);
  const [subjectId, setSubjectId] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [teacherId, setTeacherId] = useState(null);
  const [attendance, setAttendance] = useState([]);
  const [studentMap, setStudentMap] = useState({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (isTeacher && !teacherId) {
      teacherApi.dashboard().then((d) => setTeacherId(d.teacher_id)).catch(() => {});
    }
  }, [isTeacher, teacherId]);

  useEffect(() => {
    if (semester) {
      setSubjectId("");
      setSubjects([]);
      if (isTeacher) {
        // wait for the teacher id so the list can never show unassigned subjects
        if (!teacherId) return;
        subjectsApi.list(user?.branch || "", semester, teacherId).then(setSubjects).catch(() => {});
        return;
      }
      subjectsApi.list(user?.branch || "", semester).then(setSubjects).catch(() => {});
    }
  }, [semester, user, isTeacher, teacherId]);

  const load = async () => {
    if (!subjectId) { setError("Select a subject first."); return; }
    if (startDate && endDate && startDate > endDate) {
      setError("The start date must be on or before the end date.");
      return;
    }
    setLoading(true);
    setError(null);
    setLoaded(false);
    try {
      const data = await attendanceApi.list(Number(subjectId), null, startDate || null, endDate || null);
      setAttendance(data);
      setLoaded(true);
      const studentIds = [...new Set(data.map((r) => r.student_id))];
      if (studentIds.length > 0) {
        try {
          const all = await studentsApi.list({});
          const map = {};
          all.forEach((s) => { map[s.id] = s; });
          setStudentMap(map);
        } catch (_) {}
      }
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load");
    } finally {
      setLoading(false);
    }
  };

  const selectedSubject = subjects.find((s) => String(s.id) === String(subjectId));

  const grouped = {};
  attendance.forEach((r) => {
    if (!grouped[r.student_id]) grouped[r.student_id] = { present: 0, total: 0 };
    grouped[r.student_id].total++;
    if (r.status === "present") grouped[r.student_id].present++;
  });

  const rows = Object.entries(grouped)
    .map(([sid, data]) => ({
      sid,
      data,
      usn: studentMap[sid]?.student_id || `#${sid}`,
      name: studentMap[sid]?.name || `Student #${sid}`,
    }))
    .sort((a, b) => String(a.usn).localeCompare(String(b.usn)));

  const period = startDate || endDate
    ? `Period: ${startDate || "beginning"} to ${endDate || "today"}`
    : "All dates";

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Attendance History</h2>

      <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap", alignItems: "flex-end" }}>
        <div>
          <label style={labelStyle}>Semester</label>
          <select value={semester} onChange={(e) => setSemester(e.target.value)} style={selectStyle}>
            <option value="">Select semester</option>
            {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Subject{isTeacher ? " (yours)" : ""}</label>
          <select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} style={{ ...selectStyle, minWidth: "220px" }} disabled={!semester}>
            <option value="">{semester ? "Select subject" : "Select semester first"}</option>
            {subjects.map((s) => <option key={s.id} value={s.id}>{s.code} - {s.name}</option>)}
          </select>
        </div>
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
        <button onClick={load} disabled={!subjectId || loading} style={{ ...buttonStyle(COLORS.primary), height: "36px" }}>
          {loading ? "Loading..." : "Load"}
        </button>
      </div>

      {error && <p style={{ color: "#c53030" }}>{error}</p>}

      {loaded && selectedSubject && (
        <div>
          <h3>{selectedSubject.code} — {selectedSubject.name}</h3>
          <p style={{ color: "#666", fontSize: "0.85rem", marginTop: 0 }}>
            {period} · {rows.length} students
          </p>
          <div style={{ overflowX: "auto" }}>
            <Table head={["USN", "Name", "Present", "Total", "Attendance %"]}>
              {rows.map((row) => {
                const pct = row.data.total > 0 ? Math.round((row.data.present / row.data.total) * 100) : 0;
                return (
                  <tr key={row.sid} style={{ borderBottom: "1px solid #dee2e6" }}>
                    <td style={tdStyle}>{row.usn}</td>
                    <td style={tdStyle}>{row.name}</td>
                    <td style={tdStyle}>{row.data.present}</td>
                    <td style={tdStyle}>{row.data.total}</td>
                    <td style={tdStyle}>
                      <span style={{ fontWeight: 700, color: pct >= 75 ? COLORS.success : COLORS.danger }}>{pct}%</span>
                    </td>
                  </tr>
                );
              })}
              {rows.length === 0 && <EmptyRow colSpan={5}>No records found</EmptyRow>}
            </Table>
          </div>
        </div>
      )}

      {loaded && attendance.length === 0 && !error && (
        <div style={{ textAlign: "center", padding: "2rem", color: "#6c757d" }}>No attendance records found</div>
      )}
    </div>
  );
}

const labelStyle = { display: "block", fontSize: "0.72rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };
const selectStyle = { padding: "0.5rem", borderRadius: "4px", border: "1px solid #ced4da" };
const tdStyle = { padding: "0.6rem", fontSize: "0.85rem", borderBottom: "1px solid #dee2e6" };
