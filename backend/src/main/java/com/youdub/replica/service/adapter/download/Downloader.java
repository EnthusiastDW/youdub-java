package com.youdub.replica.service.adapter.download;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 视频下载适配器接口。
 * 原方案：YtDlpDownloader（通过 yt-dlp 子进程下载 YouTube/Bilibili 视频）
 * 替代方案：LocalFileDownloader（处理本地上传文件）
 */
public interface Downloader {
    String getName();
    void download(Task task, Path workFolder, Path cookiesDir, String proxy) throws Exception;
}
