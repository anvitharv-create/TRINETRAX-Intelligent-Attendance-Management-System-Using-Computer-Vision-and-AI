import { useState, useEffect, useCallback } from "react";
import { teachersApi } from "./api";

export const useTeachers = () => {
  const [teachers, setTeachers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchTeachers = useCallback(async (branch = null) => {
    setLoading(true);
    setError(null);
    try {
      const data = await teachersApi.list(branch);
      setTeachers(data);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load teachers");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTeachers();
  }, [fetchTeachers]);

  const addTeacher = async (teacherData) => {
    const newTeacher = await teachersApi.create(teacherData);
    setTeachers((prev) => [...prev, newTeacher]);
    return newTeacher;
  };

  const removeTeacher = async (teacherId) => {
    await teachersApi.remove(teacherId);
    setTeachers((prev) => prev.filter((t) => t.id !== teacherId));
  };

  return { teachers, loading, error, fetchTeachers, addTeacher, removeTeacher };
};