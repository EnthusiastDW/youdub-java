package com.youdub.replica.service.adapter.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.util.TaskDirResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 本地文件下载适配器。
 * 从上传目录复制视频文件到会话目录。
 */
@Slf4j
@Component("local")
@RequiredArgsConstructor
public class LocalFileDownloader implements Downloader {

    private final TaskRepository taskRepository;
    private final TaskDirResolver taskDirResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "local";
    }

    @Override
    public void download(Task task, Path workFolder, Path cookiesDir, String proxy) throws Exception {
        Path mediaDir = workFolder.resolve("media");
        Path metadataDir = workFolder.resolve("metadata");
        Files.createDirectories(mediaDir);
        Files.createDirectories(metadataDir);

        Path outputFile = mediaDir.resolve("video_source.mp4");
        if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
            log.info("本地视频已存在，跳过复制：{}", outputFile);
            taskRepository.updateField(task.getId(), "session_path", workFolder.toString());
            return;
        }

        Path uploadDir = taskDirResolver.resolveUploadDir(task.getId()).resolve("video");
        if (!Files.exists(uploadDir)) {
            throw new RuntimeException("上传目录不存在：" + uploadDir);
        }

        Path sourceVideo = findFirstVideoFile(uploadDir);
        if (sourceVideo == null) {
            throw new RuntimeException("上传目录中没有视频文件：" + uploadDir);
        }

        Files.copy(sourceVideo, outputFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("复制本地视频：{} -> {}", sourceVideo, outputFile);

        // 写入 local_info.json 元数据
        ObjectNode info = objectMapper.createObjectNode();
        info.put("title", task.getTitle() == null ? "" : task.getTitle());
        info.put("source_type", "local");
        info.put("asr_language", task.getAsrLanguage() == null ? "en" : task.getAsrLanguage());
        info.put("target_language", task.getTargetLanguage() == null ? "zh" : task.getTargetLanguage());
        info.put("original_filename", sourceVideo.getFileName().toString());

        Path infoFile = metadataDir.resolve("local_info.json");
        Files.writeString(infoFile, objectMapper.writeValueAsString(info));

        taskRepository.updateField(task.getId(), "session_path", workFolder.toString());
        log.info("本地文件导入完成：task={}, file={}", task.getId(), outputFile);
    }

    /**
     * 查找目录中第一个视频文件。
     */
    private Path findFirstVideoFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                                || name.endsWith(".avi") || name.endsWith(".webm") || name.endsWith(".flv");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }
}
