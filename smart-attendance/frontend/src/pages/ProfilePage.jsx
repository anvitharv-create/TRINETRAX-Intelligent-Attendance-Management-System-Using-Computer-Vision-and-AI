import React, { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../services/authContext";
import { studentsApi, teachersApi, usersApi, recognitionApi } from "../services/api";
import { useTeachers } from "../services/teachersService";
import FaceRegistration from "../components/FaceRegistration";
import { COLORS, StatCard, Banner, pageStyle, selectStyle, inputStyle, buttonStyle, BRANCHES, SEMESTERS, SECTIONS } from "../theme";

export default function ProfilePage() {
  const { user, refreshUser } = useAuth();
  const navigate = useNavigate();
  const [myStudent, setMyStudent] = useState(null);
  const { teachers } = useTeachers();
  const [faceStatus, setFaceStatus] = useState(null);
  const [registering, setRegistering] = useState(false);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState(null);
  const [form, setForm] = useState({});
  const fileRef = useRef(null);

  const myTeacher = teachers.find((t) => t.employee_id === user?.employee_id);

  useEffect(() => {
    if (user?.role === "student" && user?.student_id) {
      studentsApi.list({ branch: user.branch, semester: user.semester, section: user.section })
        .then((list) => setMyStudent(list.find((s) => s.student_id === user.student_id)))
        .catch(() => {});
    }
  }, [user?.student_id, user?.branch, user?.semester, user?.section, user?.role]);

  useEffect(() => {
    recognitionApi.status().then(setFaceStatus).catch(() => {});
  }, []);

  useEffect(() => {
    setForm({
      full_name: user?.full_name || "",
      email: user?.email || "",
      password: "",
      branch: user?.branch || myStudent?.branch || myTeacher?.branch || "",
      semester: user?.semester || myStudent?.semester || myTeacher?.semester || "",
      section: user?.section || myStudent?.section || myTeacher?.section || "",
    });
  }, [user, myStudent, myTeacher]);

  const displayName = user?.full_name || myStudent?.name || myTeacher?.name || "User";
  const idLabel = user?.student_id ? `Student ID: ${user.student_id}` : user?.employee_id ? `Employee ID: ${user.employee_id}` : "";
  const branchLabel = myStudent?.branch || myTeacher?.branch || user?.branch || "—";
  const loginUsername = user?.username || user?.student_id || user?.email || "—";

  const handleSave = async () => {
    setSaving(true);
    setMessage(null);
    const payload = { ...form };
    if (!payload.password) delete payload.password;
    try {
      await usersApi.updateMe(payload);
      await refreshUser();
      setEditing(false);
      setMessage({ tone: "success", text: "Profile updated successfully." });
    } catch (err) {
      setMessage({ tone: "danger", text: err?.response?.data?.detail || err.message || "Failed to update profile" });
    } finally {
      setSaving(false);
    }
  };

  const handlePhotoUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      setMessage({ tone: "danger", text: "Image must be under 2MB." });
      return;
    }
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        setMessage(null);
        await usersApi.uploadPhoto(reader.result);
        await refreshUser();
        setMessage({ tone: "success", text: "Profile photo updated." });
      } catch (err) {
        setMessage({ tone: "danger", text: "Failed to upload photo." });
      }
    };
    reader.readAsDataURL(file);
  };

  const change = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const photoSrc = user?.profile_photo || null;

  return (
    <div style={pageStyle}>
      <h2 style={{ marginTop: 0 }}>My Profile</h2>
      {message && <Banner tone={message.tone}>{message.text}</Banner>}

      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: "1rem" }}>
        <button onClick={() => { setEditing(!editing); setMessage(null); }} style={buttonStyle(editing ? "#95a5a6" : COLORS.info)}>
          {editing ? "Cancel Edit" : "Edit Profile"}
        </button>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1.5rem", flexWrap: "wrap" }}>
        <div>
          <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "10px", padding: "1.5rem" }}>
            <div style={{ position: "relative", display: "inline-block", marginBottom: "1rem" }}>
              {photoSrc ? (
                <img
                  src={photoSrc}
                  alt="Profile"
                  style={{ width: 96, height: 96, borderRadius: "50%", objectFit: "cover", border: `3px solid ${COLORS.primary}` }}
                />
              ) : (
                <div style={{ width: 96, height: 96, borderRadius: "50%", background: COLORS.primary, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontSize: "2.5rem" }}>
                  {displayName.charAt(0).toUpperCase()}
                </div>
              )}
              <button
                onClick={() => fileRef.current?.click()}
                style={{
                  position: "absolute", bottom: 0, right: 0,
                  width: 28, height: 28, borderRadius: "50%",
                  background: COLORS.info, color: "#fff", border: "2px solid #fff",
                  cursor: "pointer", fontSize: "0.75rem", display: "flex",
                  alignItems: "center", justifyContent: "center",
                }}
                title="Change photo"
              >
                📷
              </button>
              <input ref={fileRef} type="file" accept="image/*" style={{ display: "none" }} onChange={handlePhotoUpload} />
            </div>
            <h3 style={{ margin: 0 }}>{displayName}</h3>
            <div style={{ color: "#666", fontSize: "0.9rem", textTransform: "capitalize" }}>{user?.role}</div>
            <div style={{ color: "#888", fontSize: "0.8rem", marginTop: "0.25rem" }}>{user?.email}</div>
            {idLabel && <div style={{ color: "#888", fontSize: "0.8rem" }}>{idLabel}</div>}
            <div style={{ color: "#888", fontSize: "0.8rem" }}>Login ID: {loginUsername}</div>
            <div style={{ marginTop: "1rem", display: "flex", gap: "1rem", flexWrap: "wrap" }}>
              <StatCard label="Branch" value={branchLabel} color={COLORS.primary} />
              <StatCard label="Semester" value={myStudent?.semester || myTeacher?.semester || user?.semester || "—"} color={COLORS.info} />
              <StatCard label="Section" value={myStudent?.section || myTeacher?.section || user?.section || "—"} color={COLORS.success} />
            </div>
          </div>

          {user?.role === "student" && (myStudent?.student_id || user?.student_id) && (
            <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "10px", padding: "1.5rem", marginTop: "1rem" }}>
              <h3 style={{ marginTop: 0 }}>Face Recognition</h3>
              {faceStatus?.real ? (
                <Banner tone="success">
                  Real face recognition is active ({faceStatus.method}). There {faceStatus.registered === 1 ? "is" : "are"} <strong>{faceStatus.registered}</strong> registered face(s).
                </Banner>
              ) : (
                <Banner tone="warning">Real face recognition backend is not available — results will be simulated.</Banner>
              )}
              <p style={{ fontSize: "0.85rem", color: "#666" }}>
                Register your face so the AI attendance camera can recognize you automatically in classroom photos.
              </p>
              <button onClick={() => setRegistering(true)} style={{ ...buttonStyle(COLORS.success) }}>
                Register My Face
              </button>
            </div>
          )}
        </div>

        <div>
          {editing ? (
            <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "10px", padding: "1.5rem" }}>
              <h3 style={{ marginTop: 0 }}>Edit Profile</h3>
              <div style={{ display: "grid", gap: "0.6rem" }}>
                <div>
                  <label style={labelStyle}>Full name</label>
                  <input value={form.full_name} onChange={change("full_name")} style={{ ...inputStyle, width: "100%" }} />
                </div>
                <div>
                  <label style={labelStyle}>Email</label>
                  <input value={form.email} onChange={change("email")} style={{ ...inputStyle, width: "100%" }} />
                </div>
                <div>
                  <label style={labelStyle}>New password (leave blank to keep current)</label>
                  <input type="password" value={form.password} onChange={change("password")} placeholder="---" style={{ ...inputStyle, width: "100%" }} />
                </div>
                {user?.role !== "hod" && (
                  <>
                    <div>
                      <label style={labelStyle}>Branch</label>
                      <select value={form.branch} onChange={change("branch")} style={{ ...selectStyle, width: "100%" }}>
                        {BRANCHES.map((b) => <option key={b} value={b}>{b}</option>)}
                      </select>
                    </div>
                    <div>
                      <label style={labelStyle}>Semester</label>
                      <select value={form.semester} onChange={change("semester")} style={{ ...selectStyle, width: "100%" }}>
                        {SEMESTERS.map((s) => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </div>
                    <div>
                      <label style={labelStyle}>Section</label>
                      <select value={form.section} onChange={change("section")} style={{ ...selectStyle, width: "100%" }}>
                        {SECTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </div>
                  </>
                )}
                <button onClick={handleSave} disabled={saving} style={{ ...buttonStyle(COLORS.success) }}>
                  {saving ? "Saving..." : "Save Changes"}
                </button>
              </div>
            </div>
          ) : (
            <div style={{ background: "#fff", border: "1px solid #dee2e6", borderRadius: "10px", padding: "1.5rem" }}>
              <h3 style={{ marginTop: 0 }}>Account Information</h3>
              <div style={{ display: "grid", gap: "0.5rem", fontSize: "0.9rem" }}>
                <div><strong>Role:</strong> <span style={{ textTransform: "capitalize" }}>{user?.role}</span></div>
                <div><strong>Username / Login ID:</strong> {loginUsername}</div>
                <div><strong>Email:</strong> {user?.email || "—"}</div>
                <div><strong>Branch:</strong> {branchLabel}</div>
                {myStudent && <div><strong>USN:</strong> {myStudent.student_id}</div>}
                {myTeacher && <div><strong>Employee ID:</strong> {myTeacher.employee_id}</div>}
                <div style={{ marginTop: "0.5rem", color: "#666", fontSize: "0.8rem" }}>
                  Click <strong>Edit Profile</strong> to modify your info. Click the camera icon on your photo to upload a profile picture.
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {user?.role === "student" && (
        <div
          style={{
            background: "#fff",
            border: "1px solid #dee2e6",
            borderRadius: "10px",
            padding: "1.5rem",
            marginTop: "1.5rem",
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            flexWrap: "wrap",
            gap: "1rem",
          }}
        >
          <div>
            <h3 style={{ margin: 0 }}>Attendance Report</h3>
            <p style={{ margin: "0.25rem 0 0", color: "#666", fontSize: "0.85rem" }}>
              Generate a complete report with your profile, attendance summary and history.
            </p>
          </div>
          <button onClick={() => navigate("/student/reports")} style={buttonStyle(COLORS.primary)}>
            Generate Report
          </button>
        </div>
      )}

      {registering && (
        <FaceRegistration
          studentId={myStudent?.student_id || user?.student_id || ""}
          name={displayName}
          onDone={() => { setRegistering(false); recognitionApi.status().then(setFaceStatus); }}
          onClose={() => setRegistering(false)}
        />
      )}
    </div>
  );
}

const labelStyle = { display: "block", fontSize: "0.72rem", fontWeight: 600, color: "#555", marginBottom: "0.25rem" };
