import { useState } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { ArrowLeft, Play, RotateCcw, Trash2, Loader2, AlertCircle } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { StageProgress } from "@/components/StageProgress";
import { LogViewer } from "@/components/LogViewer";
import { VideoPlayer } from "@/components/VideoPlayer";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button, buttonVariants } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { useTask } from "@/hooks/useTask";
import { useI18n } from "@/i18n/index";
import { statusBadgeClass } from "@/lib/status";
import { formatDateTime } from "@/lib/utils";
import { rerunTask, resumeTask, continueTask, deleteTask, redoStage } from "@/api/client";
import type { ExecutionMode } from "@/types";

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { t, statusLabel, stageLabel } = useI18n();
  const { task, log, loading, error } = useTask(id, 2000);

  const [actionLoading, setActionLoading] = useState<string | null>(null);

  if (loading && !task) {
    return (
      <div className="min-h-screen bg-background">
        <AppHeader />
        <main className="mx-auto max-w-6xl px-4 py-6">
          <div className="flex items-center gap-2 text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t.task.loading}
          </div>
        </main>
      </div>
    );
  }

  if (error && !task) {
    return (
      <div className="min-h-screen bg-background">
        <AppHeader />
        <main className="mx-auto max-w-6xl space-y-4 px-4 py-6">
          <BackLink />
          <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
            {t.task.loadError}: {error}
          </div>
        </main>
      </div>
    );
  }

  if (!task) return null;

  const isRunning = task.status === "running";
  const isFailed = task.status === "failed";
  const isPaused = task.status === "paused";
  const isSucceeded = task.status === "succeeded";
  const canRedo = task.executionMode === "manual" && !isRunning;

  const handleAction = async (action: string, fn: () => Promise<unknown>) => {
    setActionLoading(action);
    try {
      await fn();
    } finally {
      setActionLoading(null);
    }
  };

  const handleResume = () => handleAction("resume", () => resumeTask(task.id));
  const handleRerun = () =>
    handleAction("rerun", async () => {
      if (!window.confirm(t.task.rerunTitle)) return;
      await rerunTask(task.id);
    });
  const handleContinue = (mode: ExecutionMode) =>
    handleAction("continue", () => continueTask(task.id, mode));
  const handleDelete = () =>
    handleAction("delete", async () => {
      if (!window.confirm(t.task.deleteTitle)) return;
      await deleteTask(task.id);
      navigate("/");
    });
  const handleRedoStage = (stageName: string) =>
    handleAction(`redo-${stageName}`, async () => {
      if (!window.confirm(t.task.redoStageTitle)) return;
      await redoStage(task.id, stageName);
    });

  return (
    <div className="min-h-screen bg-background">
      <AppHeader />
      <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
        <BackLink />

        {/* Task overview */}
        <Card>
          <CardHeader>
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <CardTitle className="truncate">{task.title || task.url}</CardTitle>
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <Badge className={statusBadgeClass(task.status)}>
                    {statusLabel(task.status)}
                  </Badge>
                  {task.currentStage && (
                    <span>
                      {t.task.stages}: {stageLabel(task.currentStage)}
                    </span>
                  )}
                  <span>{t.task.executionMode}: {task.executionMode}</span>
                </div>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 text-sm sm:grid-cols-2">
              <InfoRow label={t.task.url} value={task.url} />
              <InfoRow label={t.task.taskId} value={task.id} />
              <InfoRow label={t.task.created} value={formatDateTime(task.createdAt)} />
              <InfoRow label={t.task.started} value={formatDateTime(task.startedAt)} />
              <InfoRow label={t.task.completed} value={formatDateTime(task.completedAt)} />
              <InfoRow label={t.task.sourceType} value={task.sourceType || "—"} />
            </div>

            <div className="space-y-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{t.task.progress}</span>
                <span>{Math.round(task.progress)}%</span>
              </div>
              <Progress value={task.progress} />
            </div>

            {task.errorMessage && (
              <div className="flex items-start gap-2 rounded-md bg-destructive/5 p-3 text-sm text-destructive">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{task.errorMessage}</span>
              </div>
            )}

            {/* Action buttons */}
            <div className="flex flex-wrap items-center gap-2 border-t pt-4">
              {isFailed && (
                <Button
                  variant="outline"
                  onClick={handleResume}
                  disabled={actionLoading === "resume"}
                >
                  {actionLoading === "resume" ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Play className="h-4 w-4" />
                  )}
                  {t.task.resumeTask}
                </Button>
              )}
              {isPaused && (
                <>
                  <Button
                    onClick={() => handleContinue("manual")}
                    disabled={actionLoading === "continue"}
                  >
                    {actionLoading === "continue" ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <Play className="h-4 w-4" />
                    )}
                    {t.task.continueTask}
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => handleContinue("auto")}
                    disabled={actionLoading === "continue"}
                  >
                    {t.task.continueAutoTask}
                  </Button>
                </>
              )}
              <Button
                variant="ghost"
                onClick={handleRerun}
                disabled={isRunning || actionLoading === "rerun"}
              >
                {actionLoading === "rerun" ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <RotateCcw className="h-4 w-4" />
                )}
                {t.task.rerunTask}
              </Button>
              <Button
                variant="ghost"
                className="text-destructive hover:text-destructive"
                onClick={handleDelete}
                disabled={isRunning || actionLoading === "delete"}
              >
                {actionLoading === "delete" ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Trash2 className="h-4 w-4" />
                )}
                {t.task.deleteTask}
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* Stages */}
        <StageProgress
          stages={task.stages}
          onRedoStage={handleRedoStage}
          canRedo={canRedo}
        />

        {/* Log */}
        <LogViewer log={log} />

        {/* Final video */}
        {isSucceeded && task.finalVideoPath && <VideoPlayer taskId={task.id} />}
      </main>
    </div>
  );
}

function BackLink() {
  const { t } = useI18n();
  return (
    <Link
      to="/"
      className={buttonVariants({ variant: "ghost", size: "sm" })}
    >
      <ArrowLeft className="h-4 w-4" />
      {t.common.back}
    </Link>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="break-all font-medium">{value}</dd>
    </div>
  );
}
