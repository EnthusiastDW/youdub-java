import { useEffect, useRef, useState } from "react";
import { getTask, getTaskLog } from "@/api/client";
import type { Task } from "@/types";

interface UseTaskResult {
  task: Task | null;
  log: string;
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

export function useTask(taskId: string | undefined, pollIntervalMs = 2000): UseTaskResult {
  const [task, setTask] = useState<Task | null>(null);
  const [log, setLog] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);
  const logOffsetRef = useRef(0);

  const load = async () => {
    if (!taskId) return;
    try {
      const [taskData, logData] = await Promise.all([
        getTask(taskId),
        getTaskLog(taskId, logOffsetRef.current).catch(() => ""),
      ]);
      if (!mountedRef.current) return;
      setTask(taskData);
      if (logData) {
        setLog((prev) => prev + logData);
        logOffsetRef.current += new Blob([logData]).size;
      }
      setError(null);
    } catch (err) {
      if (!mountedRef.current) return;
      setError(err instanceof Error ? err.message : "Failed to load task");
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    logOffsetRef.current = 0;
    setLog("");
    load();
    const interval = window.setInterval(load, pollIntervalMs);
    return () => {
      mountedRef.current = false;
      clearInterval(interval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, pollIntervalMs]);

  return { task, log, loading, error, refresh: load };
}
