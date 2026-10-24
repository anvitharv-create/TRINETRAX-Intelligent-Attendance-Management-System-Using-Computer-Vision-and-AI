import React, { useState } from "react";
import { useAuth } from "../services/authContext";
import { useAnalytics } from "../services/analyticsService";
import { analyticsApi } from "../services/api";
import { COLORS, SEMESTERS, pageStyle, smallButton, Table, EmptyRow } from "../theme";

export default function AnalyticsPage() {
  const { user } = useAuth();
  const { overall, loading, error } = useAnalytics();
  const [semester, setSemester] = useState("");
  const [semData, setSemData] = useState(null);
  const [semLoading, setSemLoading] = useState(false);
  const [semError, setSemError] = useState(null);

  const isHod = user?.role === "hod" || user?.role === "teacher";

  const loadSemester = async (sem) => {
    setSemester(sem);
    setSemLoading(true);
    setSemError(null);
    setSemData(null);
    try {
      const data = await analyticsApi.semester(sem);
      setSemData(data);
    } catch (err) {
      setSemError(err?.response?.data?.detail || err.message);
    } finally {
      setSemLoading(false);
    }
  };

  if (loading) return <div style={{ padding: "2rem" }}>Loading analytics...</div>;
  if (error) return <div style={{ padding: "2rem", color: "#c53030" }}>{error}</div>;
  if (!overall) return <div style={{ padding: "2rem" }}>No data yet</div>;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Analytics Overview</h2>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "1rem", marginBottom: "2rem" }}>
        {[
          ["Total Students", overall.total_students, "#3498db"],
          ["Total Teachers", overall.total_teachers, "#27ae60"],
          ["Total Subjects", overall.total_subjects, "#e67e22"],
          ["Classes Held", overall.total_classes, "#9b59b6"],
          ["Present Today", overall.present_today, "#27ae60"],
          ["Absent Today", overall.absent_today, "#e74c3c"],
          ["Below 75%", overall.below_threshold_students, "#e67e22"],
        ].map(([label, value, color]) => (
          <div key={label} style={{ background: "#ecf0f1", borderRadius: "8px", padding: "1.2rem", textAlign: "center", border: "1px solid #d5d8dc" }}>
            <div style={{ fontSize: "1.8rem", fontWeight: "bold", color }}>{value}</div>
            <div style={{ fontSize: "0.8rem", color: "#444" }}>{label}</div>
          </div>
        ))}
      </div>

      <h3>Branch-wise Attendance</h3>
      <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: "2rem" }}>
        <thead>
          <tr style={{ background: "#1a1a2e", color: "white" }}>
            <th style={thStyle}>Branch</th>
            <th style={thStyle}>Students</th>
            <th style={thStyle}>Attendance %</th>
          </tr>
        </thead>
        <tbody>
          {overall.branch_stats.map((bs) => (
            <tr key={bs.branch} style={{ borderBottom: "1px solid #dee2e6" }}>
              <td style={tdStyle}>{bs.branch}</td>
              <td style={tdStyle}>{bs.total_students}</td>
              <td style={tdStyle}>{bs.overall_attendance_pct}%</td>
            </tr>
          ))}
        </tbody>
      </table>

      {isHod && (
        <div>
          <h3>View by Semester</h3>
          <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
            {SEMESTERS.map((s) => (
              <button key={s} onClick={() => loadSemester(s)} style={{ ...smallButton(semester === s ? COLORS.primary : "#e0e0e0"), color: semester === s ? "#fff" : "#333" }}>
                {s}
              </button>
            ))}
          </div>

          {semLoading && <p>Loading...</p>}
          {semError && <p style={{ color: "#c53030" }}>{semError}</p>}

          {semData && (
            <div>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "1rem", marginBottom: "1.5rem" }}>
                <div style={{ background: "#ecf0f1", borderRadius: "8px", padding: "1rem", textAlign: "center" }}>
                  <div style={{ fontSize: "1.5rem", fontWeight: "bold", color: "#3498db" }}>{semData.total_students}</div>
                  <div style={{ fontSize: "0.8rem", color: "#444" }}>Students in {semData.semester}</div>
                </div>
                <div style={{ background: "#ecf0f1", borderRadius: "8px", padding: "1rem", textAlign: "center" }}>
                  <div style={{ fontSize: "1.5rem", fontWeight: "bold", color: "#e67e22" }}>{semData.below_threshold.length}</div>
                  <div style={{ fontSize: "0.8rem", color: "#444" }}>Below 75%</div>
                </div>
              </div>

              {semData.subjects.length > 0 && (
                <>
                  <h4>Subject-wise Attendance</h4>
                  <Table head={["Subject Code", "Subject", "Total", "Present", "Attendance %"]}>
                    {semData.subjects.map((s) => (
                      <tr key={s.code} style={{ borderBottom: "1px solid #dee2e6" }}>
                        <td style={tdStyle}>{s.code}</td>
                        <td style={tdStyle}>{s.name}</td>
                        <td style={tdStyle}>{s.total}</td>
                        <td style={tdStyle}>{s.present}</td>
                        <td style={tdStyle}><strong style={{ color: s.pct >= 75 ? COLORS.success : COLORS.danger }}>{s.pct}%</strong></td>
                      </tr>
                    ))}
                  </Table>
                </>
              )}

              {semData.below_threshold.length > 0 && (
                <>
                  <h4 style={{ marginTop: "1rem" }}>Students Below 75%</h4>
                  <Table head={["Student ID", "Name", "Branch", "Attendance %"]}>
                    {semData.below_threshold.map((s) => (
                      <tr key={s.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
                        <td style={tdStyle}>{s.student_id}</td>
                        <td style={tdStyle}>{s.name}</td>
                        <td style={tdStyle}>{s.branch}</td>
                        <td style={tdStyle}><strong style={{ color: COLORS.danger }}>{s.percentage}%</strong></td>
                      </tr>
                    ))}
                  </Table>
                </>
              )}
            </div>
          )}
        </div>
      )}

      <h3 style={{ marginTop: "2rem" }}>Students below 75% attendance</h3>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ background: "#1a1a2e", color: "white" }}>
            <th style={thStyle}>Student ID</th>
            <th style={thStyle}>Name</th>
            <th style={thStyle}>Attendance %</th>
          </tr>
        </thead>
        <tbody>
          {overall.below_threshold_list.map((s) => (
            <tr key={s.student_id} style={{ borderBottom: "1px solid #dee2e6" }}>
              <td style={tdStyle}>{s.student_id}</td>
              <td style={tdStyle}>{s.name}</td>
              <td style={tdStyle}><strong>{s.percentage}%</strong></td>
            </tr>
          ))}
          {overall.below_threshold_list.length === 0 && (
            <tr><td colSpan={3} style={{ ...tdStyle, textAlign: "center", color: "#6c757d" }}>No students below threshold</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

const thStyle = { padding: "0.6rem", textAlign: "left" };
const tdStyle = { padding: "0.6rem", borderBottom: "1px solid #dee2e6" };
