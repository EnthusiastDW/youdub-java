import { useCallback, useEffect, useRef, useState } from "react";
import { listTasks } from "@/api/client";
import type { Task } from "@/types";

interface UseTasksResult {
  tasks: Task[];
  total: number;
  loading: boolean;
  error: string | null;
  page: number;
  pageSize: number;
  totalPages: number;
  setPage: (page: number) => void;
  refresh: () => void;
}

export function useTasks(
  pollIntervalMs = 2000,
  initialPage = 0,
  pageSize = 10
): UseTasksResult {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPageState] = useState(initialPage);
  const mountedRef = useRef(true);
  const pageRef = useRef(page);
  pageRef.current = page;

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const load = useCallback(async () => {
    const currentPage = pageRef.current;
    try {
      const data = await listTasks(currentPage * pageSize, pageSize);
      if (!mountedRef.current) return;
      setTasks(data.tasks);
      setTotal(data.total);
      setError(null);
    } catch (err) {
      if (!mountedRef.current) return;
      setError(err instanceof Error ? err.message : "Failed to load tasks");
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, [pageSize]);

  const setPage = useCallback((p: number) => {
    setPageState(p);
    setLoading(true);
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    load();
    const interval = window.setInterval(load, pollIntervalMs);
    return () => {
      mountedRef.current = false;
      clearInterval(interval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pollIntervalMs]);

  useEffect(() => {
    load();
  }, [page, load]);

  return {
    tasks,
    total,
    loading,
    error,
    page,
    pageSize,
    totalPages,
    setPage,
    refresh: load,
  };
}
