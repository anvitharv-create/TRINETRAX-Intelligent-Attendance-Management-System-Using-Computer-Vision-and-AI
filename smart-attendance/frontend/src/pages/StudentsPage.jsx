import React, { useState, useEffect } from "react";
import { useStudents } from "../services/studentsService";
import { studentsApi, recognitionApi, facesApi } from "../services/api";
import FaceRegistration from "../components/FaceRegistration";
import { COLORS, buttonStyle, smallButton, inputStyle, selectStyle } from "../theme";
import { useAuth } from "../services/authContext";

const emptyForm = {
  student_id: "",
  name: "",
  roll_number: "",
  branch: "ISE",
  semester: "1st Semester",
  section: "Section A",
  email: "",
};

const BULK_STATUS_COLORS = {
  ready: "#1b5e20",
  already_registered: "#0277bd",
  missing_photo: "#e65100",
  not_in_db: "#b71c1c",
  invalid: "#b71c1c",
  blocked: "#b71c1c",
  unvalidated: "#e65100",
};

const BULK_STATUS_LABELS = {
  ready: "Ready",
  already_registered: "Already registered",
  missing_photo: "No photo",
  not_in_db: "Not in DB",
  invalid: "Invalid image",
  blocked: "Backend unavailable",
  unvalidated: "Not validated",
};

