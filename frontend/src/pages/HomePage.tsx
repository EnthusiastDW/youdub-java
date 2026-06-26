import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Plus, Upload, Loader2 } from "lucide-react";
import { AppHeader } from "@/components/AppHeader";
import { TaskList } from "@/components/TaskList";
import { CreateTaskDialog } from "@/components/CreateTaskDialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { useTasks } from "@/hooks/useTasks";
import { useI18n } from "@/i18n/index";
import { uploadLocalTask, rerunTask, resumeTask, deleteTask } from "@/api/client";
import type { ExecutionMode, LocalDirection, Task } from "@/types";

export default function HomePage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { tasks, loading, error, refresh } = useTasks(2000);

  const [dialogOpen, setDialogOpen] = useState(false);

  // Upload state
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const [subtitleFile, setSubtitleFile] = useState<File | null>(null);
  const [direction, setDirection] = useState<LocalDirection>("en-zh");
  const [uploadMode, setUploadMode] = useState<ExecutionMode>("auto");
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const videoInputRef = useRef<HTMLInputElement>(null);
  const subtitleInputRef = useRef<HTMLInputElement>(null);

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

  const handleDelete = async (task: Task) => {
    if (!window.confirm(t.task.deleteTitle)) return;
    try {
      await deleteTask(task.id);
      refresh();
    } catch {
      // ignore
    }
  };

  const handleUpload = async () => {
    if (!videoFile) return;
    setUploading(true);
    setUploadError(null);
    try {
      const task = await uploadLocalTask(videoFile, uploadMode, direction, subtitleFile);
      setVideoFile(null);
      setSubtitleFile(null);
      if (videoInputRef.current) videoInputRef.current.value = "";
      if (subtitleInputRef.current) subtitleInputRef.current.value = "";
      navigate(`/tasks/${task.id}`);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : t.home.uploadError);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <AppHeader />

      <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
        <div className="space-y-2">
          <h1 className="text-2xl font-bold tracking-tight">{t.home.title}</h1>
          <p className="text-sm text-muted-foreground">{t.home.subtitle}</p>
        </div>

        <div className="grid gap-6 md:grid-cols-2">
          {/* URL Create */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Plus className="h-5 w-5" />
                {t.home.createTitle}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <CreateTaskButton
                onOpen={() => setDialogOpen(true)}
              />
            </CardContent>
          </Card>

          {/* Upload */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Upload className="h-5 w-5" />
                {t.home.uploadTitle}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>{t.home.localVideoLabel}</Label>
                <input
                  ref={videoInputRef}
                  type="file"
                  accept="video/*"
                  onChange={(e) => setVideoFile(e.target.files?.[0] ?? null)}
                  className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-2 file:text-sm file:font-medium file:text-primary-foreground hover:file:bg-primary/90"
                />
              </div>

              <div className="space-y-2">
                <Label>{t.home.localDirectionLabel}</Label>
                <Select
                  value={direction}
                  onChange={(e) => setDirection(e.target.value as LocalDirection)}
                  disabled={uploading}
                >
                  <option value="en-zh">{t.home.localEnZh}</option>
                  <option value="zh-en">{t.home.localZhEn}</option>
                </Select>
              </div>

              <div className="space-y-2">
                <Label>{t.home.localSubtitleLabel}</Label>
                <input
                  ref={subtitleInputRef}
                  type="file"
                  accept=".srt,.vtt,.txt"
                  onChange={(e) => setSubtitleFile(e.target.files?.[0] ?? null)}
                  className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-secondary file:px-3 file:py-2 file:text-sm file:font-medium file:text-secondary-foreground hover:file:bg-secondary/80"
                />
                <p className="text-xs text-muted-foreground">{t.home.localSubtitleHelp}</p>
              </div>

              <div className="space-y-2">
                <Label>{t.home.executionModeLabel}</Label>
                <Select
                  value={uploadMode}
                  onChange={(e) => setUploadMode(e.target.value as ExecutionMode)}
                  disabled={uploading}
                >
                  <option value="auto">{t.home.executionAuto}</option>
                  <option value="manual">{t.home.executionManual}</option>
                </Select>
              </div>

              {uploadError && (
                <p className="text-sm text-destructive">{uploadError}</p>
              )}

              <Button
                onClick={handleUpload}
                disabled={uploading || !videoFile}
                className="w-full"
              >
                {uploading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Upload className="h-4 w-4" />
                )}
                {uploading ? t.home.uploading : t.home.uploadTask}
              </Button>
            </CardContent>
          </Card>
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
    </div>
  );
}

function CreateTaskButton({ onOpen }: { onOpen: () => void }) {
  const { t } = useI18n();
  return (
    <Button onClick={onOpen} className="w-full">
      <Plus className="h-4 w-4" />
      {t.home.createTask}
    </Button>
  );
}
