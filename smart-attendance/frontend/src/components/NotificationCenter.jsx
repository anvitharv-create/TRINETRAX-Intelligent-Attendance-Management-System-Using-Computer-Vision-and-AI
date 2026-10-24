import React, { useState, useEffect } from "react";
import { useAuth } from "../services/authContext";
import { useNotifications } from "../services/notificationsService";
import { COLORS, smallButton } from "../theme";

const toneColors = {
  info: { bg: "#e8f4fd", color: "#0b5e8a", icon: "ℹ️" },
  success: { bg: "#e8f5e9", color: "#1b5e20", icon: "✅" },
  warning: { bg: "#fff3e0", color: "#7a4f01", icon: "⚠️" },
  danger: { bg: "#fee2e2", color: "#c53030", icon: "⛔" },
};

export function NotificationPanel({ onClose }) {
  const { user } = useAuth();
  const { notifications, loading, markAsRead, bindUser } = useNotifications();

  useEffect(() => {
    if (user?.id) bindUser(user.id);
  }, [user?.id, bindUser]);

  const unread = notifications.filter((n) => !n.read).length;

  return (
    <div
      style={{
        position: "fixed",
        top: "4rem",
        right: "1rem",
        width: 340,
        maxWidth: "calc(100vw - 2rem)",
        background: "#fff",
        border: "1px solid #dee2e6",
        borderRadius: "10px",
        boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
        zIndex: 1000,
        overflow: "hidden",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "0.75rem 1rem",
          background: COLORS.primary,
          color: "#fff",
        }}
      >
        <strong style={{ fontSize: "0.9rem" }}>🔔 Notifications {unread > 0 && <span style={{ background: COLORS.accent, borderRadius: 10, padding: "0.1rem 0.5rem", fontSize: "0.7rem" }}>{unread}</span>}</strong>
        <button onClick={onClose} style={{ background: "transparent", border: "none", color: "#fff", cursor: "pointer", fontSize: "1rem" }}>✕</button>
      </div>
      <div style={{ maxHeight: 380, overflowY: "auto" }}>
        {loading && <div style={{ padding: "1rem", textAlign: "center", color: "#888", fontSize: "0.85rem" }}>Loading notifications...</div>}
        {!loading && notifications.length === 0 && (
          <div style={{ padding: "1.5rem", textAlign: "center", color: "#888", fontSize: "0.85rem" }}>No notifications yet</div>
        )}
        {notifications.map((n) => {
          const t = toneColors[n.type] || toneColors.info;
          return (
            <div
              key={n.id}
              onClick={() => markAsRead(n.id)}
              style={{
                padding: "0.75rem 1rem",
                borderBottom: "1px solid #f0f0f0",
                cursor: "pointer",
                background: n.read ? "#fff" : "#f8f9fa",
              }}
            >
              <div style={{ fontSize: "0.8rem", fontWeight: 600, color: "#222" }}>
                {t.icon} {n.title}
                {!n.read && <span style={{ float: "right", width: 8, height: 8, background: COLORS.accent, borderRadius: "50%", display: "inline-block" }} />}
              </div>
              <div style={{ fontSize: "0.78rem", color: "#555", marginTop: "0.2rem" }}>{n.message}</div>
              <div style={{ fontSize: "0.68rem", color: "#999", marginTop: "0.2rem" }}>
                {new Date(n.created_at).toLocaleString()}
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ padding: "0.5rem 1rem", background: "#fafafa", borderTop: "1px solid #eee", textAlign: "right" }}>
        <button onClick={onClose} style={{ ...smallButton(COLORS.primary) }}>Close</button>
      </div>
    </div>
  );
}

export default NotificationPanel;