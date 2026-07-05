import { useState, useEffect } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { ArrowLeft, Download, Play, RotateCcw, Trash2, Loader2, AlertCircle, Pencil, Check, X, Copy } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { StageProgress } from "@/components/StageProgress";
import { LogViewer } from "@/components/LogViewer";
import { VideoPlayer } from "@/components/VideoPlayer";
import { DeleteTaskDialog } from "@/components/DeleteTaskDialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button, buttonVariants } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import { useTask } from "@/hooks/useTask";
import { useI18n } from "@/i18n/index";
import { statusBadgeClass } from "@/lib/status";
import { formatDateTime, getYoutubeThumbnailUrl, downloadImage } from "@/lib/utils";
import { rerunTask, resumeTask, continueTask, deleteTask, redoStage, updateTaskNotes, updateTaskYoutubeVideoId, getTaskSummary, updateTaskSummary } from "@/api/client";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { marked } from "marked";
import type { ExecutionMode } from "@/types";

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { t, statusLabel, stageLabel } = useI18n();
  const { task, log, loading, error } = useTask(id, 2000);

  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  // ── Notes edit ──
  const [editingNotes, setEditingNotes] = useState(false);
  const [notesDraft, setNotesDraft] = useState("");
  const [savingNotes, setSavingNotes] = useState(false);

  // ── YouTube Video ID edit ──
  const [editingYoutube, setEditingYoutube] = useState(false);
  const [youtubeDraft, setYoutubeDraft] = useState("");
  const [savingYoutube, setSavingYoutube] = useState(false);

  // ── Summary ──
  const [summary, setSummary] = useState<string | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [editingSummary, setEditingSummary] = useState(false);
  const [summaryDraft, setSummaryDraft] = useState("");
  const [savingSummary, setSavingSummary] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopySummary = async () => {
    if (!summary) return;
    try {
      const html = await marked.parse(summary);
      const div = document.createElement("div");
      div.innerHTML = html;
      await navigator.clipboard.writeText(div.textContent || summary);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard not available */
    }
  };

  useEffect(() => {
    if (!id) return;
    setSummaryLoading(true);
    getTaskSummary(id)
      .then((s) => {
        setSummary(s || null);
        setSummaryDraft(s || "");
      })
      .catch(() => setSummary(null))
      .finally(() => setSummaryLoading(false));
  }, [id]);

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
  const handleDelete = () => setDeleteDialogOpen(true);
  const handleCloseDeleteDialog = () => setDeleteDialogOpen(false);
  const handleConfirmDelete = async () => {
    setActionLoading("delete");
    try {
      await deleteTask(task.id);
      navigate("/");
    } finally {
      setActionLoading(null);
    }
  };
  const handleRedoStage = (stageName: string) =>
    handleAction(`redo-${stageName}`, async () => {
      if (!window.confirm(t.task.redoStageTitle)) return;
      await redoStage(task.id, stageName);
    });

  // ── Notes edit ──
  const startEditNotes = () => {
    setNotesDraft(task.notes || "");
    setEditingNotes(true);
  };

  const cancelEditNotes = () => {
    setEditingNotes(false);
    setNotesDraft("");
  };

  const saveNotes = async () => {
    setSavingNotes(true);
    try {
      await updateTaskNotes(task.id, notesDraft);
      setEditingNotes(false);
    } catch {
      /* ignore */
    } finally {
      setSavingNotes(false);
    }
  };

  // ── YouTube Video ID edit ──
  const startEditYoutube = () => {
    setYoutubeDraft(task.youtubeVideoId || "");
    setEditingYoutube(true);
  };

  const cancelEditYoutube = () => {
    setEditingYoutube(false);
    setYoutubeDraft("");
  };

  const saveYoutube = async () => {
    setSavingYoutube(true);
    try {
      await updateTaskYoutubeVideoId(task.id, youtubeDraft);
      task.youtubeVideoId = youtubeDraft;
      setEditingYoutube(false);
    } catch {
      /* ignore */
    } finally {
      setSavingYoutube(false);
    }
  };

  // ── Summary ──
  const startEditSummary = () => {
    setSummaryDraft(summary || "");
    setEditingSummary(true);
  };

  const cancelEditSummary = () => {
    setEditingSummary(false);
    setSummaryDraft("");
  };

  const saveSummary = async () => {
    setSavingSummary(true);
    try {
      await updateTaskSummary(task.id, summaryDraft);
      setSummary(summaryDraft);
      setEditingSummary(false);
    } catch {
      /* ignore */
    } finally {
      setSavingSummary(false);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <AppHeader />
      <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
        <BackLink />

        {/* Task overview */}
        <Card>
          <CardContent className="p-4 sm:p-6">
            <div className="flex gap-4 sm:gap-6">
              {/* Left: task info */}
              <div className="flex min-w-0 flex-1 flex-col gap-4">
                {/* Title + badges */}
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

                {/* Info rows */}
                <div className="grid gap-4 text-sm sm:grid-cols-2">
                  <InfoRow label={t.task.url} value={task.url} />
                  <InfoRow label={t.task.taskId} value={task.id} />
                  <InfoRow label={t.task.created} value={formatDateTime(task.createdAt)} />
                  <InfoRow label={t.task.started} value={formatDateTime(task.startedAt)} />
                  <InfoRow label={t.task.completed} value={formatDateTime(task.completedAt)} />
                  <InfoRow label={t.task.sourceType} value={task.sourceType || "—"} />
                </div>

              </div>

              {/* Right: cover thumbnail (16:9, not stretching) */}
              {task.youtubeVideoId && (
                <div className="group relative w-48 shrink-0 sm:w-56">
                  <img
                    src={getYoutubeThumbnailUrl(task.youtubeVideoId)}
                    alt="YouTube cover"
                    className="w-full rounded-lg object-cover"
                    style={{ aspectRatio: "16 / 9" }}
                    onError={(e) => { (e.target as HTMLImageElement).style.display = "none"; }}
                  />
                  <button
                    onClick={() => downloadImage(getYoutubeThumbnailUrl(task.youtubeVideoId!), "cover.jpg")}
                    className="absolute inset-0 flex items-center justify-center rounded-lg bg-black/0 text-white opacity-0 transition-all group-hover:bg-black/30 group-hover:opacity-100"
                    style={{ aspectRatio: "16 / 9" }}
                    title={t.task.downloadCover}
                  >
                    <Download className="h-6 w-6" />
                  </button>
                </div>
              )}
            </div>

            {/* Progress (full width) */}
            <div className="space-y-1 pt-2">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{t.task.progress}</span>
                <span>{Math.round(task.progress)}%</span>
              </div>
              <Progress value={task.progress} />
            </div>

            {/* Notes */}
            <div className="space-y-2 border-t pt-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-muted-foreground">{t.task.notes}</span>
                {!editingNotes && (
                  <button
                    onClick={startEditNotes}
                    className="text-muted-foreground hover:text-foreground"
                    title={t.common.edit}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
              {editingNotes ? (
                <div className="space-y-2">
                  <Textarea
                    value={notesDraft}
                    onChange={(e) => setNotesDraft(e.target.value)}
                    rows={3}
                    disabled={savingNotes}
                  />
                  <div className="flex gap-2">
                    <Button size="sm" onClick={saveNotes} disabled={savingNotes}>
                      {savingNotes ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                      {t.common.confirm}
                    </Button>
                    <Button size="sm" variant="outline" onClick={cancelEditNotes} disabled={savingNotes}>
                      <X className="h-3.5 w-3.5" />
                      {t.common.cancel}
                    </Button>
                  </div>
                </div>
              ) : (
                <p className="whitespace-pre-wrap text-sm">{task.notes || <span className="text-muted-foreground italic">{t.task.noNotes}</span>}</p>
              )}
            </div>

            {/* YouTube Video ID */}
            <div className="space-y-2 border-t pt-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-muted-foreground">{t.task.youtubeVideoId}</span>
                {!editingYoutube && (
                  <button
                    onClick={startEditYoutube}
                    className="text-muted-foreground hover:text-foreground"
                    title={t.common.edit}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
              {editingYoutube ? (
                <div className="space-y-2">
                  <Input
                    value={youtubeDraft}
                    onChange={(e) => setYoutubeDraft(e.target.value)}
                    placeholder="dQw4w9WgXcQ"
                    disabled={savingYoutube}
                  />
                  <div className="flex gap-2">
                    <Button size="sm" onClick={saveYoutube} disabled={savingYoutube}>
                      {savingYoutube ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                      {t.common.confirm}
                    </Button>
                    <Button size="sm" variant="outline" onClick={cancelEditYoutube} disabled={savingYoutube}>
                      <X className="h-3.5 w-3.5" />
                      {t.common.cancel}
                    </Button>
                  </div>
                </div>
              ) : (
                <p className="text-sm">{task.youtubeVideoId || <span className="text-muted-foreground italic">{t.task.noYoutubeVideoId}</span>}</p>
              )}
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
                disabled={isRunning}
              >
                <Trash2 className="h-4 w-4" />
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

        {/* Summary */}
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm">{t.task.summary}</CardTitle>
              {summary !== null && !editingSummary && (
                <div className="flex items-center gap-1">
                  <button
                    onClick={handleCopySummary}
                    className="text-muted-foreground hover:text-foreground"
                    title={copied ? "Copied" : "Copy"}
                  >
                    {copied ? <Check className="h-3.5 w-3.5 text-green-500" /> : <Copy className="h-3.5 w-3.5" />}
                  </button>
                  <button
                    onClick={startEditSummary}
                    className="text-muted-foreground hover:text-foreground"
                    title={t.common.edit}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                </div>
              )}
            </div>
          </CardHeader>
          <CardContent>
            {summaryLoading ? (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                {t.common.loading}
              </div>
            ) : editingSummary ? (
              <div className="space-y-2">
                <Textarea
                  value={summaryDraft}
                  onChange={(e) => setSummaryDraft(e.target.value)}
                  rows={6}
                  disabled={savingSummary}
                />
                <div className="flex gap-2">
                  <Button size="sm" onClick={saveSummary} disabled={savingSummary}>
                    {savingSummary ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                    {t.common.confirm}
                  </Button>
                  <Button size="sm" variant="outline" onClick={cancelEditSummary} disabled={savingSummary}>
                    <X className="h-3.5 w-3.5" />
                    {t.common.cancel}
                  </Button>
                </div>
              </div>
            ) : summary ? (
              <div className="prose prose-sm max-w-none dark:prose-invert">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{summary}</ReactMarkdown>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground italic">{t.task.noSummary}</p>
            )}
          </CardContent>
        </Card>

        {/* Log */}
        <LogViewer log={log} />

        {/* Final video */}
        {isSucceeded && task.finalVideoPath && <VideoPlayer taskId={task.id} />}
      </main>

      {task && (
        <DeleteTaskDialog
          open={deleteDialogOpen}
          onClose={handleCloseDeleteDialog}
          task={task}
          onConfirm={handleConfirmDelete}
          loading={actionLoading === "delete"}
        />
      )}
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
