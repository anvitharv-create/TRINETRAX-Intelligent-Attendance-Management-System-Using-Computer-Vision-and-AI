import { useState, useCallback } from "react";
import { attendanceApi } from "./api";

export const useAttendance = () => {
  const [attendance, setAttendance] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchAttendance = useCallback(async (subject_id = null, date = null) => {
    setLoading(true);
    setError(null);
    try {
      const data = await attendanceApi.list(subject_id, date);
      setAttendance(data);
      return data;
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load attendance");
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  const captureAttendance = async (data) => {
    const result = await attendanceApi.capture(data);
    return result;
  };

  const getStudentAttendance = async (student_id) => {
    setLoading(true);
    setError(null);
    try {
      const data = await attendanceApi.student(student_id);
      setAttendance(data);
      return data;
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load attendance");
      return [];
    } finally {
      setLoading(false);
    }
  };

  const updateAttendance = async (recordId, status, confidence) => {
    const updated = await attendanceApi.update(recordId, status, confidence);
    setAttendance((prev) => prev.map((r) => (r.id === recordId ? updated : r)));
    return updated;
  };

  const removeAttendance = async (recordId) => {
    await attendanceApi.remove(recordId);
    setAttendance((prev) => prev.filter((r) => r.id !== recordId));
  };

  return {
    attendance,
    loading,
    error,
    fetchAttendance,
    captureAttendance,
    getStudentAttendance,
    updateAttendance,
    removeAttendance,
  };
};