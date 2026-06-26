import { useEffect, useRef } from "react";
import { useI18n } from "@/i18n/index";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface LogViewerProps {
  log: string;
  className?: string;
}

export function LogViewer({ log, className }: LogViewerProps) {
  const { t } = useI18n();
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [log]);

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>{t.task.runLog}</CardTitle>
      </CardHeader>
      <CardContent>
        <div
          ref={containerRef}
          className="scrollbar-thin h-96 overflow-auto rounded-md bg-zinc-950 p-4 font-mono text-xs leading-relaxed text-zinc-300"
        >
          {log ? (
            <pre className="whitespace-pre-wrap break-words">{log}</pre>
          ) : (
            <span className="text-zinc-500">{t.task.emptyLog}</span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
