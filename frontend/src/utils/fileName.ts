export function coverDownloadName(title: string | undefined): string {
    return (title || "cover").replace(/[\\/:*?"<>|]/g, "_") + "-封面.jpg";
}
