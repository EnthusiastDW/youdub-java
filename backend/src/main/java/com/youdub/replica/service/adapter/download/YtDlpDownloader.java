package com.youdub.replica.service.adapter.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.YTDLP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * yt-dlp 下载适配器。
 * 通过 yt-dlp 子进程下载 YouTube/Bilibili 等视频。
 */
@Slf4j
@Component(YTDLP)
@RequiredArgsConstructor
public class YtDlpDownloader implements Downloader {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Override
    public void download(Task task, Path workFolder, Path cookiesDir, String proxy) throws Exception {
        if (task.getUrl() == null || task.getUrl().isBlank()) {
            throw new IllegalArgumentException("任务 URL 不能为空");
        }

        Path mediaDir = workFolder.resolve("media");
        Path metadataDir = workFolder.resolve("metadata");
        Files.createDirectories(mediaDir);
        Files.createDirectories(metadataDir);

        AppProperties.Download config = settingsService.getGlobalConfig("download", AppProperties.Download.class);
        String outputFilename = config.getOutputFilename();
        if (outputFilename == null || outputFilename.isBlank()) {
            outputFilename = "video_source.mp4";
        }
        Path outputFile = mediaDir.resolve(outputFilename);
        if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
            log.info("视频已存在，跳过下载：{}", outputFile);
            taskRepository.updateField(task.getId(), "session_path", workFolder.toString());
            return;
        }

        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        command.add("--no-playlist");
        // --print title 会在下载前先输出视频标题（一行），取输出的第一行即可
        command.add("--print");
        command.add("title");
        command.add("--downloader");
        command.add("curl_cffi");
        command.add("--remote-components");
        command.add("ejs:npm");
        command.add("-o");
        command.add(mediaDir.resolve(outputFilename).toString());
        if (proxy != null && !proxy.isBlank()) {
            command.add("--proxy");
            command.add(proxy);
        }
        String cookieFileName = "youtube.txt";
        Path cookieFile = cookiesDir == null ? null : cookiesDir.resolve(cookieFileName);
        if (cookieFile != null && Files.exists(cookieFile)) {
            command.add("--cookies");
            command.add(cookieFile.toString());
        }
        command.add(task.getUrl());

        log.info("执行 yt-dlp 下载：task={}, url={}", task.getId(), task.getUrl());
        String output;
        try {
            output = CommandRunner.run(Command.builder()
                    .add(command)
                    .timeout(config.getTimeoutMs())
                    .workDir(workFolder)
                    .build()).output();
        } catch (RuntimeException e) {
            throw new RuntimeException("yt-dlp 下载失败（请确认 yt-dlp 已安装）： " + e.getMessage(), e);
        }

        if (!Files.exists(outputFile)) {
            throw new RuntimeException("yt-dlp 下载完成但输出文件不存在：" + outputFile);
        }

        // 从下载输出中提取标题 --print title 输出了第一行
        String title = extractTitleFromOutput(output);
        if (title != null && !title.isBlank()) {
            taskRepository.updateField(task.getId(), "title", title);
        }

        // 保存 yt-dlp 元数据
        Path infoFile = metadataDir.resolve("ytdlp_info.json");
        try {
            JsonNode infoNode = objectMapper.createObjectNode()
                    .put("url", task.getUrl())
                    .put("title", title == null ? "" : title)
                    .put("output", output);
            Files.writeString(infoFile, objectMapper.writeValueAsString(infoNode));
        } catch (Exception e) {
            log.warn("保存 yt-dlp 元数据失败：{}", e.getMessage());
        }

        taskRepository.updateField(task.getId(), "session_path", workFolder.toString());
        log.info("下载完成：task={}, file={}", task.getId(), outputFile);
    }

    /**
     * 从 yt-dlp 输出中提取标题。
     * yt-dlp --print title 输出在第一行。
     */
    private String extractTitleFromOutput(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String title = output.trim();
        int newline = title.indexOf('\n');
        if (newline > 0) {
            title = title.substring(0, newline).trim();
        }
        return title.isEmpty() ? null : title;
    }
}
