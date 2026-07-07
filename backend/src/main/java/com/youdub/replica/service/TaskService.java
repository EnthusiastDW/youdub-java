package com.youdub.replica.service;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.dto.ContinueTaskRequest;
import com.youdub.replica.dto.TaskCreateRequest;
import com.youdub.replica.dto.TaskResponse;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.entity.TaskStage;
import com.youdub.replica.model.enums.StageStatus;
import com.youdub.replica.model.enums.TaskStatus;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.service.adapter.AdapterConstants;
import com.youdub.replica.util.FilenameUtils;
import com.youdub.replica.util.TaskDirResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 任务服务。
 * 负责任务的创建、查询、状态转换与文件管理。
 * 实际的管线执行由 PipelineOrchestrator 通过 WorkerService 触发，此处仅管理元数据。
 * 预创建的阶段列表根据执行模式和配置动态决定：
 * <ul>
 *   <li>简化模式（仅字幕）只创建与字幕相关的阶段</li>
 *   <li>配置中禁用的功能（如 ASR 纠错）不会预创建对应阶段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final AppProperties appProperties;
    private final TaskDirResolver taskDirResolver;
    private final WorkerService workerService;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final SettingsService settingsService;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<StageDef> STAGE_DEFS = List.of(
            new StageDef("download", "下载"),
            new StageDef("separate", "Demucs"),
            new StageDef("asr", "Whisper"),
            new StageDef("asr_correct", "ASR纠错"),
            new StageDef("asr_fix", "切分句子"),
            new StageDef("translate", "翻译"),
            new StageDef("split_audio", "切分音频"),
            new StageDef("tts", "VoxCPM"),
            new StageDef("merge_audio", "混合音频"),
            new StageDef("merge_video", "合成视频")
    );

    /** 简化模式（仅字幕）包含的阶段名，与 PipelineOrchestrator.SUBTITLE_ONLY_STAGES 保持同步 */
    private static final Set<String> SUBTITLE_ONLY_STAGE_NAMES = Set.of(
            "download", "separate", "asr", "asr_correct", "asr_fix", "translate", "merge_video"
    );

    /**
     * 创建任务（URL 来源）。
     */
    public TaskResponse createTask(TaskCreateRequest request) {
        if (request.getUrl() == null || request.getUrl().isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }

        // 重复检测
        Task existing = taskRepository.findByUrl(request.getUrl());
        if (existing != null) {
            log.info("URL 已存在任务：{}（id={}）", request.getUrl(), existing.getId());
            return TaskResponse.from(existing);
        }

        Task task = new Task();
        task.setId(generateId());
        task.setUrl(request.getUrl());
        task.setTitle("");
        task.setStatus(TaskStatus.QUEUED);
        task.setExecutionMode(request.getExecutionMode() == null ? "auto" : request.getExecutionMode());
        task.setNotes(request.getNotes() == null ? "" : request.getNotes());
        task.setSourceType(detectSourceType(request.getUrl()));
        task.setAsrLanguage(detectAsrLanguage(task.getSourceType()));
        task.setTargetLanguage(detectTargetLanguage(task.getSourceType()));
        task.setProgress(0.0);
        task.setCreatedAt(now());
        task.setYoutubeVideoId(request.getYoutubeVideoId() == null ? "" : request.getYoutubeVideoId());

        taskRepository.insert(task);
        initStages(task.getId(), task.getExecutionMode());

        log.info("创建任务：id={}, url={}", task.getId(), task.getUrl());
        TaskResponse response = TaskResponse.from(taskRepository.findById(task.getId()));
        workerService.enqueue(task.getId());
        return response;
    }

    /**
     * 上传本地视频创建任务。
     */
    public TaskResponse uploadLocalVideo(MultipartFile file, String executionMode, String direction, MultipartFile subtitleFile, String youtubeVideoId, String notes) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        long maxBytes = appProperties.getUploadMaxBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文件大小超过限制：" + maxBytes + " bytes");
        }

        String taskId = generateId();
        Path uploadDir = taskDirResolver.resolveUploadDir(taskId).resolve("video");
        Files.createDirectories(uploadDir);
        Path dest = uploadDir.resolve(sanitize(file.getOriginalFilename()));
        file.transferTo(dest.toFile());

        if (subtitleFile != null && !subtitleFile.isEmpty()) {
            long subtitleMax = appProperties.getSubtitleMaxBytes();
            if (subtitleFile.getSize() > subtitleMax) {
                throw new IllegalArgumentException("字幕文件大小超过限制：" + subtitleMax + " bytes");
            }
            Path subtitleDir = taskDirResolver.resolveUploadDir(taskId).resolve("subtitle");
            Files.createDirectories(subtitleDir);
            Path subtitleDest = subtitleDir.resolve(sanitize(subtitleFile.getOriginalFilename()));
            subtitleFile.transferTo(subtitleDest.toFile());
        }

        String[] dirParts = direction != null ? direction.split("-") : new String[]{"en", "zh"};
        String asrLang = dirParts.length > 0 ? dirParts[0] : "en";
        String targetLang = dirParts.length > 1 ? dirParts[1] : "zh";

        Task task = new Task();
        task.setId(taskId);
        task.setUrl("local://upload/" + taskId);
        task.setTitle(stripExtension(file.getOriginalFilename()));
        task.setStatus(TaskStatus.QUEUED);
        task.setExecutionMode(executionMode == null ? "auto" : executionMode);
        task.setSourceType(AdapterConstants.LOCAL);
        task.setAsrLanguage(asrLang);
        task.setTargetLanguage(targetLang);
        task.setProgress(0.0);
        task.setCreatedAt(now());
        task.setYoutubeVideoId(youtubeVideoId == null ? "" : youtubeVideoId);
        task.setNotes(notes == null ? "" : notes);

        taskRepository.insert(task);
        initStages(task.getId(), task.getExecutionMode());

        log.info("上传任务：id={}, file={}, direction={}", task.getId(), file.getOriginalFilename(), direction);
        TaskResponse response = TaskResponse.from(taskRepository.findById(task.getId()));
        workerService.enqueue(task.getId());
        return response;
    }

    /**
     * 任务列表（分页）。
     */
    public List<TaskResponse> listTasks(int offset, int limit) {
        return taskRepository.findAll(offset, limit).stream()
                .map(TaskResponse::from)
                .toList();
    }

    /**
     * 任务总数。
     */
    public int countTasks() {
        return taskRepository.countAll();
    }

    /**
     * 任务详情。
     */
    public TaskResponse getTask(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        return TaskResponse.from(task);
    }

    /**
     * 当前运行中的任务。
     */
    public TaskResponse getCurrentTask() {
        Task task = taskRepository.findCurrent();
        return task == null ? null : TaskResponse.from(task);
    }

    /**
     * 删除任务及所有相关文件。
     */
    public void deleteTask(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }

        // 1. 删除会话目录
        if (task.getSessionPath() != null && !task.getSessionPath().isBlank()) {
            deleteDirectory(Paths.get(task.getSessionPath()));
        }

        // 2. 删除上传目录
        Path uploadDir = taskDirResolver.resolveUploadDir(id);
        deleteDirectory(uploadDir);

        // 3. 删除日志文件
        Path logFile = Paths.get(appProperties.getLogDir(), "task-" + id + ".log");
        try {
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            log.warn("删除日志文件失败：{}", e.getMessage());
        }

        // 4. 删除数据库记录
        taskRepository.hardDelete(id);
        log.info("删除任务及所有关联文件：{} (session={})", id, task.getSessionPath());
    }

    private void deleteDirectory(Path dir) {
        if (dir == null) return;
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("删除文件/目录失败：{}", p);
                            }
                        });
                log.debug("已删除目录：{}", dir);
            }
        } catch (IOException e) {
            log.warn("遍历删除目录失败：{}", e.getMessage());
        }
    }

    /**
     * 重跑任务（从头重做，删除所有阶段产物）。
     */
    public TaskResponse rerunTask(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        // 删除所有阶段的磁盘产物
        Path sessionDir = taskDirResolver.resolveTaskDir(task);
        deleteStageOutputsFrom(sessionDir, "download");

        taskRepository.resetStagesFrom(id, "download");
        taskRepository.updateStatus(id, TaskStatus.QUEUED, 0.0);
        taskRepository.updateField(id, "error_message", "");
        taskRepository.updateField(id, "started_at", null);
        taskRepository.updateField(id, "completed_at", null);
        log.info("重跑任务：{}", id);
        TaskResponse response = TaskResponse.from(taskRepository.findById(id));
        workerService.enqueue(id);
        return response;
    }

    /**
     * 恢复失败任务。
     */
    public TaskResponse resumeTask(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        // 重置所有 failed/running 阶段为 pending
        for (TaskStage stage : task.getStages()) {
            if (stage.getStatus() == StageStatus.FAILED || stage.getStatus() == StageStatus.RUNNING) {
                taskRepository.updateStageStatus(id, stage.getName(), StageStatus.PENDING, 0, "");
            }
        }
        taskRepository.updateStatus(id, TaskStatus.QUEUED, task.getProgress());
        taskRepository.updateField(id, "error_message", "");
        log.info("恢复任务：{}", id);
        TaskResponse response = TaskResponse.from(taskRepository.findById(id));
        workerService.enqueue(id);
        return response;
    }

    /**
     * 继续已暂停任务（手动模式）。
     */
    public TaskResponse continueTask(String id, ContinueTaskRequest request) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        if (request != null && request.getExecutionMode() != null) {
            taskRepository.updateField(id, "execution_mode", request.getExecutionMode());
        }
        taskRepository.updateStatus(id, TaskStatus.QUEUED, task.getProgress());
        log.info("继续任务：{}", id);
        TaskResponse response = TaskResponse.from(taskRepository.findById(id));
        workerService.enqueue(id);
        return response;
    }

    /**
     * 重做指定阶段（清除该阶段及后续产物，重新入队）。
     */
    public TaskResponse redoStage(String id, String stageName) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        // 删除该阶段及后续所有阶段的磁盘产物
        Path sessionDir = taskDirResolver.resolveTaskDir(task);
        deleteStageOutputsFrom(sessionDir, stageName);

        taskRepository.resetStagesFrom(id, stageName);
        taskRepository.updateStatus(id, TaskStatus.QUEUED, task.getProgress());
        taskRepository.updateField(id, "error_message", "");
        log.info("重做阶段：task={}, stage={}", id, stageName);
        TaskResponse response = TaskResponse.from(taskRepository.findById(id));
        workerService.enqueue(id);
        return response;
    }

    /**
     * 停止运行中的任务（中断当前阶段，整个任务标记为失败，不继续执行下一阶段）。
     */
    public TaskResponse stopTask(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        pipelineOrchestrator.markTaskStop(id);
        workerService.interruptTask(id);
        log.info("停止任务：{}", id);
        return TaskResponse.from(taskRepository.findById(id));
    }

    /**
     * 获取任务日志（纯文本），支持字节偏移增量读取。
     *
     * @param offset 字节偏移量，从该位置开始读取；0 或负数表示从头读取
     * @return 从 offset 到文件末尾的文本内容
     */
    public String getTaskLog(String id, long offset) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        Path logFile = Paths.get(appProperties.getLogDir(), "task-" + id + ".log");
        if (!Files.exists(logFile)) {
            return "";
        }
        try {
            byte[] all = Files.readAllBytes(logFile);
            if (offset >= all.length) return "";
            long start = Math.max(0, offset);
            return new String(all, (int) start, (int) (all.length - start), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取日志失败：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 更新 YouTube 视频 ID。
     */
    public void updateYoutubeVideoId(String id, String youtubeVideoId) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        taskRepository.updateField(id, "youtube_video_id", youtubeVideoId == null ? "" : youtubeVideoId);
        log.info("更新 YouTube Video ID：task={}, youtubeVideoId={}", id, youtubeVideoId);
    }

    /**
     * 更新任务备注。
     */
    public void updateNotes(String id, String notes) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        taskRepository.updateField(id, "notes", notes == null ? "" : notes);
        log.info("更新备注：task={}", id);
    }

    /**
     * 获取视频摘要（从 session 目录读取 summary.md）。
     */
    public String getSummary(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        if (task.getSessionPath() == null || task.getSessionPath().isBlank()) {
            return "";
        }
        Path summaryFile = Paths.get(task.getSessionPath(), "metadata", "summary.md");
        if (!Files.exists(summaryFile)) {
            return "";
        }
        try {
            return Files.readString(summaryFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取摘要失败：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 更新视频摘要（写入 summary.md 文件）。
     */
    public void updateSummary(String id, String summary) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        if (task.getSessionPath() == null || task.getSessionPath().isBlank()) {
            throw new IllegalStateException("任务尚未初始化会话目录");
        }
        Path summaryFile = Paths.get(task.getSessionPath(), "metadata", "summary.md");
        try {
            Files.createDirectories(summaryFile.getParent());
            Files.writeString(summaryFile, summary == null ? "" : summary, StandardCharsets.UTF_8);
            log.info("更新摘要：task={}", id);
        } catch (IOException e) {
            throw new RuntimeException("写入摘要失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取最终视频文件路径。
     */
    public Path getFinalVideo(String id) {
        Task task = taskRepository.findById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        if (task.getFinalVideoPath() == null || task.getFinalVideoPath().isBlank()) {
            throw new NoSuchElementException("任务尚未生成最终视频：" + id);
        }
        Path video = Paths.get(task.getFinalVideoPath());
        if (!Files.exists(video)) {
            throw new NoSuchElementException("最终视频文件不存在：" + video);
        }
        return video;
    }

    /**
     * 根据任务执行模式和当前配置预创建阶段。
     * <ul>
     *   <li>简化模式（仅字幕）只创建字幕管线包含的阶段</li>
     *   <li>配置中禁用的功能（如 ASR 纠错已关闭）不会预创建对应阶段</li>
     * </ul>
     * 任务实际执行时仍以 PipelineOrchestrator 的 STAGES 为准，
     * 未预创建的阶段会在执行到该步骤时动态添加。
     */
    private void initStages(String taskId, String executionMode) {
        boolean subtitleOnly = "subtitle-only".equalsIgnoreCase(executionMode);

        for (StageDef def : STAGE_DEFS) {
            // 简化模式：跳过不属于字幕管线的阶段
            if (subtitleOnly && !SUBTITLE_ONLY_STAGE_NAMES.contains(def.name())) {
                continue;
            }

            // 根据配置跳过被禁用的功能
            if (!isStageFeatureEnabled(def.name())) {
                continue;
            }

            TaskStage stage = new TaskStage();
            stage.setTaskId(taskId);
            stage.setName(def.name());
            stage.setLabel(def.label());
            stage.setStatus(StageStatus.PENDING);
            stage.setProgress(0);
            stage.setLastMessage("");
            stage.setErrorMessage("");
            taskRepository.insertStage(stage);
        }
    }

    /**
     * 检查某个阶段对应的功能是否在配置中启用。
     * 默认所有阶段均启用，只有明确配置了 enabled=false 的可选功能会被跳过。
     */
    private boolean isStageFeatureEnabled(String stageName) {
        return switch (stageName) {
            case "asr_correct" -> {
                try {
                    var cfg = settingsService.getProviderConfig(
                            AdapterConstants.OPENAI_ASR_CORRECTOR,
                            AppProperties.AsrCorrectorConfig.OpenaiAsrCorrector.class);
                    yield cfg.isEnabled();
                } catch (Exception e) {
                    log.warn("读取 ASR 纠错配置失败，默认启用: {}", e.getMessage());
                    yield true;
                }
            }
            default -> true;
        };
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String now() {
        return LocalDateTime.now().format(ISO);
    }

    private String detectSourceType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "youtube";
        }
        if (lower.contains("bilibili.com")) {
            return "bilibili";
        }
        return "url";
    }

    private String detectAsrLanguage(String sourceType) {
        return switch (sourceType) {
            case "bilibili" -> "zh";
            default -> "en";
        };
    }

    private String detectTargetLanguage(String sourceType) {
        return switch (sourceType) {
            case "bilibili" -> "en";
            default -> "zh";
        };
    }

    private String sanitize(String name) {
        if (name == null) return "upload";
        return FilenameUtils.sanitize(name);
    }

    private String stripExtension(String filename) {
        return FilenameUtils.stripExtension(filename);
    }

    // ── 阶段产物清理 ──

    private void deleteStageOutputsFrom(Path sessionDir, String stageName) {
        boolean found = false;
        for (StageDef stage : STAGE_DEFS) {
            if (stage.name().equals(stageName)) found = true;
            if (found) {
                deleteStageOutputs(sessionDir, stage.name());
            }
        }
    }

    private void deleteStageOutputs(Path sessionDir, String stageName) {
        try {
            switch (stageName) {
                case "download" -> {
                    deletePath(sessionDir.resolve("media/video_source.mp4"));
                    deletePath(sessionDir.resolve("media/video_source.en.vtt"));
                    deletePath(sessionDir.resolve("metadata/ytdlp_info.json"));
                    deletePath(sessionDir.resolve("metadata/local_info.json"));
                }
                case "separate" -> {
                    deletePath(sessionDir.resolve("media/audio_vocals.wav"));
                    deletePath(sessionDir.resolve("media/audio_bgm.wav"));
                    deletePath(sessionDir.resolve("media/temp_audio.wav"));
                    deletePath(sessionDir.resolve("media/htdemucs_ft"));
                }
                case "asr" -> {
                    deletePath(sessionDir.resolve("metadata/asr.json"));
                    deletePath(sessionDir.resolve("metadata/asr"));
                }
                case "asr_correct" -> {
                    deletePath(sessionDir.resolve("metadata/asr_corrected.json"));
                }
                case "asr_fix" -> {
                    deletePath(sessionDir.resolve("metadata/asr_fixed.json"));
                }
                case "translate" -> {
                    deleteFilesWithPrefix(sessionDir.resolve("metadata"), "translation.");
                    deletePath(sessionDir.resolve("metadata/title_bilingual.json"));
                    deletePath(sessionDir.resolve("metadata/translation_preprocess.json"));
                    deletePath(sessionDir.resolve("metadata/summary.md"));
                }
                case "split_audio" -> {
                    deletePath(sessionDir.resolve("segments"));
                }
                case "tts" -> {
                    deletePath(sessionDir.resolve("segments/tts"));
                }
                case "merge_audio" -> {
                    deletePath(sessionDir.resolve("tmp"));
                }
                case "merge_video" -> {
                    deletePath(sessionDir.resolve("media/video_final.mp4"));
                    // 删除已重命名的最终视频（双语文件名）
                    Path mediaDir = sessionDir.resolve("media");
                    if (Files.exists(mediaDir)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mediaDir, "* - *.mp4")) {
                            for (Path p : ds) {
                                Files.deleteIfExists(p);
                            }
                        }
                    }
                    deletePath(sessionDir.resolve("media/subtitles.srt"));
                    deletePath(sessionDir.resolve("media/audio_mixed.m4a"));
                }
            }
        } catch (Exception e) {
            log.warn("删除阶段产物失败：stage={}, error={}", stageName, e.getMessage());
        }
    }

    private void deletePath(Path path) {
        try {
            if (!Files.exists(path)) return;
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ }
                        });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("删除路径失败：{}", path, e);
        }
    }

    private void deleteFilesWithPrefix(Path dir, String prefix) {
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path p : ds) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除前缀文件失败：{} in {}", prefix, dir, e);
        }
    }

    private record StageDef(String name, String label) {}
}
