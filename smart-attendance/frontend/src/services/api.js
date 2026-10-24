import axios from "axios";

const API_BASE = "http://localhost:8000/api";

export const api = axios.create({
  baseURL: API_BASE,
  headers: { "Content-Type": "application/json" },
});

// Attach auth token automatically
api.interceptors.request.use((config) => {
  const token = localStorage.getItem("access_token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

export const authApi = {
  login: async (username, password) => {
    const res = await api.post("/auth/login", { username, password });
    localStorage.setItem("access_token", res.data.access_token);
    localStorage.setItem("role", res.data.user.role);
    return res.data.user;
  },
  logout: () => {
    localStorage.removeItem("access_token");
    localStorage.removeItem("role");
  },
  check: (role, branch = "") => {
    const qs = branch ? `&branch=${encodeURIComponent(branch)}` : "";
    return api.get(`/auth/check?role=${encodeURIComponent(role)}${qs}`).then((r) => r.data);
  },
  credentials: () => api.get("/auth/credentials").then((r) => r.data),
  registerHod: (data) => api.post("/auth/register/hod", data).then((r) => r.data),
  registerTeacher: (data) => api.post("/auth/register/teacher", data).then((r) => r.data),
  registerStudent: (data) => api.post("/auth/register/student", data).then((r) => r.data),
  registerStudentsBulk: (data) => api.post("/auth/register/students/bulk", data).then((r) => r.data),
};

export const usersApi = {
  me: () => api.get("/users/me").then((r) => r.data),
  updateMe: (data) => api.put("/users/me", data).then((r) => r.data),
  uploadPhoto: (photo) => api.post("/users/me/photo", { photo }).then((r) => r.data),
  updateUser: (id, data) => api.put(`/users/${id}`, data).then((r) => r.data),
};

export const studentsApi = {
  list: (params = {}) => {
    const qs = new URLSearchParams(
      Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/students${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  create: (student) => api.post("/students", student).then((r) => r.data),
  get: (id) => api.get(`/students/${id}`).then((r) => r.data),
  update: (id, data) => api.put(`/students/${id}`, data).then((r) => r.data),
  remove: (id) => api.delete(`/students/${id}`).then((r) => r.data),
};

export const teachersApi = {
  list: (branch = "") => {
    return api.get(`/teachers${branch ? `?branch=${branch}` : ""}`).then((r) => r.data);
  },
  create: (teacher) => api.post("/teachers", teacher).then((r) => r.data),
  update: (id, data) => api.put(`/teachers/${id}`, data).then((r) => r.data),
  remove: (id) => api.delete(`/teachers/${id}`).then((r) => r.data),
};

export const subjectsApi = {
  list: (branch = "", semester = "", teacher_id = "") => {
    const params = [];
    if (branch) params.push(`branch=${encodeURIComponent(branch)}`);
    if (semester) params.push(`semester=${encodeURIComponent(semester)}`);
    if (teacher_id) params.push(`teacher_id=${encodeURIComponent(teacher_id)}`);
    return api.get(`/subjects${params.length ? `?${params.join("&")}` : ""}`).then((r) => r.data);
  },
  create: (subject) => api.post("/subjects", subject).then((r) => r.data),
  remove: (id) => api.delete(`/subjects/${id}`).then((r) => r.data),
};

export const attendanceApi = {
  capture: (data) => api.post("/attendance/capture", data).then((r) => r.data),
  list: (subject_id, date, start_date, end_date) => {
    const params = [];
    if (subject_id) params.push(`subject_id=${subject_id}`);
    if (date) params.push(`date=${date}`);
    if (start_date) params.push(`start_date=${start_date}`);
    if (end_date) params.push(`end_date=${end_date}`);
    return api.get(`/attendance${params.length ? `?${params.join("&")}` : ""}`).then((r) => r.data);
  },
  student: (student_id) =>
    api.get(`/attendance/student/${student_id}`).then((r) => r.data),
  update: (id, status, confidence) =>
    api.put(`/attendance/${id}`, { status, confidence }).then((r) => r.data),
  remove: (id) => api.delete(`/attendance/${id}`).then((r) => r.data),
};

export const notificationsApi = {
  list: (user_id, unreadOnly = false) => {
    const qs = unreadOnly ? "?unread_only=true" : "";
    return api.get(`/notifications/${user_id}${qs}`).then((r) => r.data);
  },
  markRead: (id) => api.put(`/notifications/${id}/read`).then((r) => r.data),
};

export const analyticsApi = {
  overall: () => api.get(`/analytics/overall`).then((r) => r.data),
  semester: (semester) => api.get(`/analytics/semester/${encodeURIComponent(semester)}`).then((r) => r.data),
  reports: (filters = {}) => {
    const qs = new URLSearchParams(filters).toString();
    return api.get(`/reports/attendance${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
};

export const teacherApi = {
  dashboard: () => api.get("/teacher/dashboard").then((r) => r.data),
  reports: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/teacher/reports/attendance${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  notifyEligible: (channel = "whatsapp") =>
    api.get("/teacher/reports/notify/eligible", { params: { channel } }).then((r) => r.data),
  notifyStart: (payload) => api.post("/teacher/reports/notify", payload).then((r) => r.data),
  notifyBatch: (batchId) =>
    api.get(`/teacher/reports/notify/batch/${encodeURIComponent(batchId)}`).then((r) => r.data),
};

export const studentReportApi = {
  get: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/student/report${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  downloadPdf: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api
      .get(`/student/report/pdf${qs ? `?${qs}` : ""}`, { responseType: "blob" })
      .then((r) => r.data);
  },
};

export const hodReportApi = {
  comparison: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/hod/reports/comparison${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  downloadPdf: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api
      .get(`/hod/reports/comparison/pdf${qs ? `?${qs}` : ""}`, { responseType: "blob" })
      .then((r) => r.data);
  },
  downloadXlsx: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api
      .get(`/hod/reports/comparison/xlsx${qs ? `?${qs}` : ""}`, { responseType: "blob" })
      .then((r) => r.data);
  },
};

export const recognitionApi = {
  status: () => api.get("/recognition/status").then((r) => r.data),
  register: (student_id, name, image_base64) =>
    api.post("/recognition/register", { student_id, name, image_base64 }).then((r) => r.data),
  registerMulti: (student_id, name, shots) =>
    api
      .post("/recognition/register/multi", {
        student_id,
        name,
        // the front shot doubles as the legacy single image so both code paths
        // validate against the same picture
        image_base64: shots.front,
        images: [
          { pose: "front", image_base64: shots.front },
          { pose: "left", image_base64: shots.left },
          { pose: "right", image_base64: shots.right },
        ],
      })
      .then((r) => r.data),
  remove: (student_id) => api.post("/recognition/remove", { student_id }).then((r) => r.data),
  detect: (image_base64) => api.post("/recognition/detect", { image_base64 }).then((r) => r.data),
  analyze: (image_base64, subject_id, section) =>
    api.post("/recognition/analyze", { image_base64, subject_id, section }).then((r) => r.data),
};

export const facesApi = {
  // Excel roster + ZIP of photos, validated without changing anything
  preview: (rosterFile, photosFile) => {
    const fd = new FormData();
    fd.append("roster", rosterFile);
    fd.append("photos", photosFile);
    return api
      .post("/faces/bulk/preview", fd, { headers: { "Content-Type": "multipart/form-data" } })
      .then((r) => r.data);
  },
  // Actually import the validated faces (overwrite=false keeps existing ones)
  commit: (rosterFile, photosFile, overwrite = false) => {
    const fd = new FormData();
    fd.append("roster", rosterFile);
    fd.append("photos", photosFile);
    fd.append("overwrite", overwrite ? "true" : "false");
    return api
      .post("/faces/bulk/commit", fd, { headers: { "Content-Type": "multipart/form-data" } })
      .then((r) => r.data);
  },
};

export const adminApi = {
  overview: (filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/admin/overview${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  departmentDetail: (branch, filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/admin/departments/${encodeURIComponent(branch)}${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  semesterDetail: (branch, semester, filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(
      `/admin/departments/${encodeURIComponent(branch)}/${encodeURIComponent(semester)}${qs ? `?${qs}` : ""}`
    ).then((r) => r.data);
  },
  classOverview: (branch, semester, section, filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(
      `/admin/classes/${encodeURIComponent(branch)}/${encodeURIComponent(semester)}/${encodeURIComponent(section)}${qs ? `?${qs}` : ""}`
    ).then((r) => r.data);
  },
  lowAttendance: (branch, filters = {}) => {
    const qs = new URLSearchParams(
      Object.entries(filters).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    return api.get(`/admin/low-attendance/${encodeURIComponent(branch)}${qs ? `?${qs}` : ""}`).then((r) => r.data);
  },
  exportReport: async (params = {}) => {
    const qs = new URLSearchParams(
      Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== "")
    ).toString();
    const res = await api.get(`/admin/reports/export${qs ? `?${qs}` : ""}`, { responseType: "blob" });
    const url = URL.createObjectURL(res.data);
    const a = document.createElement("a");
    a.href = url;
    const disposition = res.headers?.["content-disposition"] || "";
    const match = /filename="?([^";]+)"?/.exec(disposition);
    a.download = match ? match[1] : "trinetra-report.xlsx";
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    return true;
  },
  teachersByBranch: (branch) => api.get(`/admin/teachers/${encodeURIComponent(branch)}`).then((r) => r.data),
  studentsByBranch: (branch) => api.get(`/admin/students/${encodeURIComponent(branch)}`).then((r) => r.data),
  hods: () => api.get("/admin/hods").then((r) => r.data),
  createHod: (data) => api.post("/admin/hods", data).then((r) => r.data),
  updateHod: (id, data) => usersApi.updateUser(id, data),
};