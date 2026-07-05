import { useEffect, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { useI18n } from "@/i18n/index";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { createTask, getSettings, uploadLocalTask } from "@/api/client";
import { extractYoutubeVideoId, getYoutubeThumbnailUrl } from "@/lib/utils";
import type { ExecutionMode, LocalDirection } from "@/types";

type CreateMode = "url" | "upload";

interface CreateTaskDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (taskId: string) => void;
}

function YoutubeThumbnail({ videoId }: { videoId: string }) {
  const [loaded, setLoaded] = useState(true);
  const [failed, setFailed] = useState(false);

  if (!videoId || failed) return null;

  return (
    <div className="relative overflow-hidden rounded-md border">
      {!loaded && (
        <div className="flex h-32 items-center justify-center bg-muted text-xs text-muted-foreground">
          <Loader2 className="mr-1 h-3 w-3 animate-spin" />
          Loading cover...
        </div>
      )}
      <img
        src={getYoutubeThumbnailUrl(videoId)}
        alt="YouTube cover"
        className={`w-full object-cover ${loaded ? "block" : "hidden"}`}
        style={{ maxHeight: 180 }}
        onLoad={() => setLoaded(true)}
        onError={() => setFailed(true)}
      />
    </div>
  );
}

export function CreateTaskDialog({ open, onClose, onCreated }: CreateTaskDialogProps) {
  const { t } = useI18n();
  const [mode, setMode] = useState<CreateMode>("url");

  // URL fields
  const [url, setUrl] = useState("");

  // Upload fields
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const [subtitleFile, setSubtitleFile] = useState<File | null>(null);
  const [direction, setDirection] = useState<LocalDirection>("en-zh");

  // YouTube cover fields (upload tab)
  const [youtubeUrl, setYoutubeUrl] = useState("");

  // Shared fields
  const [executionMode, setExecutionMode] = useState<ExecutionMode>("auto");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const videoInputRef = useRef<HTMLInputElement>(null);
  const subtitleInputRef = useRef<HTMLInputElement>(null);

  // Derived: detected YouTube video IDs
  const urlVideoId = extractYoutubeVideoId(url);
  const uploadYoutubeVideoId = extractYoutubeVideoId(youtubeUrl);

  // Load notes template when dialog opens
  useEffect(() => {
    if (!open) return;
    setUrl("");
    setVideoFile(null);
    setSubtitleFile(null);
    setDirection("en-zh");
    setExecutionMode("auto");
    setNotes("");
    setError(null);
    setYoutubeUrl("");
    setMode("url");
    getSettings()
      .then((data) => {
        if (data.notesTemplate) setNotes(data.notesTemplate);
      })
      .catch(() => {});
  }, [open]);

  const handleSubmit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      if (mode === "url") {
        const trimmed = url.trim();
        if (!trimmed) return;
        const task = await createTask(trimmed, executionMode, notes || undefined, urlVideoId || undefined);
        setUrl("");
        onClose();
        onCreated(task.id);
      } else {
        if (!videoFile) return;
        const task = await uploadLocalTask(
          videoFile, executionMode, direction, subtitleFile,
          uploadYoutubeVideoId || undefined
        );
        setVideoFile(null);
        setSubtitleFile(null);
        if (videoInputRef.current) videoInputRef.current.value = "";
        if (subtitleInputRef.current) subtitleInputRef.current.value = "";
        onClose();
        onCreated(task.id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t.home.createError);
    } finally {
      setSubmitting(false);
    }
  };

  const canSubmit = mode === "url" ? url.trim() !== "" : videoFile !== null;

  return (
    <Dialog open={open} onClose={onClose} title={t.home.createTitle}>
      <div className="space-y-4">
        {/* Tab switcher */}
        <div className="flex rounded-lg border bg-muted p-1">
          <button
            onClick={() => setMode("url")}
            className={`flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
              mode === "url" ? "bg-background shadow-sm" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {t.home.urlTab}
          </button>
          <button
            onClick={() => setMode("upload")}
            className={`flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
              mode === "upload" ? "bg-background shadow-sm" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {t.home.uploadTab}
          </button>
        </div>

        {/* URL form */}
        {mode === "url" && (
          <div className="space-y-2">
            <Label htmlFor="task-url">{t.home.urlLabel}</Label>
            <Input
              id="task-url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={t.home.urlPlaceholder}
              disabled={submitting}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !submitting && canSubmit) handleSubmit();
              }}
            />
            {urlVideoId && (
              <div className="space-y-1 pt-1">
                <YoutubeThumbnail videoId={urlVideoId} />
                <p className="text-xs text-muted-foreground">
                  {t.home.youtubeCoverLabel} ✓
                </p>
              </div>
            )}
          </div>
        )}

        {/* Upload form */}
        {mode === "upload" && (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>{t.home.localVideoLabel}</Label>
              <input
                ref={videoInputRef}
                type="file"
                accept="video/*"
                onChange={(e) => setVideoFile(e.target.files?.[0] ?? null)}
                disabled={submitting}
                className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-2 file:text-sm file:font-medium file:text-primary-foreground hover:file:bg-primary/90"
              />
            </div>

            <div className="space-y-2">
              <Label>{t.home.localDirectionLabel}</Label>
              <Select
                value={direction}
                onChange={(e) => setDirection(e.target.value as LocalDirection)}
                disabled={submitting}
              >
                <option value="en-zh">{t.home.localEnZh}</option>
                <option value="zh-en">{t.home.localZhEn}</option>
              </Select>
            </div>

            {/* YouTube URL for cover (optional) */}
            <div className="space-y-2">
              <Label>{t.home.youtubeUrlLabel}</Label>
              <Input
                value={youtubeUrl}
                onChange={(e) => setYoutubeUrl(e.target.value)}
                placeholder={t.home.youtubeUrlPlaceholder}
                disabled={submitting}
              />
              {uploadYoutubeVideoId && (
                <div className="space-y-1 pt-1">
                  <YoutubeThumbnail videoId={uploadYoutubeVideoId} />
                  <p className="text-xs text-muted-foreground">
                    {t.home.youtubeCoverLabel} ✓
                  </p>
                </div>
              )}
            </div>

            <div className="space-y-2">
              <Label>{t.home.localSubtitleLabel}</Label>
              <input
                ref={subtitleInputRef}
                type="file"
                accept=".srt,.vtt,.txt"
                onChange={(e) => setSubtitleFile(e.target.files?.[0] ?? null)}
                disabled={submitting}
                className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-secondary file:px-3 file:py-2 file:text-sm file:font-medium file:text-secondary-foreground hover:file:bg-secondary/80"
              />
              <p className="text-xs text-muted-foreground">{t.home.localSubtitleHelp}</p>
            </div>
          </div>
        )}

        {/* Execution mode (shared) */}
        <div className="space-y-2">
          <Label htmlFor="task-mode">{t.home.executionModeLabel}</Label>
          <Select
            id="task-mode"
            value={executionMode}
            onChange={(e) => setExecutionMode(e.target.value as ExecutionMode)}
            disabled={submitting}
          >
            <option value="auto">{t.home.executionAuto}</option>
            <option value="manual">{t.home.executionManual}</option>
          </Select>
        </div>

        {/* Notes (shared) */}
        <div className="space-y-2">
          <Label htmlFor="task-notes">{t.home.notesLabel}</Label>
          <Textarea
            id="task-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder={t.home.notesPlaceholder}
            disabled={submitting}
            rows={3}
          />
        </div>

        {error && (
          <p className="text-sm text-destructive">{error}</p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            {t.common.cancel}
          </Button>
          <Button onClick={handleSubmit} disabled={submitting || !canSubmit}>
            {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
            {submitting
              ? t.home.submitting
              : mode === "upload"
                ? t.home.uploadTask
                : t.home.createTask}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
