import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate()
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function formatDuration(startedAt: string | null, completedAt: string | null): string {
  if (!startedAt) return "—";
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "—";
  const seconds = Math.floor((end - start) / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes < 60) return `${minutes}m ${rest}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

export function formatFileSize(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(
    units.length - 1,
    Math.floor(Math.log(bytes) / Math.log(1024))
  );
  return `${(bytes / Math.pow(1024, index)).toFixed(1)} ${units[index]}`;
}

/**
 * 从 YouTube URL 中提取视频 ID。
 * 支持格式：
 *   https://www.youtube.com/watch?v=VIDEO_ID
 *   https://youtu.be/VIDEO_ID
 *   https://www.youtube.com/embed/VIDEO_ID
 *   https://www.youtube.com/shorts/VIDEO_ID
 *   直接传入 VIDEO_ID（11 位字符）
 */
export function extractYoutubeVideoId(input: string): string | null {
  if (!input) return null;

  // 直接是 11 位视频 ID
  if (/^[A-Za-z0-9_-]{11}$/.test(input.trim())) {
    return input.trim();
  }

  try {
    const url = new URL(input.trim());
    if (url.hostname.includes("youtube.com") || url.hostname.includes("youtu.be")) {
      // youtu.be/VIDEO_ID
      if (url.hostname === "youtu.be") {
        const id = url.pathname.slice(1).split("/")[0];
        if (/^[A-Za-z0-9_-]{11}$/.test(id)) return id;
      }
      // youtube.com/watch?v=VIDEO_ID
      const v = url.searchParams.get("v");
      if (v && /^[A-Za-z0-9_-]{11}$/.test(v)) return v;
      // youtube.com/embed/VIDEO_ID 或 /shorts/VIDEO_ID
      const match = url.pathname.match(/\/(embed|shorts)\/([A-Za-z0-9_-]{11})/);
      if (match) return match[2];
    }
  } catch {
    // 不是合法 URL
  }
  return null;
}

/**
 * 根据 YouTube 视频 ID 获取封面图片 URL。
 * 优先使用 maxresdefault，失败时可降级到 hqdefault。
 */
export function getYoutubeThumbnailUrl(videoId: string): string {
  return `https://img.youtube.com/vi/${videoId}/maxresdefault.jpg`;
}
