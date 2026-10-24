import React, { useState, useRef, useEffect } from "react";
import { useAuth } from "../services/authContext";
import { authApi } from "../services/api";
import {
  COLORS, buttonStyle, smallButton, inputStyle, selectStyle, Banner,
  BRANCHES, SEMESTERS, SECTIONS, BRANCH_CODES,
} from "../theme";

const HOD_NAMES = {
  ISE: "DR B I D Kumar",
  CSE: "DR Mahantesh Matapathi",
  ECE: "Dr. Veeresh Patil",
  AIML: "Prof. Shreyas Shivananjappa",
  CIVIL: "Mr. M Rudramurthy",
};

const ROLE_CARDS = [
  { key: "admin", label: "Principal", icon: "👑", color: "linear-gradient(135deg, #7c3aed, #4f46e5)", desc: "College Administration" },
  { key: "hod", label: "HOD", icon: "🏛", color: "linear-gradient(135deg, #0ea5e9, #0284c7)", desc: "Head of Department" },
  { key: "teacher", label: "Teacher", icon: "👨‍🏫", color: "linear-gradient(135deg, #10b981, #059669)", desc: "Faculty Member" },
  { key: "student", label: "Student", icon: "🎓", color: "linear-gradient(135deg, #f59e0b, #d97706)", desc: "Enrolled Student" },
];

function field(label) {
  return {
    display: "block",
    fontSize: "0.72rem",
    fontWeight: 600,
    color: "#555",
    marginBottom: "0.25rem",
  };
}

