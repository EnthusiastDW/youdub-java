import { useState } from "react";
import { Loader2 } from "lucide-react";
import { useI18n } from "@/i18n/index";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { createTask } from "@/api/client";
import type { ExecutionMode } from "@/types";

interface CreateTaskDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (taskId: string) => void;
}

export function CreateTaskDialog({ open, onClose, onCreated }: CreateTaskDialogProps) {
  const { t } = useI18n();
  const [url, setUrl] = useState("");
  const [executionMode, setExecutionMode] = useState<ExecutionMode>("auto");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    const trimmed = url.trim();
    if (!trimmed) return;
    setSubmitting(true);
    setError(null);
    try {
      const task = await createTask(trimmed, executionMode);
      setUrl("");
      onClose();
      onCreated(task.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.home.createError);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t.home.createTitle}
    >
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="task-url">{t.home.urlLabel}</Label>
          <Input
            id="task-url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder={t.home.urlPlaceholder}
            disabled={submitting}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !submitting) handleSubmit();
            }}
          />
        </div>

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

        {error && (
          <p className="text-sm text-destructive">{error}</p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            {t.common.cancel}
          </Button>
          <Button onClick={handleSubmit} disabled={submitting || !url.trim()}>
            {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
            {submitting ? t.home.submitting : t.home.createTask}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
