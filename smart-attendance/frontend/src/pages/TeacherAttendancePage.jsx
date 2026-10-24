import React, { useState, useEffect, useCallback } from "react";
import CameraPreview from "../components/CameraPreview";
import { useStudents } from "../services/studentsService";
import { useSubjects } from "../services/subjectsService";
import { attendanceApi, recognitionApi } from "../services/api";
import {
  COLORS, buttonStyle, smallButton, selectStyle, StatCard, Banner,
  Table, EmptyRow, pageStyle, BRANCHES, SEMESTERS, SECTIONS,
} from "../theme";

const STEPS = [
  { key: "setup", label: "1. Select Class & Subject" },
  { key: "capture", label: "2. Capture Classroom" },
  { key: "review", label: "3. Review AI Results" },
  { key: "done", label: "4. Confirm & Save" },
];

export default function TeacherAttendancePage() {
  const { students, fetchStudents } = useStudents();
  const { subjects, fetchSubjects } = useSubjects();

  const [branch, setBranch] = useState("ISE");
  const [semester, setSemester] = useState("1st Semester");
  const [section, setSection] = useState("Section A");
  const [subjectId, setSubjectId] = useState("");

  const [step, setStep] = useState("setup");
  const [capturedImage, setCapturedImage] = useState(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState(null);
  const [rows, setRows] = useState([]);
  const [editing, setEditing] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState(null);
  const [addId, setAddId] = useState("");
  const [recognitionStatus, setRecognitionStatus] = useState(null);

  const classStudents = students.filter(
    (s) => s.branch === branch && s.semester === semester && s.section === section
  );

  useEffect(() => {
    fetchStudents(branch, semester, section);
  }, [branch, semester, section, fetchStudents]);

  useEffect(() => {
    fetchSubjects(branch, semester);
  }, [branch, semester, fetchSubjects]);

  const loadRecognitionStatus = useCallback(async () => {
    try {
      const st = await recognitionApi.status();
      setRecognitionStatus(st);
    } catch (err) {
      setRecognitionStatus(null);
    }
  }, []);
  useEffect(() => { loadRecognitionStatus(); }, [loadRecognitionStatus]);

  const classStudentsForSubject = useCallback(() => {
    return students.filter(
      (s) => s.branch === branch && s.semester === semester && s.section === section
    );
  }, [students, branch, semester, section]);

  const handleAnalyze = async () => {
    if (!capturedImage) {
      setMessage({ tone: "warning", text: "Please capture a classroom photo first." });
      return;
    }
    setAnalyzing(true);
    setMessage(null);
    try {
      const res = await recognitionApi.analyze(capturedImage, Number(subjectId), section);
      setAnalysis(res);
      const seen = new Set();
      const initialRows = res.results.map((r, i) => {
        const key = `${r.student_id || "unknown"}-${i}`;
        seen.add(key);
        return {
          key,
          student_id: r.student_id,
          db_student_id: r.db_student_id,
          name: r.name,
          roll_number: r.roll_number,
          status: r.status === "present" ? "Present" : "Unknown",
          confidence: r.confidence,
          matched: r.matched,
        };
      });
      setRows(initialRows);
      setStep("review");
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message || "Failed to analyze image." });
    } finally {
      setAnalyzing(false);
    }
  };

  const addMissingStudent = () => {
    if (!addId) return;
    const s = classStudents.find((x) => String(x.id) === String(addId));
    if (!s) return;
    if (rows.some((r) => r.db_student_id === s.id)) return;
    setRows((prev) => [
      ...prev,
      {
        key: `manual-${s.id}`,
        student_id: s.student_id,
        db_student_id: s.id,
        name: s.name,
        roll_number: s.roll_number,
        status: "Present",
        confidence: null,
        matched: true,
      },
    ]);
    setAddId("");
  };

  const setRowStatus = (key, status) =>
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, status } : r)));

  const setRowStudent = (key, studentId) => {
    const s = classStudents.find((x) => String(x.id) === String(studentId));
    setRows((prev) =>
      prev.map((r) =>
        r.key === key ? { ...r, db_student_id: studentId, student_id: s?.student_id || r.student_id, name: s?.name || r.name, roll_number: s?.roll_number || r.roll_number } : r
      )
    );
  };

  const removeRow = (key) => setRows((prev) => prev.filter((r) => r.key !== key));

  const presentCount = rows.filter((r) => r.status === "Present").length;
  const absentCount = rows.filter((r) => r.status === "Absent").length;
  const unknownCount = rows.filter((r) => r.status === "Unknown").length;

  const handleSave = async () => {
    const studentResults = rows
      .filter((r) => r.db_student_id && r.status !== "Unknown")
      .map((r) => ({
        student_id: r.db_student_id,
        status: r.status.toLowerCase(),
        confidence: r.confidence != null ? r.confidence / 100 : 0.9,
      }));
    if (studentResults.length === 0) {
      setMessage({ tone: "warning", text: "No students to save. Assign students to the detected faces or add them manually." });
      return;
    }
    setSaving(true);
    setMessage(null);
    try {
      const date = new Date().toISOString().split("T")[0];
      const res = await attendanceApi.capture({
        subject_id: Number(subjectId),
        date,
        student_results: studentResults,
      });
      setMessage({ tone: "success", text: `✅ Attendance saved. ${res.present} present, ${res.absent} absent, ${res.unknown} unknown.` });
      setStep("done");
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message || "Failed to save attendance." });
    } finally {
      setSaving(false);
    }
  };

  const resetAll = () => {
    setStep("setup");
    setCapturedImage(null);
    setAnalysis(null);
    setRows([]);
    setMessage(null);
  };

  const canProceed = step === "setup" && subjectId;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>AI Attendance Workflow</h2>

      {/* Stepper */}
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "1.5rem", background: "#f8f9fa", padding: "0.75rem", borderRadius: "8px" }}>
        {STEPS.map((s) => {
          const active = step === s.key;
          const done = ["setup", "capture", "review", "done"].indexOf(step) > ["setup", "capture", "review", "done"].indexOf(s.key);
          return (
            <div key={s.key} style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
              <span style={{
                width: 24, height: 24, borderRadius: "50%", display: "inline-flex", alignItems: "center", justifyContent: "center",
                background: active ? COLORS.primary : done ? COLORS.success : "#e0e0e0",
                color: "#fff", fontSize: "0.75rem", fontWeight: 700,
              }}>{done ? "✓" : s.label[0]}</span>
              <span style={{ fontSize: "0.8rem", color: active ? COLORS.primary : "#666", fontWeight: active ? 700 : 400 }}>{s.label}</span>
              {s.key !== "done" && <span style={{ color: "#bbb", margin: "0 0.2rem" }}>→</span>}
            </div>
          );
        })}
      </div>

      {recognitionStatus && (
        <Banner tone={recognitionStatus.real ? "success" : "warning"} style={{ marginBottom: "1rem" }}>
          {recognitionStatus.real
            ? <>✅ Real face recognition active ({recognitionStatus.method}). {recognitionStatus.registered} face(s) registered. Register faces on the <strong>Students</strong> page to improve accuracy.</>
            : <>⚠️ Real recognition backend unavailable — analysis uses simulated results. Install <code>opencv-contrib-python</code> on the backend to enable real face recognition.</>}
        </Banner>
      )}

      {message && <Banner tone={message.tone}>{message.text}</Banner>}

      {/* Step 1: Setup */}
      {step === "setup" && (
        <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "8px", padding: "1.5rem" }}>
          <h3 style={{ marginTop: 0 }}>Select Class &amp; Subject</h3>
          <p style={{ color: "#666", fontSize: "0.85rem", marginTop: 0 }}>
            Follow the drill-down to the exact class you are teaching, then choose the subject for which attendance is being taken.
          </p>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "0.75rem" }}>
            <div>
              <label style={labelStyle}>Branch</label>
              <select value={branch} onChange={(e) => { setBranch(e.target.value); setSubjectId(""); }} style={{ ...selectStyle, width: "100%" }}>
                {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </div>
            <div>
              <label style={labelStyle}>Semester</label>
              <select value={semester} onChange={(e) => { setSemester(e.target.value); setSubjectId(""); }} style={{ ...selectStyle, width: "100%" }}>
                {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div>
              <label style={labelStyle}>Section</label>
              <select value={section} onChange={(e) => setSection(e.target.value)} style={{ ...selectStyle, width: "100%" }}>
                {SECTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div>
              <label style={labelStyle}>Subject</label>
              <select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} style={{ ...selectStyle, width: "100%" }}>
                <option value="">Select subject</option>
                {subjects.map((s) => (
                  <option key={s.id} value={s.id}>{s.name} ({s.code})</option>
                ))}
              </select>
            </div>
          </div>

          {subjectId && (
            <div style={{ marginTop: "1rem", display: "flex", alignItems: "center", gap: "1rem", flexWrap: "wrap" }}>
              <span style={{ fontSize: "0.9rem" }}>
                <strong>{subjects.find((s) => String(s.id) === String(subjectId))?.name}</strong> — {semester} / {branch} / {section}
              </span>
              <span style={{ fontSize: "0.8rem", color: "#888" }}>
                {classStudents.length} students in class
              </span>
            </div>
          )}

          <div style={{ marginTop: "1.5rem" }}>
            <button
              onClick={() => canProceed && setStep("capture")}
              disabled={!canProceed}
              style={{ ...buttonStyle(COLORS.primary) }}
            >
              Next: Capture Classroom →
            </button>
          </div>
        </div>
      )}

      {/* Step 2: Capture */}
      {step === "capture" && (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))", gap: "1.5rem" }}>
          <div>
            <h3>Capture the Classroom</h3>
            <CameraPreview onImageCaptured={setCapturedImage} onError={(e) => setMessage({ tone: "danger", text: e.message || "Camera error" })} />
            <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.75rem", flexWrap: "wrap" }}>
              <button onClick={handleAnalyze} disabled={analyzing || !capturedImage} style={{ ...buttonStyle(COLORS.success) }}>
                {analyzing ? "Analyzing image..." : "🔍 Analyze Image"}
              </button>
              <button onClick={() => setStep("setup")} style={buttonStyle("#95a5a6")}>← Back</button>
            </div>
            <p style={{ fontSize: "0.8rem", color: "#888", marginTop: "0.5rem" }}>
              Tip: Position the camera to capture faces clearly. Good lighting and front-facing angles improve recognition accuracy.
            </p>
          </div>
          <div>
            <h3>Selected Class</h3>
            <div style={{ background: "#f8f9fa", padding: "1rem", borderRadius: "8px" }}>
              <div style={{ fontSize: "0.9rem" }}><strong>{subjects.find((s) => String(s.id) === String(subjectId))?.name || "—"}</strong></div>
              <div style={{ color: "#666", fontSize: "0.85rem" }}>{semester} · {branch} · {section}</div>
            </div>
            <div style={{ marginTop: "1rem" }}>
              <h4 style={{ margin: 0, fontSize: "0.9rem" }}>Students in this class</h4>
              <div style={{ maxHeight: 300, overflowY: "auto", border: "1px solid #eee", borderRadius: "8px", marginTop: "0.5rem" }}>
                <table style={{ width: "100%", borderCollapse: "collapse" }}>
                  <thead>
                    <tr style={{ background: COLORS.primary, color: "#fff" }}>
                      <th style={{ padding: "0.5rem", textAlign: "left", fontSize: "0.75rem" }}>Name</th>
                      <th style={{ padding: "0.5rem", textAlign: "left", fontSize: "0.75rem" }}>Roll</th>
                    </tr>
                  </thead>
                  <tbody>
                    {classStudents.map((s) => (
                      <tr key={s.id} style={{ borderBottom: "1px solid #f0f0f0" }}>
                        <td style={{ padding: "0.4rem 0.5rem", fontSize: "0.8rem" }}>{s.name}</td>
                        <td style={{ padding: "0.4rem 0.5rem", fontSize: "0.8rem", color: "#666" }}>{s.roll_number}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Step 3: Review */}
      {step === "review" && (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "0.5rem", marginBottom: "1rem" }}>
            <div>
              <h3 style={{ margin: 0 }}>Review AI Results</h3>
              <div style={{ color: "#666", fontSize: "0.85rem" }}>
                {subjects.find((s) => String(s.id) === String(subjectId))?.name} · {semester} · {branch} · {section}
              </div>
            </div>
            <div style={{ display: "flex", gap: "0.5rem" }}>
              <button onClick={() => setEditing(!editing)} style={buttonStyle(editing ? COLORS.warning : COLORS.primary)}>
                {editing ? "Lock Editing" : "✏️ Edit Attendance"}
              </button>
              <button onClick={resetAll} style={buttonStyle("#95a5a6")}>Start Over</button>
            </div>
          </div>

          <Banner tone={editing ? "warning" : "info"}>
            {editing
              ? "AI analyzed the classroom photo. Review every row below — correct any misidentifications, mark absent students, and add anyone the camera missed. Nothing is saved yet."
              : "Editing is locked. Press Confirm & Save to write the attendance records to the database."}
          </Banner>

          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", marginBottom: "1rem" }}>
            <StatCard label="Detected Faces" value={rows.length} color={COLORS.info} />
            <StatCard label="Present" value={presentCount} color={COLORS.success} />
            <StatCard label="Absent" value={absentCount} color={COLORS.danger} />
            <StatCard label="Unknown" value={unknownCount} color={COLORS.muted} />
            <StatCard label="Class Size" value={classStudents.length} color={COLORS.primary} />
          </div>

          {analysis?.backend && (
            <Banner tone={analysis.backend.real ? "success" : "warning"} style={{ marginBottom: "0.75rem" }}>
              {analysis.note} (method: {analysis.backend.method}, {analysis.backend.registered} registered face(s)).
            </Banner>
          )}

          <div style={{ overflowX: "auto" }}>
            <Table head={["Student", "Roll No", "Status", "Confidence", editing ? "Actions" : ""]}>
              {rows.length === 0 && <EmptyRow colSpan={5}>No faces were detected in the image. Add students manually below or retake the photo.</EmptyRow>}
              {rows.map((r) => (
                <tr key={r.key} style={{ borderBottom: "1px solid #dee2e6", background: r.status === "Unknown" ? "#fff8f0" : "#fff" }}>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                    {editing && !r.matched ? (
                      <select value={r.db_student_id || ""} onChange={(e) => setRowStudent(r.key, e.target.value)} style={selectStyle}>
                        <option value="">Assign student…</option>
                        {classStudents.map((s) => (
                          <option key={s.id} value={s.id}>{s.name} ({s.roll_number})</option>
                        ))}
                      </select>
                    ) : (
                      <strong>{r.name}</strong>
                    )}
                  </td>
                  <td style={{ padding: "0.6rem", fontSize: "0.8rem", color: "#666" }}>{r.roll_number}</td>
                  <td style={{ padding: "0.6rem" }}>
                    <select
                      value={r.status}
                      onChange={(e) => setRowStatus(r.key, e.target.value)}
                      disabled={!editing}
                      style={selectStyle}
                    >
                      <option>Present</option>
                      <option>Absent</option>
                      <option>Unknown</option>
                    </select>
                  </td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                    {r.confidence != null ? `${r.confidence.toFixed(0)}%` : "—"}
                  </td>
                  <td style={{ padding: "0.6rem" }}>
                    {editing && (
                      <button onClick={() => removeRow(r.key)} style={{ ...smallButton(COLORS.danger) }}>Remove</button>
                    )}
                  </td>
                </tr>
              ))}
            </Table>
          </div>

          {editing && (
            <div style={{ display: "flex", gap: "0.5rem", marginTop: "1rem", alignItems: "center", flexWrap: "wrap", background: "#f8f9fa", padding: "0.75rem", borderRadius: "8px" }}>
              <span style={{ fontSize: "0.85rem" }}>➕ Add student not detected by the camera:</span>
              <select value={addId} onChange={(e) => setAddId(e.target.value)} style={selectStyle}>
                <option value="">Select student…</option>
                {classStudents.map((s) => (
                  <option key={s.id} value={s.id}>{s.name} ({s.roll_number})</option>
                ))}
              </select>
              <button onClick={addMissingStudent} style={smallButton(COLORS.info)}>Add</button>
            </div>
          )}

          <div style={{ display: "flex", gap: "0.75rem", marginTop: "1.5rem", justifyContent: "flex-end" }}>
            <button onClick={() => setStep("capture")} style={buttonStyle("#95a5a6")}>← Retake Photo</button>
            <button onClick={handleSave} disabled={saving} style={buttonStyle(COLORS.success)}>
              {saving ? "Saving..." : "✓ Confirm & Save Attendance"}
            </button>
          </div>
        </div>
      )}

      {/* Step 4: Done */}
      {step === "done" && (
        <div style={{ textAlign: "center", padding: "2rem" }}>
          <div style={{ fontSize: "3rem" }}>🎉</div>
          <h3>Attendance Saved</h3>
          <p style={{ color: "#666" }}>The attendance record has been written to the database.</p>
          <div style={{ display: "flex", gap: "1rem", justifyContent: "center", flexWrap: "wrap", margin: "1rem 0" }}>
            <StatCard label="Present" value={presentCount} color={COLORS.success} />
            <StatCard label="Absent" value={absentCount} color={COLORS.danger} />
            <StatCard label="Unknown" value={unknownCount} color={COLORS.muted} />
          </div>
          <button onClick={resetAll} style={buttonStyle(COLORS.primary)}>Take Another Class</button>
        </div>
      )}
    </div>
  );
}

const labelStyle = { display: "block", fontSize: "0.75rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };