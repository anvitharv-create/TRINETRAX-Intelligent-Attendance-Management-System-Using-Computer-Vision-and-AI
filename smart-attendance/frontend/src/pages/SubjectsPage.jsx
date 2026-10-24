import React, { useState } from "react";
import { useSubjects } from "../services/subjectsService";
import { useTeachers } from "../services/teachersService";
import { useAuth } from "../services/authContext";

const COLORS = {
  primary: "#1a1a2e",
  accent: "#e94f37",
  light: "#f8f9fa",
};

const emptyForm = {
  name: "",
  code: "",
  branch: "ISE",
  semester: "1st Semester",
  teacher_id: "",
};

export default function SubjectsPage() {
  const { user } = useAuth();
  const { subjects, loading, error, fetchSubjects, addSubject, removeSubject } = useSubjects();
  const { teachers } = useTeachers();
  const myBranch = user?.role === "hod" || user?.role === "teacher" ? user.branch : "";
  const branchOptions = myBranch ? [myBranch] : ["ISE", "CSE", "ECE", "AIML", "CIVIL"];
  const [form, setForm] = useState({ ...emptyForm, branch: myBranch || "ISE" });
  const [showForm, setShowForm] = useState(false);
  const [message, setMessage] = useState("");
  const [branch, setBranch] = useState(myBranch || "ISE");
  const [semester, setSemester] = useState("1st Semester");

  const handleBranch = (e) => {
    const value = e.target.value;
    setBranch(value);
    fetchSubjects(value, semester);
  };

  const handleSemester = (e) => {
    const value = e.target.value;
    setSemester(value);
    fetchSubjects(branch, value);
  };

  const handleChange = (e) =>
    setForm({ ...form, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setMessage("");
    try {
      await addSubject({
        ...form,
        teacher_id: form.teacher_id ? Number(form.teacher_id) : null,
      });
      setForm(emptyForm);
      setShowForm(false);
      setMessage("Subject added successfully");
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to add subject");
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this subject and its attendance records?")) return;
    try {
      await removeSubject(id);
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to delete subject");
    }
  };

  return (
    <div style={{ padding: "2rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
        <h2 style={{ margin: 0 }}>Subjects</h2>
        <button onClick={() => setShowForm(!showForm)} style={buttonStyle(COLORS.accent)}>
          {showForm ? "Close" : "+ Add Subject"}
        </button>
      </div>

      {message && (
        <div style={{ padding: "0.75rem", background: "#e8f5e9", color: "#1b5e20", borderRadius: "6px", marginBottom: "1rem" }}>
          {message}
        </div>
      )}

      <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem" }}>
        <select onChange={handleBranch} value={branch} style={selectStyle}>
          {branchOptions.map((b) => (
            <option key={b} value={b}>{b}</option>
          ))}
        </select>
        <select onChange={handleSemester} value={semester} style={selectStyle}>
          {["1st Semester", "3rd Semester", "5th Semester", "7th Semester"].map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      {showForm && (
        <form onSubmit={handleSubmit} style={{ background: COLORS.light, padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>Add New Subject</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "0.5rem" }}>
            {[
              ["name", "Subject Name"],
              ["code", "Subject Code"],
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
            ].map(([name, options]) => (
              <select key={name} name={name} value={form[name]} onChange={handleChange} style={inputStyle}>
                {options.map((o) => (
                  <option key={o} value={o}>{o}</option>
                ))}
              </select>
            ))}
            <select name="teacher_id" value={form.teacher_id} onChange={handleChange} style={inputStyle}>
              <option value="">No teacher assigned</option>
              {teachers.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
          </div>
          <button type="submit" style={{ ...buttonStyle(COLORS.accent), marginTop: "0.75rem" }}>
            Save Subject
          </button>
        </form>
      )}

      {loading ? (
        <p>Loading subjects...</p>
      ) : error ? (
        <p style={{ color: "#c53030" }}>{error}</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: COLORS.primary, color: "white" }}>
              <th style={thStyle}>Code</th>
              <th style={thStyle}>Name</th>
              <th style={thStyle}>Branch</th>
              <th style={thStyle}>Semester</th>
              <th style={thStyle}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {subjects.map((s) => (
              <tr key={s.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={tdStyle}>{s.code}</td>
                <td style={tdStyle}>{s.name}</td>
                <td style={tdStyle}>{s.branch}</td>
                <td style={tdStyle}>{s.semester}</td>
                <td style={tdStyle}>
                  <button
                    onClick={() => handleDelete(s.id)}
                    style={{ ...buttonStyle("#e74c3c"), fontSize: "0.75rem", padding: "0.25rem 0.5rem" }}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {subjects.length === 0 && (
              <tr>
                <td colSpan={5} style={{ ...tdStyle, textAlign: "center", color: "#6c757d" }}>
                  No subjects found
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}

const buttonStyle = (bg) => ({
  background: bg,
  color: "white",
  border: "none",
  padding: "0.5rem 1rem",
  borderRadius: "6px",
  cursor: "pointer",
  fontSize: "0.85rem",
});

const inputStyle = { padding: "0.5rem", borderRadius: "4px", border: "1px solid #ced4da" };
const selectStyle = { padding: "0.5rem", borderRadius: "4px", border: "1px solid #ced4da" };
const thStyle = { padding: "0.6rem", textAlign: "left" };
const tdStyle = { padding: "0.6rem", borderBottom: "1px solid #dee2e6" };