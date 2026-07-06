import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronLeft, ChevronRight, List, Plus } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { TaskList } from "@/components/TaskList";
import { CreateTaskDialog } from "@/components/CreateTaskDialog";
import { DeleteTaskDialog } from "@/components/DeleteTaskDialog";
import { Button } from "@/components/ui/button";
import { useTasks } from "@/hooks/useTasks";
import { useI18n } from "@/i18n/index";
import { rerunTask, resumeTask, deleteTask } from "@/api/client";
import type { Task } from "@/types";

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

export default function HomePage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [pageSize, setPageSizeState] = useState(10);
  const {
    tasks, total, loading, error, page, totalPages, setPage, refresh,
  } = useTasks(10000, 0, pageSize);

  const handlePageSizeChange = (newSize: number) => {
    setPageSizeState(newSize);
    setPage(0);
  };

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

          {total > pageSize && (
            <div className="flex items-center justify-between gap-4 pt-2">
              <div className="flex items-center gap-3">
                <p className="text-xs text-muted-foreground">
                  {t.common.total}: {total}
                </p>
                <div className="flex items-center gap-1.5">
                  <List className="h-3 w-3 text-muted-foreground" />
                  <select
                    value={pageSize}
                    onChange={(e) => handlePageSizeChange(Number(e.target.value))}
                    className="h-7 rounded-md border border-input bg-background px-2 text-xs ring-offset-background focus:outline-none focus:ring-2 focus:ring-ring"
                  >
                    {PAGE_SIZE_OPTIONS.map((size) => (
                      <option key={size} value={size}>{size}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage(page - 1)}
                >
                  <ChevronLeft className="h-4 w-4" />
                </Button>
                {Array.from({ length: totalPages }, (_, i) => (
                  <Button
                    key={i}
                    variant={i === page ? "default" : "outline"}
                    size="sm"
                    className="min-w-[32px]"
                    onClick={() => setPage(i)}
                  >
                    {i + 1}
                  </Button>
                ))}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= totalPages - 1}
                  onClick={() => setPage(page + 1)}
                >
                  <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
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
