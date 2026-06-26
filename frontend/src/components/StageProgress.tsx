import { CheckCircle2, Circle, Loader2, XCircle, RotateCcw } from "lucide-react";
import type { TaskStage } from "@/types";
import { useI18n } from "@/i18n/index";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { statusBadgeClass } from "@/lib/status";
import { formatDuration } from "@/lib/utils";

interface StageProgressProps {
  stages: TaskStage[];
  onRedoStage?: (stageName: string) => void;
  canRedo?: boolean;
}

function StageIcon({ status }: { status: TaskStage["status"] }) {
  if (status === "succeeded")
    return <CheckCircle2 className="h-4 w-4 text-success" />;
  if (status === "failed")
    return <XCircle className="h-4 w-4 text-destructive" />;
  if (status === "running")
    return <Loader2 className="h-4 w-4 animate-spin text-primary" />;
  return <Circle className="h-4 w-4 text-muted-foreground" />;
}

export function StageProgress({ stages, onRedoStage, canRedo }: StageProgressProps) {
  const { t, statusLabel, stageLabel } = useI18n();

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t.task.stages}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {stages.map((stage) => (
          <div key={stage.name} className="space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <StageIcon status={stage.status} />
                <span className="text-sm font-medium">
                  {stageLabel(stage.name, stage.label)}
                </span>
                <Badge className={statusBadgeClass(stage.status)}>
                  {statusLabel(stage.status)}
                </Badge>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground">
                  {formatDuration(stage.startedAt, stage.completedAt)}
                </span>
                {canRedo && onRedoStage && stage.status !== "pending" && (
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 px-2 text-xs"
                    onClick={() => onRedoStage(stage.name)}
                  >
                    <RotateCcw className="h-3 w-3" />
                    {t.task.redoStage}
                  </Button>
                )}
              </div>
            </div>

            <Progress value={stage.progress} className="h-1.5" />

            {stage.lastMessage && (
              <p className="text-xs text-muted-foreground">{stage.lastMessage}</p>
            )}

            {stage.errorMessage && (
              <p className="rounded bg-destructive/5 px-2 py-1 text-xs text-destructive">
                {stage.errorMessage}
              </p>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
