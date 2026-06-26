import type { Task } from "@/types";
import { useI18n } from "@/i18n/index";
import { TaskCard } from "./TaskCard";

interface TaskListProps {
  tasks: Task[];
  loading: boolean;
  error: string | null;
  onResume?: (task: Task) => void;
  onRerun?: (task: Task) => void;
  onDelete?: (task: Task) => void;
}

export function TaskList({ tasks, loading, error, onResume, onRerun, onDelete }: TaskListProps) {
  const { t } = useI18n();

  if (loading && tasks.length === 0) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <div
            key={i}
            className="h-32 animate-pulse rounded-lg border bg-muted/40"
            aria-hidden="true"
          />
        ))}
      </div>
    );
  }

  if (error && tasks.length === 0) {
    return (
      <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
        {t.home.loadError}: {error}
      </div>
    );
  }

  if (tasks.length === 0) {
    return (
      <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
        {t.home.empty}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {tasks.map((task) => (
        <TaskCard
          key={task.id}
          task={task}
          onResume={onResume}
          onRerun={onRerun}
          onDelete={onDelete}
        />
      ))}
    </div>
  );
}
