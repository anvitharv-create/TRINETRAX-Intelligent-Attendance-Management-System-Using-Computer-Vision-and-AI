import React, { useEffect } from "react";
import { Routes, Route, Link, useNavigate, useLocation, Navigate } from "react-router-dom";
import "./style.css";
import { useAuth } from "./services/authContext";
import { useNotifications } from "./services/notificationsService";
import { COLORS } from "./theme";

import DashboardPage from "./pages/DashboardPage";
import StudentsPage from "./pages/StudentsPage";
import TeachersPage from "./pages/TeachersPage";
import SubjectsPage from "./pages/SubjectsPage";
import AttendancePage from "./pages/AttendancePage";
import AnalyticsPage from "./pages/AnalyticsPage";
import ReportsPage from "./pages/ReportsPage";
import StudentAttendancePage from "./pages/StudentAttendancePage";
import PreviousAttendancePage from "./pages/PreviousAttendancePage";
import LoginPage from "./pages/LoginPage";

import AdminDashboardPage from "./pages/AdminDashboardPage";
import AdminUsersPage from "./pages/AdminUsersPage";
import AdminReportsPage from "./pages/AdminReportsPage";
import TeacherAttendancePage from "./pages/TeacherAttendancePage";
import NotificationsPage from "./pages/NotificationsPage";
import ProfilePage from "./pages/ProfilePage";
import StudentDashboardPage from "./pages/StudentDashboardPage";
import StudentReportPage from "./pages/StudentReportPage";

const MENUS = {
  admin: [
    { path: "/admin", label: "Overview", icon: "🏛" },
    { path: "/admin/users", label: "User Management", icon: "👥" },
    { path: "/admin/reports", label: "Reports", icon: "📄" },
    { path: "/admin/notifications", label: "Notifications", icon: "🔔" },
    { path: "/admin/profile", label: "Profile", icon: "👤" },
  ],
  hod: [
    { path: "/hod", label: "Dashboard", icon: "📊" },
    { path: "/hod/students", label: "Students", icon: "👥" },
    { path: "/hod/teachers", label: "Teachers", icon: "👨‍🏫" },
    { path: "/hod/subjects", label: "Subjects", icon: "📚" },
    { path: "/hod/attendance", label: "Attendance", icon: "📋" },
    { path: "/hod/analytics", label: "Analytics", icon: "📈" },
    { path: "/hod/reports", label: "Reports", icon: "📄" },
    { path: "/hod/notifications", label: "Notifications", icon: "🔔" },
    { path: "/hod/profile", label: "Profile", icon: "👤" },
  ],
  teacher: [
    { path: "/teacher", label: "Dashboard", icon: "📊" },
    { path: "/teacher/attendance", label: "Take Attendance", icon: "🤳" },
    { path: "/teacher/history", label: "History", icon: "📜" },
    { path: "/teacher/reports", label: "Reports", icon: "📄" },
    { path: "/teacher/students", label: "Students", icon: "👥" },
    { path: "/teacher/profile", label: "Profile", icon: "👤" },
  ],
  student: [
    { path: "/student", label: "Dashboard", icon: "📊" },
    { path: "/student/attendance", label: "My Attendance", icon: "📋" },
    { path: "/student/reports", label: "Reports", icon: "📄" },
    { path: "/student/profile", label: "Profile", icon: "👤" },
  ],
};

function NavBar({ role, logout }) {
  const { user } = useAuth();
  const { notifications, bindUser } = useNotifications();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    if (user?.id) bindUser(user.id);
  }, [user?.id, bindUser]);

  const unread = notifications.filter((n) => !n.read).length;
  const items = MENUS[role] || [];

  return (
    <nav
      className="no-print"
      style={{
        display: "flex",
        gap: "0.5rem",
        background: "linear-gradient(135deg, #0f172a 0%, #1e3a5f 100%)",
        padding: "0.6rem 1rem",
        borderRadius: "0 0 8px 8px",
        boxShadow: "0 2px 4px rgba(0,0,0,0.1)",
        flexWrap: "wrap",
        alignItems: "center",
        position: "sticky",
        top: 0,
        zIndex: 100,
      }}
    >
      <Link
        to={`/${role}`}
        style={{ color: COLORS.light, textDecoration: "none", fontWeight: "bold", fontSize: "1.05rem" }}
      >
        <span style={{ color: COLORS.accent, fontFamily: "'JetBrains Mono', monospace", letterSpacing: "0.05em" }}>TrinetraX</span>
      </Link>

      <div style={{ display: "flex", gap: "0.25rem", flexWrap: "wrap", justifyContent: "flex-end", flex: 1 }}>
        {items.map((item) => {
          const active = location.pathname === item.path;
          return (
            <Link
              key={item.path}
              to={item.path}
              style={{
                color: active ? "#fff" : COLORS.light,
                background: active ? "rgba(255,255,255,0.18)" : "transparent",
                textDecoration: "none",
                fontSize: "0.82rem",
                padding: "0.35rem 0.6rem",
                borderRadius: "6px",
                display: "inline-flex",
                alignItems: "center",
                gap: "0.3rem",
              }}
            >
              {item.icon} {item.label}
              {item.label === "Notifications" && unread > 0 && (
                <span style={{ background: COLORS.accent, borderRadius: 10, padding: "0.05rem 0.4rem", fontSize: "0.65rem", fontWeight: 700 }}>{unread}</span>
              )}
            </Link>
          );
        })}

        <button
          onClick={logout}
          style={{ background: COLORS.accent, color: "#fff", border: "none", padding: "0.4rem 0.8rem", borderRadius: "6px", fontSize: "0.75rem", cursor: "pointer", marginLeft: "0.25rem" }}
        >
          Logout
        </button>
      </div>
    </nav>
  );
}

