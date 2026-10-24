import React, { useState, useEffect } from "react";
import { adminApi, teachersApi, studentsApi } from "../services/api";
import {
  COLORS, buttonStyle, smallButton, selectStyle, inputStyle, Banner,
  Table, EmptyRow, pageStyle, BRANCHES,
} from "../theme";

const emptyHodForm = { name: "", email: "", branch: "ISE", password: "hod123" };
const emptyTeacherForm = { employee_id: "", name: "", email: "", branch: "ISE" };
const emptyStudentForm = { student_id: "", name: "", roll_number: "", branch: "ISE", semester: "1st Semester", section: "Section A", email: "" };

export default function AdminUsersPage() {
  const [tab, setTab] = useState("hods");
  const [hods, setHods] = useState([]);
  const [teachers, setTeachers] = useState([]);
  const [students, setStudents] = useState([]);
  const [selectedBranch, setSelectedBranch] = useState("");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState(null);

  const [hodForm, setHodForm] = useState(emptyHodForm);
  const [showHodForm, setShowHodForm] = useState(false);
  const [teacherForm, setTeacherForm] = useState(emptyTeacherForm);
  const [showTeacherForm, setShowTeacherForm] = useState(false);
  const [studentForm, setStudentForm] = useState(emptyStudentForm);
  const [showStudentForm, setShowStudentForm] = useState(false);
  const [editingHod, setEditingHod] = useState(null);

  useEffect(() => { adminApi.hods().then(setHods).catch(() => {}); }, []);

  useEffect(() => {
    if (tab === "teachers" || tab === "students") {
      setSelectedBranch("");
      setTeachers([]);
      setStudents([]);
    }
  }, [tab]);

  const loadBranch = async (branch) => {
    setSelectedBranch(branch);
    setLoading(true);
    try {
      if (tab === "teachers") {
        const data = await adminApi.teachersByBranch(branch);
        setTeachers(data);
      } else {
        const data = await adminApi.studentsByBranch(branch);
        setStudents(data);
      }
    } catch (_) {
      setTeachers([]);
      setStudents([]);
    } finally {
      setLoading(false);
    }
  };

  const startEditHod = (h) =>
    setEditingHod({ id: h.id, name: h.full_name, email: h.email, branch: h.branch, password: "" });

  const handleEditHod = async () => {
    try {
      await adminApi.updateHod(editingHod.id, {
        full_name: editingHod.name,
        email: editingHod.email,
        branch: editingHod.branch,
        password: editingHod.password || undefined,
      });
      adminApi.hods().then(setHods);
      setEditingHod(null);
      setMessage({ tone: "success", text: "HOD updated." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const handleAddHod = async () => {
    try {
      const h = await adminApi.createHod(hodForm);
      setHods((prev) => [...prev, h]);
      setShowHodForm(false);
      setHodForm(emptyHodForm);
      setMessage({ tone: "success", text: `HOD ${h.full_name} created.` });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const handleAddTeacher = async () => {
    try {
      await teachersApi.create({ ...teacherForm, semester: "1st Semester", section: "Section A" });
      setShowTeacherForm(false);
      setTeacherForm(emptyTeacherForm);
      if (selectedBranch) loadBranch(selectedBranch);
      setMessage({ tone: "success", text: "Teacher added." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const handleAddStudent = async () => {
    try {
      await studentsApi.create(studentForm);
      setShowStudentForm(false);
      setStudentForm(emptyStudentForm);
      if (selectedBranch) loadBranch(selectedBranch);
      setMessage({ tone: "success", text: "Student added." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const handleRemoveTeacher = async (id) => {
    if (!confirm("Delete this teacher?")) return;
    try {
      await teachersApi.remove(id);
      setTeachers((prev) => prev.filter((t) => t.id !== id));
      setMessage({ tone: "success", text: "Teacher deleted." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const handleRemoveStudent = async (id) => {
    if (!confirm("Delete this student?")) return;
    try {
      await studentsApi.remove(id);
      setStudents((prev) => prev.filter((s) => s.id !== id));
      setMessage({ tone: "success", text: "Student deleted." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    }
  };

  const tabs = [
    ["hods", "HOD Management", "🏛"],
    ["teachers", "Teacher Management", "👨‍🏫"],
    ["students", "Student Management", "👥"],
  ];

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>User Management</h2>
      <p style={{ color: "#666" }}>Manage HODs, teachers, and students across the college.</p>

      {message && <Banner tone={message.tone}>{message.text}</Banner>}

      <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
        {tabs.map(([key, label, icon]) => (
          <button key={key} onClick={() => setTab(key)} style={{ ...smallButton(tab === key ? COLORS.primary : "#e0e0e0"), color: tab === key ? "#fff" : "#333" }}>
            {icon} {label}
          </button>
        ))}
      </div>

      {tab === "hods" && (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.75rem" }}>
            <h3 style={{ margin: 0 }}>HODs</h3>
            <button onClick={() => setShowHodForm(!showHodForm)} style={buttonStyle(COLORS.accent)}>
              {showHodForm ? "Close" : "+ Add HOD"}
            </button>
          </div>
          {editingHod && (
            <div style={{ background: "#fff8e1", padding: "1rem", borderRadius: "8px", marginBottom: "1rem", border: "1px solid #f1c48b" }}>
              <h4 style={{ marginTop: 0 }}>Edit HOD — {editingHod.name}</h4>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.5rem" }}>
                {[["name", "Full Name"], ["email", "Email"], ["password", "New Password (optional)"]].map(([k, label]) => (
                  <input key={k} value={editingHod[k] || ""} placeholder={label} onChange={(e) => setEditingHod({ ...editingHod, [k]: e.target.value })} style={inputStyle} />
                ))}
                <select value={editingHod.branch} onChange={(e) => setEditingHod({ ...editingHod, branch: e.target.value })} style={selectStyle}>
                  {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.75rem" }}>
                <button onClick={handleEditHod} style={buttonStyle(COLORS.success)}>Save Changes</button>
                <button onClick={() => setEditingHod(null)} style={buttonStyle("#95a5a6")}>Cancel</button>
              </div>
            </div>
          )}
          {showHodForm && (
            <div style={{ background: "#f8f9fa", padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))", gap: "0.5rem" }}>
                {[["name", "Full Name"], ["email", "Email"], ["password", "Password"]].map(([k, label]) => (
                  <input key={k} placeholder={label} value={hodForm[k]} onChange={(e) => setHodForm({ ...hodForm, [k]: e.target.value })} style={inputStyle} />
                ))}
                <select value={hodForm.branch} onChange={(e) => setHodForm({ ...hodForm, branch: e.target.value })} style={selectStyle}>
                  {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <button onClick={handleAddHod} style={{ ...buttonStyle(COLORS.success), marginTop: "0.75rem" }}>Create HOD</button>
            </div>
          )}
          <Table head={["HOD", "Email", "Department", "Role", ""]}>
            {hods.map((h) => (
              <tr key={h.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong>{h.full_name}</strong></td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{h.email}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{h.branch}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem", textTransform: "capitalize" }}>{h.role}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                  <button onClick={() => startEditHod(h)} style={smallButton(COLORS.info)}>Edit</button>
                </td>
              </tr>
            ))}
            {hods.length === 0 && <EmptyRow colSpan={5} />}
          </Table>
        </div>
      )}

      {tab === "teachers" && (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.75rem" }}>
            <h3 style={{ margin: 0 }}>Teachers</h3>
            <button onClick={() => setShowTeacherForm(!showTeacherForm)} style={buttonStyle(COLORS.accent)}>
              {showTeacherForm ? "Close" : "+ Add Teacher"}
            </button>
          </div>
          {showTeacherForm && (
            <div style={{ background: "#f8f9fa", padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))", gap: "0.5rem" }}>
                {[["employee_id", "Employee ID"], ["name", "Full Name"], ["email", "Email"]].map(([k, label]) => (
                  <input key={k} placeholder={label} value={teacherForm[k]} onChange={(e) => setTeacherForm({ ...teacherForm, [k]: e.target.value })} style={inputStyle} />
                ))}
                <select value={teacherForm.branch} onChange={(e) => setTeacherForm({ ...teacherForm, branch: e.target.value })} style={selectStyle}>
                  {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <button onClick={handleAddTeacher} style={{ ...buttonStyle(COLORS.success), marginTop: "0.75rem" }}>Add Teacher</button>
            </div>
          )}
          <p style={{ color: "#666", fontSize: "0.85rem" }}>Select a department to view teachers:</p>
          <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
            {BRANCHES.map((b) => (
              <button key={b} onClick={() => loadBranch(b)} style={{ ...smallButton(selectedBranch === b ? COLORS.primary : "#e0e0e0"), color: selectedBranch === b ? "#fff" : "#333" }}>
                {b}
              </button>
            ))}
          </div>
          {loading && <p>Loading...</p>}
          {!loading && selectedBranch && (
            <Table head={["Employee ID", "Name", "Email", "Branch", ""]}>
              {teachers.map((t) => (
                <tr key={t.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{t.employee_id}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong>{t.name}</strong></td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{t.email}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{t.branch}</td>
                  <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                    <button onClick={() => handleRemoveTeacher(t.id)} style={smallButton(COLORS.danger)}>Delete</button>
                  </td>
                </tr>
              ))}
              {teachers.length === 0 && <EmptyRow colSpan={5}>No teachers in {selectedBranch}.</EmptyRow>}
            </Table>
          )}
        </div>
      )}

      {tab === "students" && (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.75rem" }}>
            <h3 style={{ margin: 0 }}>Students</h3>
            <button onClick={() => setShowStudentForm(!showStudentForm)} style={buttonStyle(COLORS.accent)}>
              {showStudentForm ? "Close" : "+ Add Student"}
            </button>
          </div>
          {showStudentForm && (
            <div style={{ background: "#f8f9fa", padding: "1rem", borderRadius: "8px", marginBottom: "1rem" }}>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.5rem" }}>
                {[["student_id", "Student ID"], ["name", "Full Name"], ["roll_number", "Roll No"], ["email", "Email"]].map(([k, label]) => (
                  <input key={k} placeholder={label} value={studentForm[k]} onChange={(e) => setStudentForm({ ...studentForm, [k]: e.target.value })} style={inputStyle} />
                ))}
                <select value={studentForm.branch} onChange={(e) => setStudentForm({ ...studentForm, branch: e.target.value })} style={selectStyle}>
                  {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
              </div>
              <button onClick={handleAddStudent} style={{ ...buttonStyle(COLORS.success), marginTop: "0.75rem" }}>Add Student</button>
            </div>
          )}
          <p style={{ color: "#666", fontSize: "0.85rem" }}>Select a department to view students:</p>
          <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
            {BRANCHES.map((b) => (
              <button key={b} onClick={() => loadBranch(b)} style={{ ...smallButton(selectedBranch === b ? COLORS.primary : "#e0e0e0"), color: selectedBranch === b ? "#fff" : "#333" }}>
                {b}
              </button>
            ))}
          </div>
          {loading && <p>Loading...</p>}
          {!loading && selectedBranch && (
            <div style={{ maxHeight: 500, overflowY: "auto", border: "1px solid #eee", borderRadius: "8px" }}>
              <Table head={["Student ID", "Name", "Email", "Roll No", "Semester", "Section", ""]}>
                {students.map((s) => (
                  <tr key={s.id} style={{ borderBottom: "1px solid #dee2e6" }}>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.student_id}</td>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}><strong>{s.name}</strong></td>
                    <td style={{ padding: "0.6rem", fontSize: "0.8rem", color: s.email ? "#333" : "#999" }}>
                      {s.email || "No email"}
                    </td>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.roll_number}</td>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.semester}</td>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>{s.section}</td>
                    <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                      <button onClick={() => handleRemoveStudent(s.id)} style={smallButton(COLORS.danger)}>Delete</button>
                    </td>
                  </tr>
                ))}
                {students.length === 0 && <EmptyRow colSpan={7}>No students in {selectedBranch}.</EmptyRow>}
              </Table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
