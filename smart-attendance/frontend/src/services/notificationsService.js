import { useState, useEffect, useCallback } from "react";
import { notificationsApi } from "./api";

export const useNotifications = () => {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [userId, setUserId] = useState(null);

  const fetchNotifications = useCallback(async (uid = null, unreadOnly = false) => {
    setLoading(true);
    setError(null);
    try {
      const id = uid || userId;
      const data = id ? await notificationsApi.list(id, unreadOnly) : [];
      setNotifications(data);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load notifications");
    } finally {
      setLoading(false);
    }
  }, [userId]);

  const bindUser = useCallback((id) => {
    setUserId(id);
    fetchNotifications(id);
  }, [fetchNotifications]);

  useEffect(() => {
    if (userId) fetchNotifications(userId);
    else setLoading(false);
  }, [userId, fetchNotifications]);

  const markAsRead = async (notificationId) => {
    await notificationsApi.markRead(notificationId);
    setNotifications((prev) =>
      prev.map((n) => (n.id === notificationId ? { ...n, read: true } : n))
    );
  };

  return { notifications, loading, error, fetchNotifications, markAsRead, bindUser, userId };
};