function Layout({ children }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    if (!user) navigate("/", { replace: true });
  }, [user, navigate]);

  if (!user) return null;
  const role = user.role;

  // Route guard: users can only access their own role's area
  const expectedPrefix = `/${role}`;
  const allowed = location.pathname === expectedPrefix || location.pathname.startsWith(`${expectedPrefix}/`);
  if (!allowed) {
    return <Navigate to={expectedPrefix} replace />;
  }

  return (
    <>
      <NavBar role={role} logout={logout} />
      <div style={{ maxWidth: 1200, margin: "0 auto", padding: "0 1rem", background: "#f8fafc", minHeight: "100vh" }}>{children}</div>
    </>
  );
}

function PublicAuth({ children }) {
  const { user, isLoading, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    if (!isLoading && user && location.pathname === "/") {
      navigate(`/${user.role}`, { replace: true });
    }
  }, [user, isLoading, navigate, location.pathname]);

  if (isLoading) {
    return (
      <div style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", background: "linear-gradient(135deg, #0f172a 0%, #1e3a5f 50%, #0f172a 100%)", color: COLORS.light }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: "3rem", marginBottom: "1rem" }}>🎓</div>
          <h1 style={{ fontSize: "1.8rem", margin: 0, fontFamily: "'JetBrains Mono', monospace", letterSpacing: "0.05em" }}>TrinetraX</h1>
          <p style={{ opacity: 0.8 }}>Loading...</p>
        </div>
      </div>
    );
  }

  if (!user) {
    return <LoginPage />;
  }

  return <>{children}</>;
}

export default function App() {
  return (
    <PublicAuth>
      <Routes>
        {/* Admin */}
        <Route path="/admin" element={<Layout><AdminDashboardPage /></Layout>} />
        <Route path="/admin/users" element={<Layout><AdminUsersPage /></Layout>} />
        <Route path="/admin/reports" element={<Layout><AdminReportsPage /></Layout>} />
        <Route path="/admin/notifications" element={<Layout><NotificationsPage /></Layout>} />
        <Route path="/admin/profile" element={<Layout><ProfilePage /></Layout>} />

        {/* HOD */}
        <Route path="/hod" element={<Layout><DashboardPage /></Layout>} />
        <Route path="/hod/students" element={<Layout><StudentsPage /></Layout>} />
        <Route path="/hod/teachers" element={<Layout><TeachersPage /></Layout>} />
        <Route path="/hod/subjects" element={<Layout><SubjectsPage /></Layout>} />
        <Route path="/hod/attendance" element={<Layout><TeacherAttendancePage /></Layout>} />
        <Route path="/hod/analytics" element={<Layout><AnalyticsPage /></Layout>} />
        <Route path="/hod/reports" element={<Layout><ReportsPage /></Layout>} />
        <Route path="/hod/notifications" element={<Layout><NotificationsPage /></Layout>} />
        <Route path="/hod/profile" element={<Layout><ProfilePage /></Layout>} />

        {/* Teacher */}
        <Route path="/teacher" element={<Layout><DashboardPage /></Layout>} />
        <Route path="/teacher/attendance" element={<Layout><TeacherAttendancePage /></Layout>} />
        <Route path="/teacher/history" element={<Layout><PreviousAttendancePage /></Layout>} />
        <Route path="/teacher/reports" element={<Layout><ReportsPage /></Layout>} />
        <Route path="/teacher/students" element={<Layout><StudentsPage /></Layout>} />
        <Route path="/teacher/profile" element={<Layout><ProfilePage /></Layout>} />

        {/* Student */}
        <Route path="/student" element={<Layout><StudentDashboardPage /></Layout>} />
        <Route path="/student/attendance" element={<Layout><StudentAttendancePage /></Layout>} />
        <Route path="/student/reports" element={<Layout><StudentReportPage /></Layout>} />
        <Route path="/student/profile" element={<Layout><ProfilePage /></Layout>} />

        <Route
          path="*"
          element={
            <Layout>
              <div style={{ padding: "3rem", textAlign: "center" }}>
                <h2>Page not found</h2>
                <Link to="/">Go home</Link>
              </div>
            </Layout>
          }
        />
      </Routes>
    </PublicAuth>
  );
}