import { useState, useEffect, useCallback } from "react";
import { analyticsApi } from "./api";

export const useAnalytics = () => {
  const [overall, setOverall] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchOverall = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await analyticsApi.overall();
      setOverall(data);
    } catch (err) {
      setError(err?.response?.data?.detail || err.message || "Failed to fetch analytics");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchOverall();
  }, [fetchOverall]);

  return { overall, loading, error, refreshAnalytics: fetchOverall };
};

export const getOverallAnalytics = () => analyticsApi.overall();
export const getBranchStats = (branch) => analyticsApi.reports({ subject_id: null });
export const getSubjectAttendance = (subjectId, startDate, endDate) =>
  analyticsApi.reports({
    subject_id: subjectId,
    start_date: startDate,
    end_date: endDate,
  });