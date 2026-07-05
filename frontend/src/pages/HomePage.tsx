import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Plus } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { TaskList } from "@/components/TaskList";
import { CreateTaskDialog } from "@/components/CreateTaskDialog";
import { DeleteTaskDialog } from "@/components/DeleteTaskDialog";
import { Button } from "@/components/ui/button";
import { useTasks } from "@/hooks/useTasks";
import { useI18n } from "@/i18n/index";
import { rerunTask, resumeTask, deleteTask } from "@/api/client";
import type { Task } from "@/types";

export default function HomePage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { tasks, loading, error, refresh } = useTasks(2000);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Task | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const handleCreated = (taskId: string) => {
    navigate(`/tasks/${taskId}`);
  };

  const handleResume = async (task: Task) => {
    try {
      await resumeTask(task.id);
      refresh();
    } catch {
      // ignore
    }
  };

  const handleRerun = async (task: Task) => {
    try {
      await rerunTask(task.id);
      refresh();
    } catch {
      // ignore
    }
  };

  const handleDelete = (task: Task) => setDeleteTarget(task);
  const handleCloseDeleteDialog = () => {
    setDeleteTarget(null);
    setDeleteLoading(false);
  };
  const handleConfirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleteLoading(true);
    try {
      await deleteTask(deleteTarget.id);
      setDeleteTarget(null);
      refresh();
    } catch {
      // ignore
    } finally {
      setDeleteLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <AppHeader />

      <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
        <div className="flex items-start justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-2xl font-bold tracking-tight">{t.home.title}</h1>
            <p className="text-sm text-muted-foreground">{t.home.subtitle}</p>
          </div>
          <Button onClick={() => setDialogOpen(true)} size="sm" className="shrink-0">
            <Plus className="h-4 w-4" />
            {t.home.createTask}
          </Button>
        </div>

        {/* Task list */}
        <div className="space-y-3">
          <h2 className="text-lg font-semibold">{t.home.taskHistory}</h2>
          <TaskList
            tasks={tasks}
            loading={loading}
            error={error}
            onResume={handleResume}
            onRerun={handleRerun}
            onDelete={handleDelete}
          />
        </div>
      </main>

      <CreateTaskDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={handleCreated}
      />

      {deleteTarget && (
        <DeleteTaskDialog
          open={deleteTarget !== null}
          onClose={handleCloseDeleteDialog}
          task={deleteTarget}
          onConfirm={handleConfirmDelete}
          loading={deleteLoading}
        />
      )}
    </div>
  );
}
