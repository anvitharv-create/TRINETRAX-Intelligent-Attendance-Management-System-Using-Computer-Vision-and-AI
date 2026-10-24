import React, { useState, useEffect, useCallback } from "react";
import { useAuth } from "../services/authContext";
import { studentsApi, attendanceApi, subjectsApi, recognitionApi } from "../services/api";
import { useNotifications } from "../services/notificationsService";
import { COLORS, StatCard, Banner, pageStyle, selectStyle } from "../theme";

export default function StudentDashboardPage() {
  const { user } = useAuth();
  const { notifications, bindUser } = useNotifications();
  const [myStudent, setMyStudent] = useState(null);
  const [records, setRecords] = useState([]);
  const [subjects, setSubjects] = useState([]);
  const [faceStatus, setFaceStatus] = useState(null);

  useEffect(() => {
    if (!user?.student_id) return;
    studentsApi.list({ branch: user.branch, semester: user.semester, section: user.section })
      .then((list) => {
        const me = list.find((s) => s.student_id === user.student_id);
        setMyStudent(me);
      })
      .catch(() => {});
  }, [user?.student_id, user?.branch, user?.semester, user?.section]);

  const load = useCallback(async () => {
    if (!myStudent) return;
    const recs = await attendanceApi.student(myStudent.id);
    setRecords(recs);
    const subs = await subjectsApi.list(myStudent.branch, myStudent.semester);
    setSubjects(subs);
  }, [myStudent]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (user?.id) bindUser(user.id);
  }, [user?.id, bindUser]);

  useEffect(() => { recognitionApi.status().then(setFaceStatus).catch(() => {}); }, []);

  const total = records.length;
  const present = records.filter((r) => r.status === "present").length;
  const overallPct = total ? ((present / total) * 100).toFixed(1) : "0.0";

  const subjectStats = subjects
    .map((sub) => {
      const subRecs = records.filter((r) => r.subject_id === sub.id);
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
  const todayStatus = records.length ? records[records.length - 1] : null;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>My Dashboard</h2>
      <p style={{ color: "#666" }}>Welcome back, <strong>{user?.full_name}</strong> ({user?.student_id})</p>

      {lowSubjects.length > 0 && (
        <Banner tone="warning">
          ⚠️ Your attendance is below 75% in {lowSubjects.length > 1 ? `these subjects: ${lowSubjects.map((s) => s.name).join(", ")}` : `${lowSubjects[0].name}. Please attend classes regularly to avoid penalties.`}
        </Banner>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "1rem", marginBottom: "1.5rem" }}>
        <StatCard label="Overall Attendance" value={`${overallPct}%`} color={overallPct >= 75 ? COLORS.success : COLORS.warning} />
        <StatCard label="Classes Attended" value={present} color={COLORS.success} />
        <StatCard label="Total Classes" value={total} color={COLORS.info} />
        <StatCard label="Subjects" value={subjects.length} color={COLORS.purple} />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1.5rem", flexWrap: "wrap" }}>
        <div>
          <h3>Subject-wise Attendance</h3>
          {subjectStats.length === 0 && <p style={{ color: "#888" }}>No subject data yet.</p>}
          {subjectStats.map((s) => {
            const pctNum = Number(s.pct);
            return (
              <div key={s.id} style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "0.75rem 1rem", marginBottom: "0.5rem" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontSize: "0.9rem", fontWeight: 600 }}>{s.name}</span>
                  <span style={{ fontSize: "0.85rem", color: pctNum >= 75 ? COLORS.success : pctNum > 0 ? COLORS.warning : "#888", fontWeight: 700 }}>
                    {s.pct}% {pctNum > 0 && pctNum < 75 && "⚠️"}
                  </span>
                </div>
                <div style={{ height: 8, background: "#ecf0f1", borderRadius: 4, marginTop: "0.4rem", overflow: "hidden" }}>
                  <div style={{ width: `${Math.min(100, pctNum)}%`, height: "100%", background: pctNum >= 75 ? COLORS.success : pctNum > 0 ? COLORS.warning : "#d0d0d0" }} />
                </div>
              </div>
            );
          })}
        </div>

        <div>
          <h3>Notifications</h3>
          <div style={{ maxHeight: 300, overflowY: "auto", border: "1px solid #eee", borderRadius: "8px" }}>
            {notifications.length === 0 && <p style={{ padding: "1rem", color: "#888" }}>No notifications.</p>}
            {notifications.map((n) => (
              <div key={n.id} style={{ padding: "0.75rem 1rem", borderBottom: "1px solid #f0f0f0", background: n.read ? "#fff" : "#f8f9fa" }}>
                <div style={{ fontSize: "0.85rem", fontWeight: 600 }}>{n.title}</div>
                <div style={{ fontSize: "0.8rem", color: "#555" }}>{n.message}</div>
              </div>
            ))}
          </div>

          <h3 style={{ marginTop: "1rem" }}>Face Recognition Status</h3>
          {faceStatus?.real ? (
            <Banner tone="success">Your face can be recognized automatically. {faceStatus.registered} face(s) registered.</Banner>
          ) : (
            <Banner tone="warning">Face recognition backend unavailable — results simulated.</Banner>
          )}
        </div>
      </div>
    </div>
  );
}