import React, { useEffect } from "react";
import { useAuth } from "../services/authContext";
import { useNotifications } from "../services/notificationsService";
import { notificationsApi } from "../services/api";
import { COLORS, smallButton, pageStyle, Table, EmptyRow } from "../theme";

const toneColors = {
  info: { bg: "#e8f4fd", color: "#0b5e8a", icon: "ℹ️" },
  success: { bg: "#e8f5e9", color: "#1b5e20", icon: "✅" },
  warning: { bg: "#fff3e0", color: "#7a4f01", icon: "⚠️" },
  danger: { bg: "#fee2e2", color: "#c53030", icon: "⛔" },
};

export default function NotificationsPage() {
  const { user } = useAuth();
  const { notifications, loading, bindUser, markAsRead } = useNotifications();

  useEffect(() => {
    if (user?.id) bindUser(user.id);
  }, [user?.id, bindUser]);

  const markAll = async () => {
    for (const n of notifications) {
      if (!n.read) await markAsRead(n.id);
    }
  };

  const unread = notifications.filter((n) => !n.read).length;

  return (
    <div style={pageStyle}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
        <div>
          <h2 style={{ margin: 0 }}>Notifications</h2>
          <p style={{ color: "#666", marginTop: "0.25rem" }}>{unread > 0 ? `${unread} unread` : "All caught up 🎉"}</p>
        </div>
        <button onClick={markAll} style={smallButton(COLORS.primary)}>Mark all as read</button>
      </div>

      {loading ? (
        <p>Loading notifications...</p>
      ) : (
        <Table head={["", "Notification", "Message", "Time", ""]}>
          {notifications.length === 0 && <EmptyRow colSpan={5} />}
          {notifications.map((n) => {
            const t = toneColors[n.type] || toneColors.info;
            return (
              <tr key={n.id} style={{ borderBottom: "1px solid #dee2e6", background: n.read ? "#fff" : "#f8f9fa" }}>
                <td style={{ padding: "0.6rem", fontSize: "1rem" }}>{t.icon}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem" }}>
                  <strong>{n.title}</strong>
                  {!n.read && <span style={{ marginLeft: "0.5rem", background: COLORS.accent, color: "#fff", borderRadius: 10, padding: "0.05rem 0.4rem", fontSize: "0.65rem" }}>NEW</span>}
                </td>
                <td style={{ padding: "0.6rem", fontSize: "0.85rem", color: "#555" }}>{n.message}</td>
                <td style={{ padding: "0.6rem", fontSize: "0.78rem", color: "#888" }}>{new Date(n.created_at).toLocaleString()}</td>
                <td style={{ padding: "0.6rem" }}>
                  {!n.read && (
                    <button onClick={() => markAsRead(n.id)} style={smallButton(COLORS.info)}>Mark read</button>
                  )}
                </td>
              </tr>
            );
          })}
        </Table>
      )}
    </div>
  );
}