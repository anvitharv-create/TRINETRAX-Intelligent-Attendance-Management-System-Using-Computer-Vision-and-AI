import React, { useState, useEffect } from "react";
import CameraPreview from "../components/CameraPreview";
import { useSubjects } from "../services/subjectsService";
import { useAttendance } from "../services/attendanceService";
import { studentsApi } from "../services/api";
import { useAuth } from "../services/authContext";

const COLORS = { primary: "#1a1a2e", accent: "#e94f37", success: "#27ae60", warning: "#e67e22" };

function StatusBadge({ status }) {
  const map = {
    present: { bg: "#27ae60", label: "Present" },
    absent: { bg: "#e74c3c", label: "Absent" },
    unknown: { bg: "#95a5a6", label: "Unknown" },
    late: { bg: "#f39c12", label: "Late" },
  };
  const s = map[status] || { bg: "#95a5a6", label: status };
  return (
    <span style={{ background: s.bg, color: "white", padding: "0.2rem 0.6rem", borderRadius: "10px", fontSize: "0.7rem" }}>
      {s.label}
    </span>
  );
}

export default function AttendancePage({ student_id = null }) {
  const { user } = useAuth();
  const myBranch = user?.role === "hod" || user?.role === "teacher" ? user.branch : "ISE";
  const { subjects, loading: subjectsLoading, fetchSubjects } = useSubjects();
  const [students, setStudents] = useState([]);
  const { attendance, loading, error, fetchAttendance, captureAttendance, removeAttendance } = useAttendance();

  const [subjectId, setSubjectId] = useState("");
  const [date, setDate] = useState(() => new Date().toISOString().split("T")[0]);
  const [capturedImage, setCapturedImage] = useState(null);
  const [cameraError, setCameraError] = useState("");
  const [processing, setProcessing] = useState(false);
  const [result, setResult] = useState(null);
  const [mode, setMode] = useState("camera");
  const [manualResults, setManualResults] = useState([]);

  useEffect(() => {
    fetchSubjects(myBranch, "1st Semester");
  }, [myBranch, fetchSubjects]);

  useEffect(() => {
    studentsApi.list({ branch: myBranch }).then(setStudents).catch(() => {});
  }, [myBranch]);

  const loadRecords = async () => {
    if (subjectId) await fetchAttendance(subjectId, date);
  };

  useEffect(() => {
    if (subjectId && mode === "records") {
      loadRecords();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectId, date, mode]);

  const handleCapture = async () => {
    if (!subjectId) {
      setCameraError("Please select a subject first");
      return;
    }
    if (!capturedImage) {
      setCameraError("Please capture a classroom image first");
      return;
    }
    setProcessing(true);
    setResult(null);
    setCameraError("");
    try {
      const res = await captureAttendance({
        subject_id: Number(subjectId),
        date,
        image_base64: capturedImage,
        use_recognition: true,
      });
      setResult(res);
      setMode("records");
      if (subjectId) await fetchAttendance(subjectId, date);
    } catch (err) {
      setCameraError(err?.response?.data?.detail || err.message || "Failed to capture attendance");
    } finally {
      setProcessing(false);
    }
  };

  const submitManual = async () => {
    if (!subjectId) {
      setCameraError("Please select a subject first");
      return;
    }
    const results = manualResults
      .filter((r) => r.student_id)
      .map((r) => ({ student_id: r.student_id, status: "present", confidence: 0.9 }));
    if (results.length === 0) {
      setCameraError("Select at least one student to mark present");
      return;
    }
    setProcessing(true);
    setResult(null);
    setCameraError("");
    try {
      const res = await captureAttendance({
        subject_id: Number(subjectId),
        date,
        student_results: results,
      });
      setResult(res);
      setMode("records");
      if (subjectId) await fetchAttendance(subjectId, date);
    } catch (err) {
      setCameraError(err?.response?.data?.detail || err.message || "Failed to save attendance");
    } finally {
      setProcessing(false);
    }
  };

  const toggleManual = (id) => {
    setManualResults((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this attendance record?")) return;
    try {
      await removeAttendance(id);
    } catch (err) {
      setCameraError(err?.response?.data?.detail || err.message || "Failed to delete record");
    }
  };

  const studentName = (sid) => {
    const s = students.find((x) => x.id === sid);
    return s ? s.name : `Student #${sid}`;
  };

  const subjectName = () => {
    const s = subjects.find((x) => String(x.id) === String(subjectId));
    return s ? `${s.name} (${s.code})` : "";
  };

  return (
    <div style={{ padding: "2rem" }}>
      <h2 style={{ marginTop: 0 }}>
        {student_id ? `Attendance for Student #${student_id}` : "Mark Attendance"}
      </h2>

      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1rem" }}>
        <select
          value={subjectId}
          onChange={(e) => setSubjectId(e.target.value)}
          style={selectStyle}
          disabled={!!student_id}
        >
          <option value="">Select subject</option>
          {subjects.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} ({s.code})
            </option>
          ))}
        </select>
        <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={selectStyle} />
        <div style={{ display: "flex", gap: "0.25rem" }}>
          <button style={{ ...modeBtn("camera", mode), ...tabBtn }} onClick={() => setMode("camera")}>Camera</button>
          <button style={{ ...modeBtn("manual", mode), ...tabBtn }} onClick={() => setMode("manual")}>Manual</button>
          <button style={{ ...modeBtn("records", mode), ...tabBtn }} onClick={() => setMode("records")}>Records</button>
        </div>
      </div>

      {mode === "camera" && (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem" }}>
          <div>
            <CameraPreview onImageCaptured={setCapturedImage} onError={(e) => setCameraError(e.message || "Camera error")} />
            {capturedImage && (
              <button onClick={handleCapture} disabled={processing} style={{ ...buttonStyle(COLORS.success), marginTop: "0.75rem", width: "100%" }}>
                {processing ? "Processing..." : "✅ Mark Attendance"}
              </button>
            )}
          </div>
          <div>
            <h3 style={{ marginTop: 0 }}>How it works</h3>
            <ol style={{ lineHeight: 1.8, color: "#444" }}>
              <li>Select the subject and date.</li>
              <li>Allow camera access and point it at the classroom.</li>
              <li>Press <strong>📸 Capture</strong>.</li>
              <li>Press <strong>Mark Attendance</strong> to run face recognition.</li>
            </ol>
            <p style={{ fontSize: "0.85rem", color: "#777" }}>
              Demo mode uses simulated face recognition. Seeded students (Alice Johnson, Bob Smith, ...) are recognized automatically.
            </p>
          </div>
        </div>
      )}

      {mode === "manual" && (
        <div>
          <h3>Mark Present Manually</h3>
          <p>Select the students who are present today (for {subjectName() || "selected subject"}).</p>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "0.5rem" }}>
            {students.map((s) => (
              <label key={s.id} style={{ display: "flex", alignItems: "center", gap: "0.5rem", background: "#f8f9fa", padding: "0.5rem", borderRadius: "6px", cursor: "pointer" }}>
                <input type="checkbox" checked={manualResults.includes(s.id)} onChange={() => toggleManual(s.id)} />
                <span>
                  <strong>{s.name}</strong>
                  <br />
                  <small>{s.student_id} • {s.branch}</small>
                </span>
              </label>
            ))}
          </div>
          <button onClick={submitManual} disabled={processing} style={{ ...buttonStyle(COLORS.success), marginTop: "1rem" }}>
            {processing ? "Saving..." : "Save Attendance"}
          </button>
        </div>
      )}

      {(mode === "records" || result) && (
        <div style={{ marginTop: "1rem" }}>
          <h3>Attendance Records {subjectName() && <span style={{ color: "#666", fontSize: "0.85rem" }}>— {subjectName()}</span>}</h3>

          {result && (
            <div style={{ display: "flex", gap: "1rem", padding: "1rem", background: "#e8f5e9", borderRadius: "8px", marginBottom: "1rem" }}>
              <div><strong>Present:</strong> {result.present}</div>
              <div><strong>Absent:</strong> {result.absent}</div>
              <div><strong>Unknown:</strong> {result.unknown}</div>
            </div>
          )}

          {loading ? (
            <p>Loading records...</p>
          ) : error ? (
            <p style={{ color: "#c53030" }}>{error}</p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ background: COLORS.primary, color: "white" }}>
                  <th style={thStyle}>Student</th>
                  <th style={thStyle}>Date</th>
                  <th style={thStyle}>Status</th>
                  <th style={thStyle}>Confidence</th>
                  <th style={thStyle}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {attendance.map((r) => (
                  <tr key={r.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                    <td style={tdStyle}>{studentName(r.student_id)}</td>
                    <td style={tdStyle}>{r.date}</td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={tdStyle}>{r.confidence != null ? r.confidence.toFixed(2) : "—"}</td>
                    <td style={tdStyle}>
                      <button
                        onClick={() => handleDelete(r.id)}
                        style={{ ...buttonStyle("#e74c3c"), fontSize: "0.75rem", padding: "0.25rem 0.5rem" }}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
                {attendance.length === 0 && (
                  <tr>
                    <td colSpan={5} style={{ ...tdStyle, textAlign: "center", color: "#6c757d" }}>
                      No records yet. Capture attendance to see results.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      )}

      {cameraError && (
        <div style={{ marginTop: "1rem", padding: "0.75rem", background: "#fee2e2", color: "#c53030", borderRadius: "6px" }}>
          {cameraError}
        </div>
      )}
    </div>
  );
}

const buttonStyle = (bg) => ({
  background: bg,
  color: "white",
  border: "none",
  padding: "0.6rem 1rem",
  borderRadius: "6px",
  cursor: "pointer",
  fontSize: "0.85rem",
});

const tabBtn = { fontSize: "0.8rem", padding: "0.4rem 0.9rem", borderRadius: "6px", border: "1px solid #ced4da", background: "white", cursor: "pointer" };
const modeBtn = (m, cur) =>
  m === cur ? { background: "#1a1a2e", color: "white", border: "1px solid #1a1a2e" } : { color: "#333" };

const selectStyle = { padding: "0.5rem", borderRadius: "4px", border: "1px solid #ced4da" };
const thStyle = { padding: "0.6rem", textAlign: "left" };
const tdStyle = { padding: "0.6rem", borderBottom: "1px solid #dee2e6" };