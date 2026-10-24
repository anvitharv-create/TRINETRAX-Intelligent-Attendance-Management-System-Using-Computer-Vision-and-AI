import { useState, useCallback } from "react";
import { studentsApi } from "./api";

export const useStudents = () => {
  const [students, setStudents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchStudents = useCallback(async (branch = null, semester = null, section = null) => {
    setLoading(true);
    setError(null);
    try {
      const data = await studentsApi.list({ branch, semester, section });
      setStudents(data);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load students");
    } finally {
      setLoading(false);
    }
  }, []);

  const addStudent = async (studentData) => {
    const newStudent = await studentsApi.create(studentData);
    setStudents((prev) => [...prev, newStudent]);
    return newStudent;
  };

  const removeStudent = async (studentId) => {
    await studentsApi.remove(studentId);
    setStudents((prev) => prev.filter((s) => s.id !== studentId));
  };

  return { students, loading, error, fetchStudents, addStudent, removeStudent };
};