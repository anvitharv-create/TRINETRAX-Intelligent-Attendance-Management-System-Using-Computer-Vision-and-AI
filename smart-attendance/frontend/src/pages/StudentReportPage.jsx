import React, { useCallback, useState } from "react";
import { studentReportApi } from "../services/api";
import {
  COLORS, Banner, EmptyRow, StatCard, StatusBadge, Table, buttonStyle, cardStyle, inputStyle, pageStyle, tdStyle,
} from "../theme";

// Student attendance report: profile + summary + full history.
// Deliberately has ONE control (Generate Report) - no semester or subject
// selectors. The date range below the report is an optional refinement of the
// already generated report, never a second "generate" path.
export default function StudentReportPage() {
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");

  const generate = useCallback(async (range = { start_date: "", end_date: "" }) => {
    setLoading(true);
    setError(null);
    try {
      const data = await studentReportApi.get({
        start_date: range.start_date || "",
        end_date: range.end_date || "",
      });
      setReport(data);
      setStartDate(range.start_date || "");
      setEndDate(range.end_date || "");
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to generate report");
      setReport(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const downloadPdf = async () => {
    if (!report) return;
    setDownloading(true);
    setError(null);
    try {
      const blob = await studentReportApi.downloadPdf({
        start_date: report.filter?.start_date || "",
        end_date: report.filter?.end_date || "",
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `attendance_report_${report.student?.usn || "student"}.pdf`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to download PDF");
    } finally {
      setDownloading(false);
    }
  };

  const summary = report?.summary;
  const profile = report?.student;
  const eligible = summary?.eligible;
  const pctColor =
    eligible === true ? COLORS.success : eligible === false ? COLORS.danger : COLORS.primary;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>Attendance Report</h2>
      <p style={{ color: "#64748b", fontSize: "0.9rem", marginTop: "0.25rem" }}>
        Your complete profile and attendance record in one report — generated for you.
      </p>

      <div style={{ display: "flex", gap: "0.6rem", flexWrap: "wrap", margin: "1rem 0 1.25rem" }}>
        <button onClick={() => generate()} disabled={loading} style={buttonStyle(COLORS.primary)}>
          {loading ? "Generating..." : "Generate Report"}
        </button>
        {report && (
          <button onClick={downloadPdf} disabled={downloading} style={buttonStyle(COLORS.success)}>
            {downloading ? "Preparing PDF..." : "Download PDF"}
          </button>
        )}
      </div>

      {error && <Banner tone="danger">{error}</Banner>}

      {!report && !loading && (
        <div style={{ ...cardStyle, textAlign: "center", padding: "2.5rem", color: "#64748b" }}>
          Click <strong>Generate Report</strong> to view your profile, attendance summary and history.
        </div>
      )}

      {report && (
        <>
          <div style={cardStyle}>
            <h3 style={{ marginTop: 0 }}>Student Profile</h3>
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))",
                gap: "0.6rem",
                fontSize: "0.9rem",
              }}
            >
              <div><strong>Name:</strong> {profile?.name || "—"}</div>
              <div><strong>USN / Student ID:</strong> {profile?.usn || "—"}</div>
              <div><strong>Roll number:</strong> {profile?.roll_number || "—"}</div>
              <div><strong>Branch:</strong> {profile?.branch || "—"}</div>
              <div><strong>Semester:</strong> {profile?.semester || "—"}</div>
              <div><strong>Section:</strong> {profile?.section || "—"}</div>
              <div><strong>Email:</strong> {profile?.email || "—"}</div>
              <div><strong>Login ID:</strong> {profile?.username || "—"}</div>
            </div>
          </div>

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
              gap: "1rem",
              marginTop: "1rem",
            }}
          >
            <StatCard
              label="Attendance"
              value={summary?.pct == null ? "—" : `${summary.pct}%`}
              color={pctColor}
              sub={`Minimum ${report.min_attendance_pct}%`}
            />
            <StatCard label="Present" value={summary?.present ?? "—"} color={COLORS.success} />
            <StatCard label="Absent" value={summary?.absent ?? "—"} color={COLORS.danger} />
            <StatCard label="Total classes marked" value={summary?.total_marked ?? "—"} color={COLORS.info} />
          </div>

          <div style={{ marginTop: "1rem" }}>
            {eligible === null && (
              <Banner tone="info">No attendance records yet — eligibility cannot be calculated.</Banner>
            )}
            {eligible === true && (
              <Banner tone="success">
                <strong>Eligible</strong> — your attendance of {summary.pct}% meets the minimum{" "}
                {report.min_attendance_pct}% requirement.
              </Banner>
            )}
            {eligible === false && (
              <Banner tone="warning">
                <strong>Not eligible</strong> — your attendance of {summary.pct}% is {summary.shortage_pct}{" "}
                percentage points below the minimum {report.min_attendance_pct}%.
                {summary.classes_to_attend
                  ? ` Attend the next ${summary.classes_to_attend} class(es) — all present — to reach the requirement.`
                  : ""}
              </Banner>
            )}
          </div>

          <div style={{ ...cardStyle, marginTop: "1rem" }}>
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                flexWrap: "wrap",
                gap: "0.6rem",
                marginBottom: "0.75rem",
              }}
            >
              <h3 style={{ margin: 0 }}>Attendance History</h3>
              <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", flexWrap: "wrap" }}>
                <span style={{ fontSize: "0.78rem", color: "#555", fontWeight: 600 }}>Date range (optional)</span>
                <input
                  type="date"
                  value={startDate}
                  max={endDate || undefined}
                  onChange={(e) => setStartDate(e.target.value)}
                  style={inputStyle}
                />
                <input
                  type="date"
                  value={endDate}
                  min={startDate || undefined}
                  onChange={(e) => setEndDate(e.target.value)}
                  style={inputStyle}
                />
                <button
                  onClick={() => generate({ start_date: startDate, end_date: endDate })}
                  disabled={loading}
                  style={buttonStyle(COLORS.accent)}
                >
                  Update
                </button>
                {(startDate || endDate) && (
                  <button onClick={() => generate({ start_date: "", end_date: "" })} style={buttonStyle(COLORS.muted)}>
                    Clear
                  </button>
                )}
              </div>
            </div>

            <div style={{ overflowX: "auto" }}>
              <Table head={["Date", "Subject", "Attendance"]}>
                {!report.records?.length ? (
                  <EmptyRow colSpan={3}>No attendance records in this period.</EmptyRow>
                ) : (
                  report.records.map((rec) => (
                    <tr key={rec.id}>
                      <td style={tdStyle}>{rec.date}</td>
                      <td style={tdStyle}>
                        {rec.subject} <span style={{ color: "#94a3b8" }}>({rec.subject_code})</span>
                      </td>
                      <td style={tdStyle}>
                        <StatusBadge status={rec.status} />
                      </td>
                    </tr>
                  ))
                )}
              </Table>
            </div>

            <div style={{ marginTop: "0.6rem", fontSize: "0.78rem", color: "#94a3b8" }}>
              Generated on {report.generated_at} · Minimum attendance required: {report.min_attendance_pct}%
            </div>
          </div>
        </>
      )}
    </div>
  );
}
