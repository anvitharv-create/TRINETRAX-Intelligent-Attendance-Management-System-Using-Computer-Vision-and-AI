import React, { useState, useEffect, useCallback } from "react";
import { useAuth } from "../services/authContext";
import { studentsApi, attendanceApi, subjectsApi } from "../services/api";
import { COLORS, StatCard, Banner, Table, EmptyRow, pageStyle } from "../theme";
import { StatusBadge } from "../theme";

const labelStyle = { display: "block", fontSize: "0.72rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };
const selectStyle = { padding: "0.5rem", borderRadius: "4px", border: "1px solid #ced4da", minWidth: "240px" };

export default function StudentAttendancePage() {
  const { user } = useAuth();
  const [myStudent, setMyStudent] = useState(null);
  const [records, setRecords] = useState([]);
  const [subjects, setSubjects] = useState([]);
  const [subjectFilter, setSubjectFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!user?.student_id) { setLoading(false); return; }
    studentsApi.list({ branch: user.branch, semester: user.semester, section: user.section })
      .then((list) => {
        const me = list.find((s) => s.student_id === user.student_id);
        setMyStudent(me);
      })
      .catch(() => { setLoading(false); });
  }, [user?.student_id, user?.branch, user?.semester, user?.section]);

  const load = useCallback(async () => {
    if (!myStudent) { setLoading(false); return; }
    setLoading(true);
    setError(null);
    try {
      const recs = await attendanceApi.student(myStudent.id);
      const subs = await subjectsApi.list(myStudent.branch, myStudent.semester);
      setRecords(recs);
      setSubjects(subs);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message);
    } finally {
      setLoading(false);
    }
  }, [myStudent]);

  useEffect(() => { load(); }, [load]);

  const studentSubjects = subjects.filter(
    (s) => s.branch === myStudent?.branch && s.semester === myStudent?.semester
  );

  const sortedRecords = [...records].sort((a, b) => {
    const d = String(b.date || "").localeCompare(String(a.date || ""));
    return d !== 0 ? d : (b.id || 0) - (a.id || 0);
  });

  const filteredRecords = subjectFilter
    ? sortedRecords.filter((r) => r.subject_id === Number(subjectFilter))
    : sortedRecords;

  const selectedSubject = studentSubjects.find((s) => String(s.id) === String(subjectFilter));

  const total = filteredRecords.length;
  const present = filteredRecords.filter((r) => r.status === "present").length;
  const absent = filteredRecords.filter((r) => r.status === "absent").length;
  const pctNum = total ? (present / total) * 100 : 0;
  const overallPct = pctNum.toFixed(1);
  const atRisk = total > 0 && pctNum < 75;

  const subjectStats = studentSubjects
    .map((sub) => {
      const subRecs = sortedRecords.filter((r) => r.subject_id === sub.id);
      const p = subRecs.filter((r) => r.status === "present").length;
      return {
        ...sub,
        total: subRecs.length,
        present: p,
        pct: subRecs.length ? ((p / subRecs.length) * 100).toFixed(1) : "0.0",
      };
    })
    .sort((a, b) => a.pct - b.pct);

  const lowSubjects = subjectStats.filter((s) => Number(s.pct) > 0 && Number(s.pct) < 75);

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>My Attendance</h2>
      <p style={{ color: "#666" }}>
        Attendance records for <strong>{user?.full_name}</strong> (Student ID: {user?.student_id})
      </p>

      <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1.5rem", flexWrap: "wrap", alignItems: "flex-end" }}>
        <div>
          <label style={labelStyle} htmlFor="subject-filter">Subject</label>
          <select
            id="subject-filter"
            value={subjectFilter}
            onChange={(e) => setSubjectFilter(e.target.value)}
            style={selectStyle}
            disabled={!myStudent}
          >
            <option value="">All subjects</option>
            {studentSubjects.map((s) => (
              <option key={s.id} value={s.id}>{s.code} - {s.name}</option>
            ))}
          </select>
        </div>
        <p style={{ color: "#666", fontSize: "0.85rem", margin: 0 }}>
          {selectedSubject
            ? <>Showing attendance for <strong>{selectedSubject.name}</strong> ({selectedSubject.code})</>
            : "Showing attendance for all subjects"}
        </p>
      </div>

      <div style={{ display: "flex", gap: "1rem", marginBottom: "1.5rem", flexWrap: "wrap" }}>
        <StatCard
          label="Overall Attendance"
          value={total ? `${overallPct}%` : "—"}
          color={total ? (atRisk ? COLORS.warning : COLORS.success) : "#888"}
          sub={selectedSubject ? selectedSubject.code : "All subjects"}
        />
        <StatCard label="Classes Present" value={present} color={COLORS.success} />
        <StatCard label="Classes Absent" value={absent} color={COLORS.danger} />
        <StatCard label="Total Classes" value={total} color={COLORS.info} />
      </div>

      {lowSubjects.length > 0 && (
        <Banner tone="warning" style={{ marginBottom: "1rem" }}>
          ⚠️ Your attendance is below 75% in {lowSubjects.map((s) => <strong key={s.id}>{s.name}</strong>).reduce((acc, el, i, arr) => i === 0 ? [el] : [...acc, <span key={`s${i}`}>{i === arr.length - 1 ? " and " : ", "}</span>, el], [])}. Please attend classes regularly to maintain eligibility.
        </Banner>
      )}

      <div style={{ marginBottom: "1.5rem" }}>
        <h3>Subject-wise Attendance</h3>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: "0.75rem" }}>
          {studentSubjects.length === 0 && !loading && <p style={{ color: "#888" }}>No subject data yet.</p>}
          {subjectStats.map((s) => {
            const pctNum = Number(s.pct);
            return (
              <div key={s.id} style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "0.75rem 1rem" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontSize: "0.9rem", fontWeight: 600 }}>{s.name}</span>
                  <span style={{ fontSize: "0.85rem", fontWeight: 700, color: pctNum >= 75 ? COLORS.success : pctNum > 0 ? COLORS.warning : "#888" }}>
                    {s.pct}% {pctNum > 0 && pctNum < 75 && "⚠️"}
                  </span>
                </div>
                <div style={{ height: 8, background: "#ecf0f1", borderRadius: 4, marginTop: "0.4rem", overflow: "hidden" }}>
                  <div style={{ width: `${Math.min(100, pctNum)}%`, height: "100%", background: pctNum >= 75 ? COLORS.success : pctNum > 0 ? COLORS.warning : "#d0d0d0" }} />
                </div>
                <div style={{ fontSize: "0.75rem", color: "#888", marginTop: "0.3rem" }}>{s.present}/{s.total} classes attended</div>
              </div>
            );
          })}
        </div>
      </div>

      <h3>Attendance History</h3>
      {loading ? (
        <p>Loading...</p>
      ) : error ? (
        <p style={{ color: "#c53030" }}>{error}</p>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <Table head={["Date", "Subject", "Attendance", "Confidence"]}>
            {filteredRecords.map((r) => {
              const subj = studentSubjects.find((s) => s.id === r.subject_id);
              return (
                <tr key={r.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{r.date}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{subj ? `${subj.name} (${subj.code})` : `Subject #${r.subject_id}`}</td>
                  <td style={{ padding: "0.6rem" }}><StatusBadge status={r.status} /></td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{r.confidence != null ? `${(r.confidence * 100).toFixed(0)}%` : "—"}</td>
                </tr>
              );
            })}
            {filteredRecords.length === 0 && (
              <EmptyRow colSpan={4}>
                {subjectFilter ? "No attendance records for this subject yet." : "No attendance records yet."}
              </EmptyRow>
            )}
          </Table>
        </div>
      )}
    </div>
  );
}
