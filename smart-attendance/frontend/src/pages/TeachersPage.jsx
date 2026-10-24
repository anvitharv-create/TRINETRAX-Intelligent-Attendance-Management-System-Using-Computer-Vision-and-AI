import React, { useState, useEffect } from "react";
import { useTeachers } from "../services/teachersService";
import { teachersApi } from "../services/api";
import { useAuth } from "../services/authContext";

const COLORS = {
  primary: "#1a1a2e",
  accent: "#e94f37",
  light: "#f8f9fa",
};

const emptyForm = {
  employee_id: "",
  name: "",
  email: "",
  branch: "ISE",
  semester: "1st Semester",
  section: "Section A",
};

export default function TeachersPage() {
  const { user } = useAuth();
  const { teachers, loading, error, fetchTeachers, addTeacher, removeTeacher } = useTeachers();
  const myBranch = user?.role === "hod" || user?.role === "teacher" ? user.branch : "";
  const [form, setForm] = useState({ ...emptyForm, branch: myBranch || "ISE" });
  const [showForm, setShowForm] = useState(false);
  const [message, setMessage] = useState("");
  const [branchFilter, setBranchFilter] = useState(myBranch);
  const [editing, setEditing] = useState(null);

  const branchOptions = myBranch ? [myBranch] : ["ISE", "CSE", "ECE", "AIML", "CIVIL"];

  useEffect(() => {
    if (myBranch) fetchTeachers(myBranch);
  }, [myBranch, fetchTeachers]);

  const handleFilter = (e) => {
    const value = e.target.value;
    setBranchFilter(value);
    fetchTeachers(value || null);
  };

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setMessage("");
    try {
      await addTeacher(form);
      setForm(emptyForm);
      setShowForm(false);
      setMessage("Teacher added successfully");
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to add teacher");
    }
  };

  const startEdit = (t) =>
    setEditing({
      id: t.id,
      name: t.name,
      email: t.email,
      branch: t.branch,
      semester: t.semester || "",
      section: t.section || "",
      password: "",
    });

  const handleEdit = async (e) => {
    e.preventDefault();
    setMessage("");
    try {
      await teachersApi.update(editing.id, {
        name: editing.name,
        email: editing.email,
        branch: editing.branch,
        semester: editing.semester || null,
        section: editing.section || null,
        password: editing.password || undefined,
      });
      setEditing(null);
      setMessage("Teacher updated successfully");
      fetchTeachers(branchFilter || null);
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to update teacher");
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this teacher?")) return;
    try {
      await removeTeacher(id);
    } catch (err) {
      setMessage(err?.response?.data?.detail || err.message || "Failed to delete teacher");
    }
  };

  return (
    <div style={{ padding: "2rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
        <h2 style={{ margin: 0 }}>Teachers</h2>
        <button onClick={() => setShowForm(!showForm)} style={buttonStyle(COLORS.accent)}>
          {showForm ? "Close" : "+ Add Teacher"}
        </button>
      </div>

      {message && (
        <div style={{ padding: "0.75rem", background: "#e8f5e9", color: "#1b5e20", borderRadius: "6px", marginBottom: "1rem" }}>
          {message}
        </div>
      )}

      <div style={{ marginBottom: "1rem" }}>
        <select onChange={handleFilter} value={branchFilter} style={selectStyle}>
          <option value="">All Branches</option>
          {branchOptions.map((b) => (
            <option key={b} value={b}>{b}</option>
          ))}
        </select>
      </div>

      {editing && (
        <form onSubmit={handleEdit} style={{ background: "#fff8e1", padding: "1rem", borderRadius: "8px", marginBottom: "1rem", border: "1px solid #f1c48b" }}>
          <h3 style={{ marginTop: 0 }}>Edit Teacher — {editing.name}</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.5rem" }}>
            {[["name", "Full Name"], ["email", "Email"], ["password", "New Password (optional)"]].map(([k, label]) => (
              <input key={k} value={editing[k] || ""} placeholder={label} onChange={(e) => setEditing({ ...editing, [k]: e.target.value })} style={inputStyle} />
            ))}
            {[
              ["branch", branchOptions],
              ["semester", ["1st Semester", "3rd Semester", "5th Semester", "7th Semester", ""]],
              ["section", ["Section A", "Section B", ""]],
            ].map(([k, options]) => (
              <select key={k} value={editing[k] || ""} onChange={(e) => setEditing({ ...editing, [k]: e.target.value })} style={inputStyle}>
                <option value="">—</option>
                {options.map((o) => o !== "" && <option key={o} value={o}>{o}</option>)}
              </select>
            ))}
          </div>
          <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.75rem" }}>
            <button type="submit" style={{ ...buttonStyle(COLORS.accent) }}>Save Changes</button>
            <button type="button" onClick={() => setEditing(null)} style={buttonStyle("#95a5a6")}>Cancel</button>
          </div>
        </form>
      )}

      {showForm && (
        <form onSubmit={handleSubmit} style={{ background: COLORS.light, padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>Add New Teacher</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "0.5rem" }}>
            {[
              ["employee_id", "Employee ID"],
              ["name", "Full Name"],
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
            Save Teacher
          </button>
        </form>
      )}

      {loading ? (
        <p>Loading teachers...</p>
      ) : error ? (
        <p style={{ color: "#c53030" }}>{error}</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: COLORS.primary, color: "white" }}>
              <th style={thStyle}>Employee ID</th>
              <th style={thStyle}>Name</th>
              <th style={thStyle}>Email</th>
              <th style={thStyle}>Branch</th>
              <th style={thStyle}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {teachers.map((t) => (
              <tr key={t.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={tdStyle}>{t.employee_id}</td>
                <td style={tdStyle}>{t.name}</td>
                <td style={tdStyle}>{t.email}</td>
                <td style={tdStyle}>{t.branch}</td>
                <td style={tdStyle}>
                  <div style={{ display: "flex", gap: "0.3rem", flexWrap: "wrap" }}>
                    <button onClick={() => startEdit(t)} style={{ ...buttonStyle(COLORS.accent), fontSize: "0.75rem", padding: "0.25rem 0.5rem" }}>
                      ✏️ Edit
                    </button>
                    <button
                      onClick={() => handleDelete(t.id)}
                      style={{ ...buttonStyle("#e74c3c"), fontSize: "0.75rem", padding: "0.25rem 0.5rem" }}
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {teachers.length === 0 && (
              <tr>
                <td colSpan={5} style={{ ...tdStyle, textAlign: "center", color: "#6c757d" }}>
                  No teachers found
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