export default function StudentsPage() {
  const { user } = useAuth();
  const { students, loading, error, fetchStudents, addStudent, removeStudent } = useStudents();
  const myBranch = user?.role === "hod" || user?.role === "teacher" ? user.branch : "";
  const [form, setForm] = useState({ ...emptyForm, branch: myBranch || "ISE" });
  const [showForm, setShowForm] = useState(false);
  const [message, setMessage] = useState("");
  const [filter, setFilter] = useState({ branch: myBranch, semester: "", section: "" });
  const [registering, setRegistering] = useState(null);
  const [faceStatus, setFaceStatus] = useState(null);
  const [editing, setEditing] = useState(null);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [rosterFile, setRosterFile] = useState(null);
  const [photosFile, setPhotosFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [commitResult, setCommitResult] = useState(null);
  const [commitBusy, setCommitBusy] = useState(false);
  const [commitError, setCommitError] = useState("");
  const [overwrite, setOverwrite] = useState(false);

  useEffect(() => { recognitionApi.status().then(setFaceStatus).catch(() => {}); }, []);

  const refreshFaces = () => {
    fetchStudents(filter.branch || null, filter.semester || null, filter.section || null);
    recognitionApi.status().then(setFaceStatus).catch(() => {});
  };

  const runPreview = async () => {
    if (!rosterFile || !photosFile) return;
    setPreviewBusy(true);
    setPreviewError("");
    setPreview(null);
    setCommitResult(null);
    setCommitError("");
    try {
      const res = await facesApi.preview(rosterFile, photosFile);
      setPreview(res);
    } catch (err) {
      setPreviewError(err?.response?.data?.detail || err.message || "Validation failed");
    } finally {
      setPreviewBusy(false);
    }
  };

  const runCommit = async () => {
    if (!rosterFile || !photosFile) return;
    setCommitBusy(true);
    setCommitError("");
    setCommitResult(null);
    try {
      const res = await facesApi.commit(rosterFile, photosFile, overwrite);
      setCommitResult(res);
      refreshFaces();
    } catch (err) {
      setCommitError(err?.response?.data?.detail || err.message || "Import failed");
    } finally {
      setCommitBusy(false);
    }
  };

  const bulkSummary = preview?.summary || {};
  const pendingCount =
    (bulkSummary.ready || 0) +
    (overwrite ? bulkSummary.already_registered || 0 : 0);


  const handleFilter = (key) => (e) => {
    const value = e.target.value;
    const next = { ...filter, [key]: value };
    setFilter(next);
    fetchStudents(next.branch || null, next.semester || null, next.section || null);
  };

  const branchOptions = myBranch ? [myBranch] : ["ISE", "CSE", "ECE", "AIML", "CIVIL"];

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setMessage("");
    try {
      await addStudent(form);
      setForm(emptyForm);
      setShowForm(false);
      setMessage("Student added successfully");
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to add student");
    }
  };

  const handleEdit = async (e) => {
    e.preventDefault();
    setMessage("");
    try {
      await studentsApi.update(editing.id, {
        name: editing.name,
        email: editing.email,
        roll_number: editing.roll_number,
        branch: editing.branch,
        semester: editing.semester,
        section: editing.section,
        password: editing.password || undefined,
      });
      setEditing(null);
      setMessage("Student updated successfully");
      fetchStudents(filter.branch || null, filter.semester || null, filter.section || null);
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to update student");
    }
  };

  const startEdit = (s) =>
    setEditing({
      id: s.id,
      name: s.name,
      email: s.email,
      roll_number: s.roll_number,
      branch: s.branch,
      semester: s.semester,
      section: s.section,
      password: "",
    });

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this student and all their attendance records?")) return;
    try {
      await removeStudent(id);
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to delete student");
    }
  };

  return (
    <div style={{ padding: "2rem", maxWidth: 1200, margin: "0 auto", width: "100%" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
        <div>
          <h2 style={{ margin: 0 }}>Students</h2>
          {faceStatus && (
            <p style={{ margin: "0.25rem 0 0", fontSize: "0.8rem", color: faceStatus.real ? COLORS.success : COLORS.warning }}>
              {faceStatus.real ? `✅ Face recognition active — ${faceStatus.registered} registered face(s). Click "Register Face" to add a student's photo.` : "⚠️ Real recognition backend unavailable — results simulated."}
            </p>
          )}
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button onClick={() => setBulkOpen(!bulkOpen)} style={buttonStyle(COLORS.primary)}>
            {bulkOpen ? "Close Bulk Import" : "⬇ Bulk Face Import"}
          </button>
          <button onClick={() => setShowForm(!showForm)} style={buttonStyle(COLORS.accent)}>
            {showForm ? "Close" : "+ Add Student"}
          </button>
        </div>
      </div>

      {message && (
        <div style={{ padding: "0.75rem", background: "#e8f5e9", color: "#1b5e20", borderRadius: "6px", marginBottom: "1rem" }}>
          {message}
        </div>
      )}

      {bulkOpen && (
        <div style={{ background: "#fff", border: "1px solid #dbe4f0", borderRadius: "8px", padding: "1rem", marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>Bulk Face Registration</h3>
          <p style={{ fontSize: "0.82rem", color: "#556", marginTop: 0 }}>
            Upload an Excel roster (<strong>.xlsx</strong> with a Student ID / USN column) and a{" "}
            <strong>ZIP</strong> of photos named like <code>1AR23IS006.jpg</code>. Nothing is saved until
            you press Import — existing face registrations are never overwritten unless you opt in below.
          </p>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", alignItems: "center" }}>
            <label style={{ fontSize: "0.8rem", fontWeight: 600 }}>
              Excel roster:{" "}
              <input
                type="file"
                accept=".xlsx,.xlsm"
                onChange={(e) => { setRosterFile(e.target.files[0] || null); setPreview(null); setCommitResult(null); }}
              />
            </label>
            <label style={{ fontSize: "0.8rem", fontWeight: 600 }}>
              Photos ZIP:{" "}
              <input
                type="file"
                accept=".zip"
                onChange={(e) => { setPhotosFile(e.target.files[0] || null); setPreview(null); setCommitResult(null); }}
              />
            </label>
            <button
              onClick={runPreview}
              disabled={!rosterFile || !photosFile || previewBusy}
              style={buttonStyle(COLORS.info)}
            >
              {previewBusy ? "Validating..." : "1. Validate"}
            </button>
            <label style={{ fontSize: "0.78rem", display: "flex", gap: "0.35rem", alignItems: "center" }}>
              <input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} />
              Overwrite existing registrations
            </label>
            <button
              onClick={runCommit}
              disabled={!preview || pendingCount === 0 || commitBusy}
              style={buttonStyle(COLORS.success)}
            >
              {commitBusy ? "Importing..." : `2. Import ${pendingCount} face(s)`}
            </button>
          </div>

          {faceStatus && !faceStatus.real && (
            <div style={{ marginTop: "0.75rem", padding: "0.6rem", background: "#fff3e0", color: "#e65100", borderRadius: "6px", fontSize: "0.8rem" }}>
              ⚠️ Recognition backend unavailable — validation and import are blocked until OpenCV + models are present.
            </div>
          )}
          {previewError && (
            <div style={{ marginTop: "0.75rem", padding: "0.6rem", background: "#ffebee", color: "#b71c1c", borderRadius: "6px", fontSize: "0.8rem" }}>
              {previewError}
            </div>
          )}
          {commitError && (
            <div style={{ marginTop: "0.75rem", padding: "0.6rem", background: "#ffebee", color: "#b71c1c", borderRadius: "6px", fontSize: "0.8rem" }}>
              {commitError}
            </div>
          )}

          {preview && (
            <div style={{ marginTop: "1rem" }}>
              <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "0.6rem" }}>
                {Object.entries(preview.summary || {})
                  .filter(([k, v]) => typeof v === "number" && !["roster_rows", "photos_matched", "photos_unmatched", "roster_errors"].includes(k))
                  .map(([k, v]) => (
                    <span
                      key={k}
                      style={{
                        fontSize: "0.72rem", fontWeight: 700, padding: "0.2rem 0.55rem",
                        borderRadius: "999px", color: "#fff",
                        background: BULK_STATUS_COLORS[k] || "#607d8b",
                      }}
                    >
                      {BULK_STATUS_LABELS[k] || k}: {v}
                    </span>
                  ))}
                <span style={{ fontSize: "0.72rem", padding: "0.2rem 0.55rem", borderRadius: "999px", background: "#eceff1", color: "#37474f" }}>
                  Roster rows: {preview.summary?.roster_rows ?? 0} · Matched photos: {preview.summary?.photos_matched ?? 0} · Unmatched photos: {preview.summary?.photos_unmatched ?? 0}
                </span>
              </div>

              {(preview.roster?.errors?.length > 0 || preview.photos?.errors?.length > 0) && (
                <div style={{ fontSize: "0.75rem", color: "#b71c1c", marginBottom: "0.5rem" }}>
                  {[...preview.roster.errors.map((e) => `Row ${e.row}: ${e.error}`),
                    ...preview.photos.errors.map((e) => `${e.file}: ${e.error}`)]
                    .slice(0, 8).map((line, i) => <div key={i}>• {line}</div>)}
                </div>
              )}

              <div style={{ maxHeight: 320, overflowY: "auto", border: "1px solid #e3e8ef", borderRadius: "6px" }}>
                <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.78rem" }}>
                  <thead>
                    <tr style={{ background: COLORS.primary, color: "#fff" }}>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}></th>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}>USN</th>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}>Name</th>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}>Photo</th>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}>Status</th>
                      <th style={{ padding: "0.4rem", textAlign: "left" }}>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.items.map((it) => (
                      <tr key={it.student_id} style={{ borderBottom: "1px solid #eef1f5" }}>
                        <td style={{ padding: "0.3rem" }}>
                          {it.thumbnail ? (
                            <img src={it.thumbnail} alt="" style={{ width: 40, height: 40, objectFit: "cover", borderRadius: 4 }} />
                          ) : null}
                        </td>
                        <td style={{ padding: "0.3rem", fontFamily: "monospace" }}>{it.student_id}</td>
                        <td style={{ padding: "0.3rem" }}>{it.name || "—"}</td>
                        <td style={{ padding: "0.3rem" }}>{it.filename || "—"}</td>
                        <td style={{ padding: "0.3rem", fontWeight: 700, color: BULK_STATUS_COLORS[it.status] || "#444" }}>
                          {BULK_STATUS_LABELS[it.status] || it.status}
                        </td>
                        <td style={{ padding: "0.3rem", color: "#667" }}>
                          {it.reason || (it.faces ? `${it.faces} face(s) detected` : "")}
                          {it.extra_photos?.length > 0 ? ` · +${it.extra_photos.length} extra photo(s)` : ""}
                        </td>
                      </tr>
                    ))}
                    {preview.items.length === 0 && (
                      <tr><td colSpan={6} style={{ padding: "0.6rem", textAlign: "center", color: "#789" }}>No roster rows found</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
              {preview.match?.unmatched?.length > 0 && (
                <div style={{ fontSize: "0.73rem", color: "#e65100", marginTop: "0.4rem" }}>
                  Unmatched photos: {preview.match.unmatched.join(", ")}
                </div>
              )}
            </div>
          )}

          {commitResult && (
            <div style={{ marginTop: "1rem", padding: "0.75rem", borderRadius: "6px", background: "#f1f8e9", border: "1px solid #c5e1a5" }}>
              <strong>
                ✅ Imported {commitResult.summary.imported} · Skipped {commitResult.summary.skipped} · Failed {commitResult.summary.failed}
              </strong>
              <div style={{ fontSize: "0.75rem", color: "#556", marginTop: "0.25rem" }}>
                Total registered faces now: {commitResult.registered_total}
              </div>
              {commitResult.failed.length > 0 && (
                <div style={{ fontSize: "0.75rem", color: "#b71c1c", marginTop: "0.4rem" }}>
                  {commitResult.failed.slice(0, 10).map((f, i) => (
                    <div key={i}>• {f.student_id}: {f.reason}</div>
                  ))}
                </div>
              )}
              {commitResult.skipped.length > 0 && (
                <div style={{ fontSize: "0.75rem", color: "#e65100", marginTop: "0.3rem" }}>
                  {commitResult.skipped.slice(0, 10).map((s, i) => (
                    <div key={i}>• {s.student_id}: {s.reason}</div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
        {[
          ["branch", "Branch", branchOptions],
          ["semester", "Semester", ["1st Semester", "3rd Semester", "5th Semester", "7th Semester"]],
          ["section", "Section", ["Section A", "Section B"]],
        ].map(([key, label, options]) => (
          <select key={key} onChange={handleFilter(key)} value={filter[key]} style={selectStyle}>
            <option value="">All {label}s</option>
            {options.map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </select>
        ))}
      </div>

      {editing && (
        <form onSubmit={handleEdit} style={{ background: "#fff8e1", padding: "1rem", borderRadius: "8px", marginBottom: "1rem", border: "1px solid #f1c48b" }}>
          <h3 style={{ marginTop: 0 }}>Edit Student — {editing.name}</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.5rem" }}>
            {[["name", "Full Name"], ["email", "Email"], ["roll_number", "Roll Number"], ["password", "New Password (optional)"]].map(([k, label]) => (
              <input
                key={k}
                value={editing[k] || ""}
                placeholder={label}
                onChange={(e) => setEditing({ ...editing, [k]: e.target.value })}
                style={inputStyle}
              />
            ))}
            {[
              ["branch", branchOptions],
              ["semester", ["1st Semester", "3rd Semester", "5th Semester", "7th Semester"]],
              ["section", ["Section A", "Section B"]],
            ].map(([k, options]) => (
              <select key={k} value={editing[k] || ""} onChange={(e) => setEditing({ ...editing, [k]: e.target.value })} style={inputStyle}>
                {options.map((o) => (
                  <option key={o} value={o}>{o}</option>
                ))}
              </select>
            ))}
          </div>
          <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.75rem" }}>
            <button type="submit" style={{ ...buttonStyle(COLORS.success) }}>Save Changes</button>
            <button type="button" onClick={() => setEditing(null)} style={buttonStyle("#95a5a6")}>Cancel</button>
          </div>
        </form>
      )}

      {showForm && (
        <form onSubmit={handleSubmit} style={{ background: COLORS.light, padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>Add New Student</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "0.5rem" }}>
            {[
              ["student_id", "Student ID"],
              ["name", "Full Name"],
              ["roll_number", "Roll Number"],
              ["email", "Email"],
            ].map(([name, label]) => (
              <input
                key={name}
                name={name}
                placeholder={label}
                value={form[name]}
                onChange={handleChange}
                required
                style={inputStyle}
              />
            ))}
            {[
              ["branch", branchOptions],
              ["semester", ["1st Semester", "3rd Semester", "5th Semester", "7th Semester"]],
              ["section", ["Section A", "Section B"]],
            ].map(([name, options]) => (
              <select key={name} name={name} value={form[name]} onChange={handleChange} style={inputStyle}>
                {options.map((o) => (
                  <option key={o} value={o}>{o}</option>
                ))}
              </select>
            ))}
          </div>
          <button type="submit" style={{ ...buttonStyle(COLORS.accent), marginTop: "0.75rem" }}>
            Save Student
          </button>
        </form>
      )}

      {loading ? (
        <p>Loading students...</p>
      ) : error ? (
        <p style={{ color: "#c53030" }}>{error}</p>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: COLORS.primary, color: "white" }}>
                <th style={thStyle}>ID</th>
                <th style={thStyle}>Name</th>
                <th style={thStyle}>Email</th>
                <th style={thStyle}>Roll No</th>
                <th style={thStyle}>Branch</th>
                <th style={thStyle}>Semester</th>
                <th style={thStyle}>Section</th>
                <th style={thStyle}>Face</th>
                <th style={thStyle}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {students.map((s) => (
                <tr key={s.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={tdStyle}>{s.student_id}</td>
                  <td style={tdStyle}>{s.name}</td>
                  <td style={{ ...tdStyle, fontSize: "0.8rem", color: s.email ? "#333" : "#999" }}>
                    {s.email || "No email"}
                  </td>
                  <td style={tdStyle}>{s.roll_number}</td>
                  <td style={tdStyle}>{s.branch}</td>
                  <td style={tdStyle}>{s.semester}</td>
                  <td style={tdStyle}>{s.section}</td>
                  <td style={tdStyle}>
                    <span style={{ fontSize: "0.75rem", color: s.face_embedding ? COLORS.success : COLORS.muted }}>
                      {s.face_embedding ? "✓ Registered" : "Not registered"}
                    </span>
                  </td>
                  <td style={tdStyle}>
                    <div style={{ display: "flex", gap: "0.3rem", flexWrap: "wrap" }}>
                      <button onClick={() => startEdit(s)} style={{ ...smallButton(COLORS.info) }}>✏️ Edit</button>
                      <button onClick={() => setRegistering(s)} style={{ ...smallButton(COLORS.info) }}>📷 Register Face</button>
                      <button
                        onClick={() => handleDelete(s.id)}
                        style={{ ...smallButton(COLORS.danger) }}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {students.length === 0 && (
                <tr>
                  <td colSpan={9} style={{ ...tdStyle, textAlign: "center", color: "#6c757d" }}>
                    No students found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {registering && (
        <FaceRegistration
          studentId={registering.student_id}
          name={registering.name}
          onDone={() => { setRegistering(null); fetchStudents(filter.branch || null, filter.semester || null, filter.section || null); recognitionApi.status().then(setFaceStatus); }}
          onClose={() => setRegistering(null)}
        />
      )}
    </div>
  );
}

const thStyle = { padding: "0.6rem", textAlign: "left" };
const tdStyle = { padding: "0.6rem", borderBottom: "1px solid #dee2e6" };