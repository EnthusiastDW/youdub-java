import type { Task } from "@/types";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/index";
import { Loader2, Trash2, AlertCircle } from "lucide-react";

interface DeleteTaskDialogProps {
  open: boolean;
  onClose: () => void;
  task: Task;
  onConfirm: () => Promise<void>;
  loading: boolean;
}

export function DeleteTaskDialog({ open, onClose, task, onConfirm, loading }: DeleteTaskDialogProps) {
  const { t } = useI18n();

  return (
    <Dialog open={open} onClose={onClose}>
      <div className="space-y-4">
        {/* Warning section */}
        <div className="flex items-start gap-2">
          <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
          <div className="space-y-1 text-sm">
            <p className="font-medium text-destructive">{t.task.deleteTitle}</p>
            <p className="text-muted-foreground">{t.task.deleteDescription}</p>
            <ul className="list-inside list-disc space-y-0.5 text-xs text-muted-foreground">
              <li>{t.task.deleteItemDb}</li>
              <li>{t.task.deleteItemSession}</li>
              <li>{t.task.deleteItemUpload}</li>
              <li>{t.task.deleteItemLog}</li>
            </ul>
            <p className="pt-1 font-mono text-xs text-muted-foreground/70">ID: {task.id}</p>
          </div>
        </div>

        {/* Action buttons */}
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" onClick={onClose} disabled={loading}>
            {t.common.cancel}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Trash2 className="h-3.5 w-3.5" />
            )}
            {t.task.confirmDelete}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