export default function LoginPage() {
  const { login } = useAuth();
  const [role, setRole] = useState(null);
  const [branch, setBranch] = useState("ISE");
  const [phase, setPhase] = useState("pick"); // pick | registered | register | login
  const [registeredUsers, setRegisteredUsers] = useState([]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState(null);
  const [newCreds, setNewCreds] = useState(null);
  const [loading, setLoading] = useState(false);
  const [studentTab, setStudentTab] = useState("login"); // login | register | csv
  const [regForm, setRegForm] = useState({
    name: "", branch: "ISE", semester: "1st Semester", section: "Section A",
  });
  const [csvResult, setCsvResult] = useState(null);
  const fileRef = useRef(null);

  const doLogin = async (u, p) => {
    setMessage(null);
    setLoading(true);
    try {
      const user = await login(u, p);
      window.location.href = `/${user.role}`;
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || "Login failed" });
    } finally {
      setLoading(false);
    }
  };

  const pickRole = (r) => {
    setRole(r);
    setPhase(r === "admin" ? "login" : "pick");
    setBranch("ISE");
    setMessage(null);
    setNewCreds(null);
    setRegisteredUsers([]);
    setCsvResult(null);
    if (r === "admin") setUsername("principal");
  };

  const lookUp = async () => {
    setLoading(true);
    setMessage(null);
    try {
      const useBranch = role === "admin" ? "" : branch;
      const data = await authApi.check(role, useBranch);
      setRegisteredUsers(data.users || []);
      setPhase(data.registered > 0 ? "registered" : "register");
      if (data.registered > 0 && (role === "hod" || role === "admin")) {
        const u = data.users[0];
        setUsername(role === "admin" ? "principal" : (u.username || u.email));
      }
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (kind) => {
    setLoading(true);
    setMessage(null);
    setNewCreds(null);
    try {
      let res;
      if (kind === "hod") res = await authApi.registerHod({ branch });
      else if (kind === "teacher") res = await authApi.registerTeacher({ branch, name: regForm.name });
      else {
        res = await authApi.registerStudent({
          name: regForm.name,
          branch: regForm.branch,
          semester: regForm.semester,
          section: regForm.section,
        });
      }
      setNewCreds(res);
      setUsername(kind === "student" ? res.student_id || res.username : res.username);
      setPhase("register");
      if (kind === "student") setStudentTab("login");
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
    } finally {
      setLoading(false);
    }
  };

  const handleCsv = (file) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async () => {
      const text = reader.result;
      const rows = [];
      const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
      const isHeader = /^(name|full\s*name|student\s*(name|id)|usn)\s*[,;]/i;
      for (const line of lines) {
        if (rows.length === 0 && isHeader.test(line)) continue;
        const [name, student_id, email] = line.split(/[,;]/).map((s) => (s || "").trim());
        if (name) rows.push({ name, student_id: student_id || "", email: email || "" });
      }
      setLoading(true);
      setMessage(null);
      setCsvResult(null);
      try {
        const res = await authApi.registerStudentsBulk({
          branch: regForm.branch,
          semester: regForm.semester,
          section: regForm.section,
          rows,
        });
        setCsvResult(res);
        if (res.total_created > 0) {
          setStudentTab("login");
          setUsername(res.created[0].student_id);
        }
      } catch (err) {
        setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message });
      } finally {
        setLoading(false);
      }
    };
    reader.readAsText(file);
  };

  const reset = () => {
    setRole(null);
    setPhase("pick");
    setMessage(null);
    setNewCreds(null);
    setCsvResult(null);
  };

  const sectionTitle = role
    ? ROLE_CARDS.find((r) => r.key === role)?.label
    : "";

  return (
    <div style={{ minHeight: "100vh", background: "linear-gradient(135deg, #0f172a 0%, #1e3a5f 50%, #0f172a 100%)", color: COLORS.light, padding: "2rem 1rem" }}>
      <div style={{ maxWidth: 860, margin: "0 auto" }}>
        <div style={{ textAlign: "center", marginBottom: "1.5rem" }}>
          <div style={{ fontSize: "3rem" }}>🎓</div>
          <h1 style={{ fontSize: "2rem", margin: "0.25rem 0", fontFamily: "'JetBrains Mono', monospace", letterSpacing: "0.05em" }}>TrinetraX</h1>
          <p style={{ opacity: 0.7, marginTop: 0, fontFamily: "'JetBrains Mono', monospace", fontSize: "0.85rem" }}>Intelligent Attendance Management System</p>
          <p style={{ opacity: 0.5, marginTop: "0.2rem", fontSize: "0.75rem" }}>Using Computer Vision & AI</p>
        </div>

        {!role && (
          <div>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "1rem" }}>
              {ROLE_CARDS.map((r) => (
                <button
                  key={r.key}
                  onClick={() => pickRole(r.key)}
                  style={{
                    background: r.color,
                    color: "#fff",
                    border: "none",
                    borderRadius: "10px",
                    padding: "1.4rem 1rem",
                    cursor: "pointer",
                    fontSize: "1.1rem",
                    fontWeight: 600,
                    boxShadow: "0 6px 16px rgba(0,0,0,0.3)",
                    transition: "transform .15s ease",
                  }}
                >
                  <div style={{ fontSize: "2rem", marginBottom: "0.4rem" }}>{r.icon}</div>
                  {r.label}
                  <div style={{ fontSize: "0.75rem", fontWeight: 400, opacity: 0.85, marginTop: "0.3rem" }}>{r.desc}</div>
                </button>
              ))}
            </div>
          </div>
        )}

        {role && (
          <div style={{ background: "#fff", color: "#222", borderRadius: "12px", padding: "1.5rem", boxShadow: "0 10px 30px rgba(0,0,0,0.35)" }}>
            <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", marginBottom: "1rem" }}>
              <button onClick={reset} style={{ ...smallButton("#95a5a6") }}>← Back</button>
              <h2 style={{ margin: 0, fontSize: "1.3rem" }}>{sectionTitle}</h2>
            </div>

            {message && <Banner tone={message.tone}>{message.text}</Banner>}
            {loading && <Banner tone="info">Please wait...</Banner>}

            {role !== "student" && role !== "admin" && phase === "pick" && (
              <div>
                <label style={field("Branch")}>Select your branch</label>
                <select value={branch} onChange={(e) => setBranch(e.target.value)} style={{ ...selectStyle, width: "100%", padding: "0.6rem" }}>
                  {BRANCHES.map((b) => (
                    <option key={b} value={b}>{b} ({BRANCH_CODES[b]})</option>
                  ))}
                </select>
                <button onClick={lookUp} disabled={loading} style={{ ...buttonStyle(COLORS.primary), marginTop: "1rem", width: "100%" }}>
                  {role === "hod" ? "Check / Register HOD" : "Check / Register Teacher"}
                </button>
              </div>
            )}

            {role !== "student" && role !== "admin" && phase === "registered" && (
              <div>
                <Banner tone="success">
                  {registeredUsers.length} {sectionTitle}(s) already registered in {branch}. Choose one to sign in, or register a new one below.
                </Banner>
                <div style={{ display: "grid", gap: "0.5rem", marginBottom: "1rem" }}>
                  {registeredUsers.map((u) => (
                    <button
                      key={u.id}
                      onClick={() => { setUsername(u.username || u.email); setPhase("login"); }}
                      style={{
                        ...buttonStyle("#34495e"),
                        justifyContent: "space-between",
                        textAlign: "left",
                      }}
                    >
                      <span>{u.full_name} {u.employee_id ? `(${u.employee_id})` : ""}</span>
                      <span style={{ fontSize: "0.7rem", opacity: 0.8 }}>Sign in →</span>
                    </button>
                  ))}
                </div>
                <button onClick={() => setPhase("register")} style={buttonStyle(COLORS.accent)}>
                  {role === "hod" ? "Register HOD for this branch" : "Register a new teacher"}
                </button>
              </div>
            )}

            {role !== "student" && role !== "admin" && phase === "register" && (
              <div>
                {role === "hod" && (
                  <div>
                    <Banner tone="info">
                      The HOD for {branch} is <strong>{HOD_NAMES[branch] || "—"}</strong>. Create their account to get sign-in credentials.
                    </Banner>
                    <div style={{ margin: "1rem 0", padding: "0.75rem", background: "#f8f9fa", borderRadius: "8px" }}>
                      <div style={{ fontSize: "0.85rem" }}><strong>Name:</strong> {HOD_NAMES[branch]}</div>
                      <div style={{ fontSize: "0.85rem" }}><strong>Branch:</strong> {branch}</div>
                    </div>
                    <button onClick={() => handleRegister("hod")} disabled={loading} style={{ ...buttonStyle(COLORS.success), width: "100%" }}>
                      {loading ? "Creating account..." : "Create HOD account"}
                    </button>
                  </div>
                )}
                {role === "teacher" && (
                  <div>
                    <Banner tone="info">Register as a teacher in {branch}.</Banner>
                    <label style={field("Full name")}>Full name</label>
                    <input value={regForm.name} onChange={(e) => setRegForm({ ...regForm, name: e.target.value })} placeholder="e.g. Prof. Uttam" style={{ ...inputStyle, width: "100%", marginBottom: "0.75rem" }} />
                    <button onClick={() => handleRegister("teacher")} disabled={loading || !regForm.name.trim()} style={{ ...buttonStyle(COLORS.success), width: "100%" }}>
                      {loading ? "Creating account..." : "Create teacher account"}
                    </button>
                  </div>
                )}

                {newCreds && (
                  <Banner tone="success" style={{ marginTop: "1rem" }}>
                    ✅ Account created! Your login details:
                    <div style={{ marginTop: "0.4rem", fontFamily: "monospace" }}>
                      <div>Username: <strong>{newCreds.username}</strong></div>
                      <div>Password: <strong>{newCreds.password}</strong></div>
                    </div>
                  </Banner>
                )}
              </div>
            )}

            {role !== "student" && phase === "login" && (
              <div>
                {role === "admin" ? (
                  <Banner tone="success">👑 Principal account is pre-created. Username <strong>principal</strong> — use password <strong>admin123</strong> to sign in.</Banner>
                ) : (
                  <Banner tone="info">Sign in to your {sectionTitle} account.</Banner>
                )}
                <label style={field("Username")}>Username</label>
                <input value={username} onChange={(e) => setUsername(e.target.value)} style={{ ...inputStyle, width: "100%", marginBottom: "0.75rem" }} />
                <label style={field("Password")}>Password</label>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={{ ...inputStyle, width: "100%", marginBottom: "0.75rem" }} />
                <button onClick={() => doLogin(username, password)} disabled={loading || !username || !password} style={{ ...buttonStyle(COLORS.success), width: "100%" }}>
                  {loading ? "Signing in..." : "Sign in"}
                </button>
                <button onClick={() => setPhase("register")} style={{ ...smallButton("#95a5a6"), marginTop: "0.75rem", width: "100%" }}>
                  Not registered? Register here
                </button>
              </div>
            )}

            {role === "student" && (
              <div>
                <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1rem", flexWrap: "wrap" }}>
                  {[["login", "Sign In"], ["register", "Register Individually"], ["csv", "Bulk Register (CSV)"]].map(([k, label]) => (
                    <button key={k} onClick={() => setStudentTab(k)} style={{ ...smallButton(studentTab === k ? COLORS.primary : "#e0e0e0"), color: studentTab === k ? "#fff" : "#333" }}>
                      {label}
                    </button>
                  ))}
                </div>

                {studentTab === "login" && (
                  <div>
                    <Banner tone="info">
                      Login with your USN (e.g. 1AR24IS002) and your password which is <strong>&lt;USN&gt;@26</strong> (e.g. <strong>1AR24IS002@26</strong>).
                    </Banner>
                    <label style={field("USN / Username")}>USN</label>
                    <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="e.g. 1AR24IS002" style={{ ...inputStyle, width: "100%", marginBottom: "0.75rem" }} />
                    <label style={field("Password")}>Password</label>
                    <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="e.g. 1AR24IS002@26" style={{ ...inputStyle, width: "100%", marginBottom: "0.75rem" }} />
                    <button onClick={() => doLogin(username, password)} disabled={loading || !username || !password} style={{ ...buttonStyle(COLORS.success), width: "100%" }}>
                      {loading ? "Signing in..." : "Sign in"}
                    </button>
                  </div>
                )}

                {studentTab === "register" && (
                  <div>
                    <Banner tone="info">Register as a new student. Your password will be <strong>&lt;USN&gt;@26</strong> (e.g. <strong>1AR24IS002@26</strong>).</Banner>
                    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "0.5rem" }}>
                      <div>
                        <label style={field("Full name")}>Full name</label>
                        <input value={regForm.name} onChange={(e) => setRegForm({ ...regForm, name: e.target.value })} placeholder="e.g. John Doe" style={{ ...inputStyle, width: "100%" }} />
                      </div>
                      <div>
                        <label style={field("Branch")}>Branch</label>
                        <select value={regForm.branch} onChange={(e) => setRegForm({ ...regForm, branch: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                        </select>
                      </div>
                      <div>
                        <label style={field("Semester")}>Semester</label>
                        <select value={regForm.semester} onChange={(e) => setRegForm({ ...regForm, semester: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
                        </select>
                      </div>
                      <div>
                        <label style={field("Section")}>Section</label>
                        <select value={regForm.section} onChange={(e) => setRegForm({ ...regForm, section: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {SECTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
                        </select>
                      </div>
                    </div>
                    <button onClick={() => handleRegister("student")} disabled={loading || !regForm.name.trim()} style={{ ...buttonStyle(COLORS.success), marginTop: "0.75rem", width: "100%" }}>
                      {loading ? "Registering..." : "Register"}
                    </button>
                    {newCreds && (
                      <Banner tone="success" style={{ marginTop: "1rem" }}>
                        ✅ Registered! Your USN is <strong>{newCreds.student_id}</strong> and password is <strong>{newCreds.password}</strong>. Sign in above.
                      </Banner>
                    )}
                  </div>
                )}

                {studentTab === "csv" && (
                  <div>
                    <Banner tone="info">
                      Upload a <strong>CSV</strong> file with one student per line. Format:
                      <div style={{ fontFamily: "monospace", marginTop: "0.4rem" }}>Name, USN(optional), Email(optional)</div>
                      <div style={{ fontFamily: "monospace" }}>John Doe, , john.doe@example.com</div>
                      <div style={{ fontFamily: "monospace" }}>Jane Smith</div>
                      <div style={{ marginTop: "0.4rem" }}>USNs are auto-generated when left blank. Passwords follow the &lt;USN&gt;@26 pattern (e.g. 1AR24IS002@26).</div>
                    </Banner>
                    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "0.5rem", marginBottom: "0.75rem" }}>
                      <div>
                        <label style={field("Branch")}>Branch</label>
                        <select value={regForm.branch} onChange={(e) => setRegForm({ ...regForm, branch: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                        </select>
                      </div>
                      <div>
                        <label style={field("Semester")}>Semester</label>
                        <select value={regForm.semester} onChange={(e) => setRegForm({ ...regForm, semester: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
                        </select>
                      </div>
                      <div>
                        <label style={field("Section")}>Section</label>
                        <select value={regForm.section} onChange={(e) => setRegForm({ ...regForm, section: e.target.value })} style={{ ...inputStyle, width: "100%" }}>
                          {SECTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
                        </select>
                      </div>
                    </div>
                    <input
                      ref={fileRef}
                      type="file"
                      accept=".csv,text/csv"
                      style={{ marginBottom: "0.75rem" }}
                      onChange={(e) => { handleCsv(e.target.files[0]); e.target.value = ""; }}
                    />
                    <button onClick={() => fileRef.current?.click()} style={buttonStyle(COLORS.primary)}>📄 Choose CSV file</button>
                    {csvResult && (
                      <div style={{ marginTop: "1rem" }}>
                        <Banner tone={csvResult.total_created > 0 ? "success" : "warning"}>
                          ✅ Created <strong>{csvResult.total_created}</strong> student(s). Skipped <strong>{csvResult.total_skipped}</strong>.
                        </Banner>
                        <div style={{ maxHeight: 300, overflowY: "auto", border: "1px solid #eee", borderRadius: "8px" }}>
                          <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.8rem" }}>
                            <thead>
                              <tr style={{ background: COLORS.primary, color: "#fff" }}>
                                <th style={{ padding: "0.4rem", textAlign: "left" }}>USN</th>
                                <th style={{ padding: "0.4rem", textAlign: "left" }}>Name</th>
                                <th style={{ padding: "0.4rem", textAlign: "left" }}>Password</th>
                              </tr>
                            </thead>
                            <tbody>
                              {csvResult.created.map((s, i) => (
                                <tr key={i} style={{ borderBottom: "1px solid #dee2e6" }}>
                                  <td style={{ padding: "0.4rem" }}>{s.student_id}</td>
                                  <td style={{ padding: "0.4rem" }}>{s.name}</td>
                                  <td style={{ padding: "0.4rem", fontWeight: 600 }}>{s.password}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}