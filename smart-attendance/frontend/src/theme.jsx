// Shared design tokens & helpers for TrinetraX — Intelligent Attendance Management System.
// Cold gradient theme — deep navy, slate, teal accents with JetBrains Mono.

export const COLORS = {
  primary: "#0f172a",
  secondary: "#1e293b",
  accent: "#0ea5e9",
  success: "#10b981",
  warning: "#f59e0b",
  danger: "#ef4444",
  info: "#38bdf8",
  purple: "#a78bfa",
  light: "#f1f5f9",
  muted: "#94a3b8",
  border: "#e2e8f0",
  cardBg: "#ffffff",
  bg: "#f8fafc",
  gradientStart: "#0f172a",
  gradientEnd: "#1e3a5f",
};

export const buttonStyle = (bg) => ({
  background: bg || COLORS.primary,
  color: "#fff",
  border: "none",
  padding: "0.5rem 1rem",
  borderRadius: "8px",
  cursor: "pointer",
  fontSize: "0.85rem",
  fontWeight: 500,
  transition: "all .2s ease",
  display: "inline-flex",
  alignItems: "center",
  gap: "0.35rem",
  boxShadow: "0 1px 3px rgba(0,0,0,0.12)",
});

export const smallButton = (bg) => ({
  ...buttonStyle(bg),
  fontSize: "0.75rem",
  padding: "0.3rem 0.65rem",
  borderRadius: "6px",
});

export const inputStyle = {
  padding: "0.55rem 0.75rem",
  borderRadius: "8px",
  border: "1px solid #cbd5e1",
  fontSize: "0.85rem",
  fontFamily: "inherit",
  outline: "none",
  transition: "border-color .2s ease",
  background: "#fff",
};

export const selectStyle = { ...inputStyle, cursor: "pointer" };

export const thStyle = { padding: "0.7rem 0.6rem", textAlign: "left", fontSize: "0.8rem", fontWeight: 600 };
export const tdStyle = { padding: "0.6rem", borderBottom: "1px solid #f1f5f9", fontSize: "0.85rem" };

export const cardStyle = {
  background: COLORS.cardBg,
  borderRadius: "12px",
  padding: "1.2rem",
  border: "1px solid #e2e8f0",
  boxShadow: "0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)",
};

export const pageStyle = { padding: "2rem", maxWidth: "1200px", margin: "0 auto", width: "100%" };

export const panelStyle = {
  background: "#fff",
  borderRadius: "12px",
  padding: "1rem",
  border: "1px solid #e2e8f0",
  boxShadow: "0 1px 3px rgba(0,0,0,0.06)",
};

export const StatusBadge = ({ status }) => {
  const map = {
    present: { bg: "#d1fae5", color: "#065f46", label: "Present" },
    absent: { bg: "#fee2e2", color: "#991b1b", label: "Absent" },
    unknown: { bg: "#f1f5f9", color: "#475569", label: "Unknown" },
    late: { bg: "#fef3c7", color: "#92400e", label: "Late" },
  };
  const s = map[status] || { bg: "#f1f5f9", color: "#475569", label: status };
  return (
    <span
      style={{
        background: s.bg,
        color: s.color,
        padding: "0.2rem 0.6rem",
        borderRadius: "6px",
        fontSize: "0.72rem",
        fontWeight: 600,
        whiteSpace: "nowrap",
      }}
    >
      {s.label}
    </span>
  );
};

export const StatCard = ({ label, value, color, sub }) => (
  <div style={{
    ...cardStyle,
    background: "linear-gradient(135deg, #ffffff 0%, #f8fafc 100%)",
    borderTop: `3px solid ${color || COLORS.primary}`,
  }}>
    <div style={{ fontSize: "1.9rem", fontWeight: 700, color: color || COLORS.primary }}>{value}</div>
    <div style={{ fontSize: "0.8rem", color: "#475569", fontWeight: 500 }}>{label}</div>
    {sub && <div style={{ fontSize: "0.72rem", color: COLORS.muted, marginTop: "0.2rem" }}>{sub}</div>}
  </div>
);

export const Table = ({ head, children, style }) => (
  <table style={{ width: "100%", borderCollapse: "collapse", borderRadius: "8px", overflow: "hidden", ...style }}>
    <thead>
      <tr style={{ background: "linear-gradient(135deg, #0f172a 0%, #1e3a5f 100%)", color: "#fff" }}>
        {head.map((h, i) => (
          <th key={i} style={thStyle}>
            {h}
          </th>
        ))}
      </tr>
    </thead>
    <tbody>{children}</tbody>
  </table>
);

export const EmptyRow = ({ colSpan, children = "No data found" }) => (
  <tr>
    <td colSpan={colSpan} style={{ ...tdStyle, textAlign: "center", color: COLORS.muted, padding: "1.5rem" }}>
      {children}
    </td>
  </tr>
);

export const Banner = ({ children, tone = "info", style }) => {
  const tones = {
    info: { bg: "#e0f2fe", color: "#075985", border: "#bae6fd" },
    success: { bg: "#d1fae5", color: "#065f46", border: "#a7f3d0" },
    warning: { bg: "#fef3c7", color: "#92400e", border: "#fde68a" },
    danger: { bg: "#fee2e2", color: "#991b1b", border: "#fecaca" },
  };
  const t = tones[tone] || tones.info;
  return (
    <div
      style={{
        padding: "0.75rem 1rem",
        background: t.bg,
        color: t.color,
        border: `1px solid ${t.border}`,
        borderRadius: "8px",
        marginBottom: "0.75rem",
        fontSize: "0.85rem",
        ...style,
      }}
    >
      {children}
    </div>
  );
};

export const BRANCHES = ["ISE", "CSE", "ECE", "AIML", "CIVIL"];
export const SEMESTERS = [
  "1st Semester", "3rd Semester", "5th Semester", "7th Semester",
];
export const SECTIONS = ["Section A", "Section B"];

export const BRANCH_CODES = { ISE: "IS", CSE: "CS", ECE: "EC", AIML: "AI", CIVIL: "CV" };
