import { Link } from "react-router-dom";
import { Play, RotateCcw, Trash2, ArrowRight, AlertCircle } from "lucide-react";
import type { Task } from "@/types";
import { useI18n } from "@/i18n/index";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { statusBadgeClass } from "@/lib/status";
import { formatDateTime } from "@/lib/utils";

interface TaskCardProps {
  task: Task;
  onResume?: (task: Task) => void;
  onRerun?: (task: Task) => void;
  onDelete?: (task: Task) => void;
}

export function TaskCard({ task, onResume, onRerun, onDelete }: TaskCardProps) {
  const { t, statusLabel, stageLabel } = useI18n();
  const isRunning = task.status === "running";
  const isFailed = task.status === "failed";

  return (
    <Card className="overflow-hidden transition-shadow hover:shadow-md">
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1 space-y-1">
            <Link
              to={`/tasks/${task.id}`}
              className="block truncate font-medium hover:text-primary"
              title={task.title || task.url}
            >
              {task.title || task.url}
            </Link>
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <Badge className={statusBadgeClass(task.status)}>
                {statusLabel(task.status)}
              </Badge>
              {task.currentStage && (
                <span>
                  {t.task.stages}: {stageLabel(task.currentStage)}
                </span>
              )}
              <span>{formatDateTime(task.createdAt)}</span>
            </div>
          </div>
        </div>

        <div className="mt-3 space-y-1">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>{t.task.progress}</span>
            <span>{Math.round(task.progress)}%</span>
          </div>
          <Progress value={task.progress} />
        </div>

        {task.errorMessage && (
          <div className="mt-3 flex items-start gap-2 rounded-md bg-destructive/5 p-2 text-xs text-destructive">
            <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span className="line-clamp-2">{task.errorMessage}</span>
          </div>
        )}

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Link
            to={`/tasks/${task.id}`}
            className={buttonVariants({ size: "sm", variant: "outline" })}
          >
            {t.task.overview}
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
          {isFailed && onResume && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => onResume(task)}
              disabled={isRunning}
            >
              <Play className="h-3.5 w-3.5" />
              {t.task.resumeTask}
            </Button>
          )}
          {onRerun && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => onRerun(task)}
              disabled={isRunning}
            >
              <RotateCcw className="h-3.5 w-3.5" />
              {t.task.rerunTask}
            </Button>
          )}
          {onDelete && (
            <Button
              size="sm"
              variant="ghost"
              className="text-destructive hover:text-destructive"
              onClick={() => onDelete(task)}
              disabled={isRunning}
            >
              <Trash2 className="h-3.5 w-3.5" />
              {t.task.deleteTask}
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
