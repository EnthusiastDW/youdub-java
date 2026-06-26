import { Download } from "lucide-react";
import { useI18n } from "@/i18n/index";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { buttonVariants } from "@/components/ui/button";
import { finalVideoUrl, finalVideoDownloadUrl } from "@/api/client";

interface VideoPlayerProps {
  taskId: string;
}

export function VideoPlayer({ taskId }: VideoPlayerProps) {
  const { t } = useI18n();

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>{t.task.finalVideo}</CardTitle>
          <a
            href={finalVideoDownloadUrl(taskId)}
            download
            className={buttonVariants({ size: "sm", variant: "outline" })}
          >
            <Download className="h-3.5 w-3.5" />
            {t.task.download}
          </a>
        </div>
      </CardHeader>
      <CardContent>
        <video
          src={finalVideoUrl(taskId)}
          controls
          className="w-full rounded-md bg-black"
          preload="metadata"
        >
          <track kind="captions" />
        </video>
      </CardContent>
    </Card>
  );
}
