import React, { useState, useEffect, useCallback } from "react";
import { useAuth } from "../services/authContext";
import { useAnalytics } from "../services/analyticsService";
import { teacherApi } from "../services/api";
import { COLORS, StatCard, Banner, pageStyle, Table, EmptyRow } from "../theme";

function StatCardMini({ label, value, color }) {
  return (
    <div style={{ background: "#ecf0f1", borderRadius: "8px", padding: "1.2rem", textAlign: "center", border: "1px solid #d5d8dc" }}>
      <div style={{ fontSize: "2rem", fontWeight: "bold", color }}>{value}</div>
      <div style={{ fontSize: "0.8rem", color: "#444" }}>{label}</div>
    </div>
  );
}

function TeacherDashboard({ user }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      const d = await teacherApi.dashboard();
      setData(d);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load dashboard");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (error) return <Banner tone="danger">{error}</Banner>;
  if (!data) return <Banner tone="info">Loading your dashboard...</Banner>;

  const greeting = () => {
    const h = new Date().getHours();
    if (h < 12) return "Good morning";
    if (h < 17) return "Good afternoon";
    return "Good evening";
  };

  const totalClassSize = data.today.total_class_size;
  const totalPresent = data.today.total_present;
  const totalMarked = data.today.total_marked;
  const attendancePct = totalClassSize > 0 ? Math.round((totalPresent / totalClassSize) * 100) : 0;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>{greeting()}, {data.teacher_name || user?.full_name}</h2>
      <p style={{ color: "#666" }}>
        <strong style={{ textTransform: "capitalize" }}>Teacher</strong> · {data.branch}
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "1rem", marginTop: "1.5rem" }}>
        <StatCard label="Subjects" value={data.total_subjects} color={COLORS.info} sub="Assigned to you" />
        <StatCard label="Class Strength" value={totalClassSize} color={COLORS.purple} sub="Students across classes" />
        <StatCard label="Present Today" value={totalPresent} color={COLORS.success} sub={`${attendancePct}% attendance`} />
        <StatCard label="Marked Today" value={totalMarked} color={COLORS.primary} sub="Records saved" />
      </div>

      {/* Today Attendance Quick View */}
      <div style={{ marginTop: "2rem", background: "#f0fdf4", border: "1px solid #bbf7d0", borderRadius: "10px", padding: "1.2rem" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "0.75rem" }}>
          <div>
            <h3 style={{ margin: 0, fontSize: "1.1rem", color: "#166534" }}>Today's Attendance Summary</h3>
            <p style={{ margin: "0.3rem 0 0", fontSize: "0.85rem", color: "#555" }}>
              {totalMarked > 0
                ? `${totalPresent} present out of ${totalClassSize} students (${attendancePct}%)`
                : "No attendance marked yet today"}
            </p>
          </div>
          <a
            href="/teacher/attendance"
            style={{
              background: COLORS.success,
              color: "#fff",
              border: "none",
              borderRadius: "8px",
              padding: "0.6rem 1.2rem",
              fontSize: "0.9rem",
              fontWeight: 600,
              cursor: "pointer",
              textDecoration: "none",
              display: "inline-flex",
              alignItems: "center",
              gap: "0.4rem",
            }}
          >
            Take Attendance
          </a>
        </div>

        {data.subjects.length > 0 && (
          <div style={{ marginTop: "1rem", display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "0.5rem" }}>
            {data.subjects.map((s) => (
              <div key={s.subject_id} style={{ background: "#fff", borderRadius: "8px", padding: "0.75rem", border: "1px solid #e5e7eb" }}>
                <div style={{ fontWeight: 600, fontSize: "0.85rem" }}>{s.code}</div>
                <div style={{ fontSize: "0.75rem", color: "#666" }}>{s.name} · {s.semester}</div>
                <div style={{ marginTop: "0.4rem", display: "flex", gap: "0.75rem", fontSize: "0.8rem" }}>
                  <span style={{ color: COLORS.success, fontWeight: 600 }}>{s.present_today} present</span>
                  <span style={{ color: COLORS.danger }}>{s.absent_today} absent</span>
                </div>
                <div style={{ fontSize: "0.7rem", color: "#888", marginTop: "0.2rem" }}>
                  {s.students_in_class} students · {s.marked_today > 0 ? "Marked" : "Not marked"}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div style={{ marginTop: "2rem" }}>
        <h3>My Subjects ({data.total_subjects})</h3>
        <Table head={["Subject Code", "Subject", "Semester", "Class Size", "Present", "Absent", "Status"]}>
          {data.subjects.length === 0 && <EmptyRow colSpan={7}>No subjects are registered under your name yet.</EmptyRow>}
          {data.subjects.map((s) => (
            <tr key={s.subject_id} style={{ borderBottom: "1px solid #dee2e6" }}>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.code}</td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong>{s.name}</strong></td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.semester}</td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.students_in_class}</td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem", color: COLORS.success, fontWeight: 700 }}>{s.present_today}</td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem", color: COLORS.danger }}>{s.absent_today}</td>
              <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                <span style={{
                  padding: "0.2rem 0.5rem",
                  borderRadius: "4px",
                  fontSize: "0.75rem",
                  fontWeight: 600,
                  background: s.marked_today > 0 ? "#dcfce7" : "#fef3c7",
                  color: s.marked_today > 0 ? "#166534" : "#92400e",
                }}>
                  {s.marked_today > 0 ? "Marked" : "Pending"}
                </span>
              </td>
            </tr>
          ))}
        </Table>
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const { user } = useAuth();
  const { overall } = useAnalytics();

  if (user?.role === "teacher") {
    return <TeacherDashboard user={user} />;
  }

  const greeting = () => {
    const h = new Date().getHours();
    if (h < 12) return "Good morning";
    if (h < 17) return "Good afternoon";
    return "Good evening";
  };

  return (
    <div style={{ padding: "2rem" }}>
      <h2 style={{ marginTop: 0 }}>
        {greeting()}, {user?.full_name || "there"} 👋
      </h2>
      <p style={{ color: "#666" }}>
        You are signed in as <strong style={{ textTransform: "capitalize" }}>{user?.role}</strong>
        {user?.branch ? ` (${user.branch})` : ""}
      </p>

      {user?.role === "hod" && user?.branch && (
        <Banner tone="info" style={{ marginBottom: "1rem" }}>
          Showing <strong>{user.branch}</strong> department data only.
        </Banner>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "1rem", marginTop: "1.5rem" }}>
        <StatCardMini label="Total Students" value={overall?.total_students ?? "—"} color="#3498db" />
        <StatCardMini label="Total Teachers" value={overall?.total_teachers ?? "—"} color="#27ae60" />
        <StatCardMini label="Total Subjects" value={overall?.total_subjects ?? "—"} color="#e67e22" />
        <StatCardMini label="Classes Held" value={overall?.total_classes ?? "—"} color="#9b59b6" />
      </div>

      {user?.role === "hod" && (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "1rem", marginTop: "1rem" }}>
          <StatCardMini label="Present Today" value={overall?.present_today ?? "—"} color="#27ae60" />
          <StatCardMini label="Absent Today" value={overall?.absent_today ?? "—"} color="#e74c3c" />
          <StatCardMini label="Below 75% Attendance" value={overall?.below_threshold_students ?? "—"} color="#e67e22" />
        </div>
      )}
    </div>
  );
}