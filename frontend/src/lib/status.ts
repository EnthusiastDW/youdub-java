import type { BadgeProps } from "@/components/ui/badge";

export function statusBadgeVariant(status?: string | null): BadgeProps["variant"] {
  if (status === "succeeded") return "success";
  if (status === "failed") return "destructive";
  if (status === "running") return "default";
  if (status === "paused") return "warning";
  if (status === "cancelled") return "secondary";
  return "secondary";
}

export function statusBadgeClass(status?: string | null): string {
  if (status === "succeeded") return "bg-success/10 text-success border-transparent";
  if (status === "failed") return "bg-destructive/10 text-destructive border-transparent";
  if (status === "running") return "bg-primary/15 text-primary border-transparent";
  if (status === "paused") return "bg-warning/15 text-warning border-transparent";
  if (status === "queued") return "bg-muted text-muted-foreground border-border";
  if (status === "cancelled") return "bg-muted text-muted-foreground border-border";
  if (status === "pending") return "bg-muted text-muted-foreground border-border";
  return "bg-background text-foreground border-border";
}
