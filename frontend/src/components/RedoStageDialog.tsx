import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/index";
import { Loader2, RotateCcw, AlertCircle } from "lucide-react";

interface RedoStageDialogProps {
  open: boolean;
  onClose: () => void;
  stageName: string;
  stageLabel: string;
  onConfirm: () => Promise<void>;
  loading: boolean;
}

export function RedoStageDialog({ open, onClose, stageName, stageLabel, onConfirm, loading }: RedoStageDialogProps) {
  const { t } = useI18n();

  return (
    <Dialog open={open} onClose={onClose}>
      <div className="space-y-4">
        <div className="flex items-start gap-2">
          <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
          <div className="space-y-1 text-sm">
            <p className="font-medium">{t.task.redoStageTitle}</p>
            <p className="text-muted-foreground">{t.task.redoStageDescription} <strong>{stageLabel}</strong></p>
            <ul className="list-inside list-disc space-y-0.5 text-xs text-muted-foreground">
              <li>{t.task.redoStageHelp}</li>
            </ul>
          </div>
        </div>

        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" onClick={onClose} disabled={loading}>
            {t.common.cancel}
          </Button>
          <Button
            size="sm"
            variant="default"
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <RotateCcw className="h-3.5 w-3.5" />
            )}
            {t.task.confirmRedoStage}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
