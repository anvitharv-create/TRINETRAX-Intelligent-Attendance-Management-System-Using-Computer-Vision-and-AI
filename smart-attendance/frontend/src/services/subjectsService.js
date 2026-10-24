import { useState, useEffect, useCallback } from "react";
import { subjectsApi } from "./api";

export const useSubjects = () => {
  const [subjects, setSubjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchSubjects = useCallback(async (branch = null, semester = null) => {
    setLoading(true);
    setError(null);
    try {
      const data = await subjectsApi.list(branch, semester);
      setSubjects(data);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to load subjects");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSubjects();
  }, [fetchSubjects]);

  const addSubject = async (subjectData) => {
    const newSubject = await subjectsApi.create(subjectData);
    setSubjects((prev) => [...prev, newSubject]);
    return newSubject;
  };

  const removeSubject = async (subjectId) => {
    await subjectsApi.remove(subjectId);
    setSubjects((prev) => prev.filter((s) => s.id !== subjectId));
  };

  return { subjects, loading, error, fetchSubjects, addSubject, removeSubject };
};