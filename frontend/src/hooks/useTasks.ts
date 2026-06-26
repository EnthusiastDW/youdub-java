import { useEffect, useRef, useState } from "react";
import { listTasks } from "@/api/client";
import type { Task } from "@/types";

interface UseTasksResult {
  tasks: Task[];
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

export function useTasks(pollIntervalMs = 2000): UseTasksResult {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  const load = async () => {
    try {
      const data = await listTasks();
      if (!mountedRef.current) return;
      setTasks(data.tasks);
      setError(null);
    } catch (err) {
      if (!mountedRef.current) return;
      setError(err instanceof Error ? err.message : "Failed to load tasks");
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

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

  return { tasks, loading, error, refresh: load };
}
