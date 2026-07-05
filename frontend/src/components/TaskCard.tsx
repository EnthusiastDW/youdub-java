import { useState } from "react";
import { Link } from "react-router-dom";
import { Download, Play, RotateCcw, Trash2, ArrowRight, AlertCircle } from "lucide-react";
import type { Task } from "@/types";
import { useI18n } from "@/i18n/index";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { statusBadgeClass } from "@/lib/status";
import { formatDateTime, getYoutubeThumbnailUrl, downloadImage } from "@/lib/utils";

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

  const [thumbError, setThumbError] = useState(false);

  return (
    <Card className="transition-shadow hover:shadow-md">
      <CardContent className="p-3 sm:p-4">
        <div className="flex gap-3 sm:gap-4">
          {/* Left: cover thumbnail */}
          {task.youtubeVideoId && !thumbError && (
            <div className="group relative w-28 shrink-0 self-stretch sm:w-44">
              <img
                src={getYoutubeThumbnailUrl(task.youtubeVideoId)}
                alt=""
                className="h-full w-full rounded-lg object-cover"
                onError={() => setThumbError(true)}
              />
              <button
                onClick={() => downloadImage(getYoutubeThumbnailUrl(task.youtubeVideoId!), "cover.jpg")}
                className="absolute inset-0 flex items-center justify-center rounded-lg bg-black/0 text-white opacity-0 transition-all group-hover:bg-black/30 group-hover:opacity-100"
                title={t.task.downloadCover}
              >
                <Download className="h-5 w-5" />
              </button>
            </div>
          )}
          {/* Right: task info */}
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            {/* Title + badges */}
            <div className="min-w-0 space-y-1">
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

            {/* Progress */}
            <div className="space-y-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{t.task.progress}</span>
                <span>{Math.round(task.progress)}%</span>
              </div>
              <Progress value={task.progress} />
            </div>

            {/* Actions */}
            <div className="flex flex-wrap items-center gap-2">
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

            {/* Notes */}
            {task.notes && (
              <p className="line-clamp-3 break-words text-xs text-muted-foreground" title={task.notes}>
                {task.notes}
              </p>
            )}

            {/* Error (bottom) */}
            {task.errorMessage && (
              <div className="flex items-start gap-2 rounded-md bg-destructive/5 p-2 text-xs text-destructive">
                <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span className="line-clamp-2">{task.errorMessage}</span>
              </div>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
