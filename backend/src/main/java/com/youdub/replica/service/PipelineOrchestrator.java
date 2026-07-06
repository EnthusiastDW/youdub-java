package com.youdub.replica.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.service.adapter.asr.SubtitleParser;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.entity.TaskStage;
import com.youdub.replica.model.enums.StageStatus;
import com.youdub.replica.model.enums.TaskStatus;
import com.youdub.replica.repository.SettingsRepository;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.service.adapter.AdapterSkipTracker;
import com.youdub.replica.service.adapter.asr.SpeechRecognizer;
import com.youdub.replica.service.adapter.audio.AudioProcessor;
import com.youdub.replica.service.adapter.download.Downloader;
import com.youdub.replica.service.adapter.separate.SourceSeparator;
import com.youdub.replica.service.adapter.translate.Translator;
import com.youdub.replica.service.adapter.tts.TtsProvider;
import com.youdub.replica.service.adapter.video.VideoProcessor;
import com.youdub.replica.util.DeviceResolver;
import com.youdub.replica.util.TaskDirResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static com.youdub.replica.service.adapter.AdapterConstants.*;

/**
 * 管线编排器。
 * 按 9 个阶段顺序执行任务，支持缓存命中、暂停/取消检查、手动模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final TaskRepository taskRepository;
    private final SettingsRepository settingsRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Qualifier("virtualExecutor")
    private final ExecutorService virtualExecutor;
    private final TaskDirResolver taskDirResolver;
    private final DeviceResolver deviceResolver;

    // 适配器通过 Spring Map<String, Interface> 注入，key 是 bean 名称
    private final Map<String, Downloader> downloaders;
    private final Map<String, SourceSeparator> separators;
    private final Map<String, SpeechRecognizer> recognizers;
    private final Map<String, Translator> translators;
    private final Map<String, TtsProvider> ttsProviders;
    private final Map<String, AudioProcessor> audioProcessors;
    private final Map<String, VideoProcessor> videoProcessors;
    private final AdapterSkipTracker skipTracker;

    /**
     * 重跑/重做时的阶段时间戳备份（taskId -> {stageName -> [startedAt, completedAt]}）。
     * TaskService 在 resetStagesFrom 前写入，PipelineOrchestrator 在阶段执行后消费并清理。
     */
    static final Map<String, Map<String, String[]>> stageTimestampsBackup = new ConcurrentHashMap<>();

    /** 9 个阶段定义 */
    private static final List<StageDef> STAGES = List.of(
            new StageDef("download", "下载"),
            new StageDef("separate", "Demucs"),
            new StageDef("asr", "Whisper"),
            new StageDef("asr_fix", "切分句子"),
            new StageDef("translate", "翻译"),
            new StageDef("split_audio", "切分音频"),
            new StageDef("tts", "VoxCPM"),
            new StageDef("merge_audio", "混合音频"),
            new StageDef("merge_video", "合成视频")
    );

    /**
     * 执行管线。
     */
    public void execute(String taskId) {
        Task task = taskRepository.findById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在：" + taskId);
        }

        log.info("开始执行管线：task={}, url={}", taskId, task.getUrl());
        taskRepository.updateStatus(taskId, TaskStatus.RUNNING, 0.0);
        taskRepository.updateField(taskId, "started_at", nowIso());
        taskRepository.updateField(taskId, "error_message", "");

        try {
            Path sessionDir = taskDirResolver.resolveTaskDir(task);
            Files.createDirectories(sessionDir);
            if (task.getSessionPath() == null || task.getSessionPath().isBlank()) {
                taskRepository.updateField(taskId, "session_path", sessionDir.toString());
                task.setSessionPath(sessionDir.toString());
            }

            for (StageDef stage : STAGES) {
                // 检查任务是否被暂停或取消
                Task current = taskRepository.findById(taskId);
                if (current.getStatus() == TaskStatus.PAUSED) {
                    log.info("任务已暂停，退出管线：task={}, stage={}", taskId, stage.name);
                    stageTimestampsBackup.remove(taskId);
                    return;
                }
                if (current.getStatus() == TaskStatus.CANCELLED) {
                    log.info("任务已取消，退出管线：task={}", taskId);
                    stageTimestampsBackup.remove(taskId);
                    return;
                }

                TaskStage stageState = findStage(current.getStages(), stage.name);
                if (stageState != null && stageState.getStatus() == StageStatus.SUCCEEDED) {
                    log.info("阶段已成功，跳过：task={}, stage={}", taskId, stage.name);
                    continue;
                }

                taskRepository.updateField(taskId, "current_stage", stage.name);
                taskRepository.updateStageStatus(taskId, stage.name, StageStatus.RUNNING, 0, "");
                double stageProgressBase = (STAGES.indexOf(stage) * 100.0) / STAGES.size();
                taskRepository.updateStatus(taskId, TaskStatus.RUNNING, stageProgressBase);

                try {
                    executeStage(stage.name, task, sessionDir);
                    taskRepository.updateStageStatus(taskId, stage.name, StageStatus.SUCCEEDED, 100, "");

                    // adapter 显式标记跳过 → 恢复原始时间戳
                    if (skipTracker.isSkipped()) {
                        restoreStageTimestampsIfSkipped(taskId, stage.name);
                    }
                    skipTracker.clear();

                    double overallProgress = ((STAGES.indexOf(stage) + 1) * 100.0) / STAGES.size();
                    taskRepository.updateStatus(taskId, TaskStatus.RUNNING, overallProgress);
                    log.info("阶段完成：task={}, stage={}", taskId, stage.name);
                } catch (Exception e) {
                    log.error("阶段失败：task={}, stage={}", taskId, stage.name, e);
                    taskRepository.updateStageStatus(taskId, stage.name, StageStatus.FAILED, 0, e.getMessage());
                    taskRepository.updateStatus(taskId, TaskStatus.FAILED, stageProgressBase);
                    taskRepository.updateField(taskId, "error_message", "阶段 " + stage.label + " 失败：" + e.getMessage());
                    taskRepository.updateField(taskId, "completed_at", nowIso());
                    return;
                }

                // 手动模式下，每个阶段成功后暂停
                if ("manual".equalsIgnoreCase(task.getExecutionMode())) {
                    log.info("手动模式，暂停任务：task={}, stage={}", taskId, stage.name);
                    taskRepository.updateStatus(taskId, TaskStatus.PAUSED, stageProgressBase + 100.0 / STAGES.size());
                    return;
                }
            }

            // 所有阶段完成
            taskRepository.updateStatus(taskId, TaskStatus.SUCCEEDED, 100.0);
            taskRepository.updateField(taskId, "completed_at", nowIso());
            log.info("管线执行完成：task={}", taskId);

        } catch (Exception e) {
            log.error("管线执行异常：task={}", taskId, e);
            taskRepository.updateStatus(taskId, TaskStatus.FAILED, 0.0);
            taskRepository.updateField(taskId, "error_message", e.getMessage());
            taskRepository.updateField(taskId, "completed_at", nowIso());
        } finally {
            skipTracker.clear();
            stageTimestampsBackup.remove(taskId);
        }
    }

    /**
     * 根据阶段名称调用对应适配器。
     */
    private void executeStage(String stageName, Task task, Path sessionDir) throws Exception {
        Path mediaDir = sessionDir.resolve("media");
        Path metadataDir = sessionDir.resolve("metadata");
        Path segmentsDir = sessionDir.resolve("segments");
        Path tmpDir = sessionDir.resolve("tmp");
        Files.createDirectories(mediaDir);
        Files.createDirectories(metadataDir);
        Files.createDirectories(segmentsDir);
        Files.createDirectories(tmpDir);

        switch (stageName) {
            case "download" -> executeDownload(task, sessionDir);
            case "separate" -> executeSeparate(task, sessionDir);
            case "asr" -> executeAsr(task, sessionDir);
            case "asr_fix" -> executeAsrFix(task, sessionDir);
            case "translate" -> executeTranslate(task, sessionDir);
            case "split_audio" -> executeSplitAudio(task, sessionDir);
            case "tts" -> executeTts(task, sessionDir);
            case "merge_audio" -> executeMergeAudio(task, sessionDir);
            case "merge_video" -> executeMergeVideo(task, sessionDir);
            default -> throw new RuntimeException("未知阶段：" + stageName);
        }
    }

    private void executeDownload(Task task, Path sessionDir) throws Exception {
        String downloaderName = LOCAL.equalsIgnoreCase(task.getSourceType()) ? LOCAL :
        settingsRepository.get("download.provider", YTDLP);
        Downloader downloader = downloaders.get(downloaderName);
        if (downloader == null) {
            throw new RuntimeException("未找到下载适配器：" + downloaderName);
        }
        Path cookiesDir = Paths.get(appProperties.getCookieDir());
        String proxy = settingsRepository.get("ytdlp.proxy", appProperties.getYtdlp().getProxy());
        downloader.download(task, sessionDir, cookiesDir, proxy);
    }

    private void executeSeparate(Task task, Path sessionDir) throws Exception {
        String provider = settingsRepository.get("separate.provider", appProperties.getSeparate().getProvider());
        SourceSeparator separator = separators.get(provider);
        if (separator == null) {
            throw new RuntimeException("未找到分离适配器：" + provider);
        }
        Path audioPath = sessionDir.resolve("media").resolve("video_source.mp4");
        Path outputDir = sessionDir.resolve("media");
        String device = deviceResolver.getDeviceForComponent(DEMUCS);
        separator.separate(task, audioPath, outputDir, device);
    }

    private void executeAsr(Task task, Path sessionDir) throws Exception {
        Path audioPath = sessionDir.resolve("media").resolve("audio_vocals.wav");
        Path outputDir = sessionDir.resolve("metadata");

        // 优先使用 YouTube 自动字幕，无需 GPU ASR
        Path subtitleFile = sessionDir.resolve("media").resolve("video_source.en.vtt");
        if (Files.exists(subtitleFile)) {
            log.info("找到英文字幕文件，跳过 Whisper ASR：{}", subtitleFile);
            List<SubtitleParser.Segment> segments = SubtitleParser.parse(subtitleFile);
            writeAsrJson(segments, audioPath, outputDir);
            return;
        }

        String provider = settingsRepository.get("asr.provider", appProperties.getAsr().getProvider());
        SpeechRecognizer recognizer = recognizers.get(provider);
        if (recognizer == null) {
            throw new RuntimeException("未找到 ASR 适配器：" + provider);
        }
        String language = task.getAsrLanguage();
        recognizer.transcribe(task, audioPath, outputDir, language);
    }

    /**
     * 将字幕段列表写入 asr.json，格式与 WhisperApiRecognizer.convertToStandardFormat 一致。
     */
    private void writeAsrJson(List<SubtitleParser.Segment> segments, Path audioPath, Path outputDir) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode audioInfo = objectMapper.createObjectNode();
        audioInfo.put("duration", 0);
        audioInfo.put("source", audioPath.toString());
        root.set("audio_info", audioInfo);

        ObjectNode resultObj = objectMapper.createObjectNode();
        StringBuilder fullText = new StringBuilder();
        ArrayNode utterances = objectMapper.createArrayNode();

        for (SubtitleParser.Segment seg : segments) {
            if (fullText.length() > 0) {
                fullText.append(" ");
            }
            fullText.append(seg.getText());

            ObjectNode utterance = objectMapper.createObjectNode();
            utterance.put("text", seg.getText());
            utterance.put("start_time", seg.getStartTimeMs());
            utterance.put("end_time", seg.getEndTimeMs());
            utterance.put("speaker", "1");
            utterance.set("words", objectMapper.createArrayNode());
            utterances.add(utterance);
        }

        resultObj.put("text", fullText.toString());
        resultObj.set("utterances", utterances);
        root.set("result", resultObj);

        Path asrFile = outputDir.resolve("asr.json");
        Files.writeString(asrFile, objectMapper.writeValueAsString(root));
        log.info("字幕转 ASR 完成：{}", asrFile);
    }

    private void executeAsrFix(Task task, Path sessionDir) throws Exception {
        Path asrFile = sessionDir.resolve("metadata").resolve("asr.json");
        Path fixedFile = sessionDir.resolve("metadata").resolve("asr_fixed.json");
        if (Files.exists(fixedFile)) {
            log.info("asr_fixed 已存在，跳过：{}", fixedFile);
            skipTracker.markSkipped();
            return;
        }
        if (!Files.exists(asrFile)) {
            throw new RuntimeException("ASR 文件不存在：" + asrFile);
        }
        // 简化版：直接复制 asr.json 为 asr_fixed.json，并应用时间 padding
        JsonNode root = objectMapper.readTree(Files.readString(asrFile));
        ObjectNode fixedRoot = root.deepCopy();
        JsonNode utterances = fixedRoot.path("result").path("utterances");
        if (utterances.isArray()) {
            for (JsonNode u : utterances) {
                if (u instanceof ObjectNode obj) {
                    long start = obj.path("start_time").asLong(0);
                    long end = obj.path("end_time").asLong(0);
                    // 应用 padding：起始 +100ms，结束 +300ms
                    obj.put("start_time", Math.max(0, start - 100));
                    obj.put("end_time", end + 300);
                }
            }
        }
        Files.writeString(fixedFile, objectMapper.writeValueAsString(fixedRoot));
        log.info("ASR 句子修正完成：task={}", task.getId());
    }

    private void executeTranslate(Task task, Path sessionDir) throws Exception {
        String provider = settingsRepository.get("translate.provider", appProperties.getTranslate().getProvider());
        Translator translator = translators.get(provider);
        if (translator == null) {
            throw new RuntimeException("未找到翻译适配器：" + provider);
        }
        Path asrPath = sessionDir.resolve("metadata").resolve("asr_fixed.json");
        if (!Files.exists(asrPath)) {
            asrPath = sessionDir.resolve("metadata").resolve("asr.json");
        }
        Path outputDir = sessionDir.resolve("metadata");
        String srcLang = task.getAsrLanguage();
        String dstLang = task.getTargetLanguage();
        translator.translate(task, asrPath, outputDir, null, srcLang, dstLang);
    }

    private void executeSplitAudio(Task task, Path sessionDir) throws Exception {
        AudioProcessor processor = audioProcessors.get(FFMPEG_AUDIO);
        Path vocalsPath = sessionDir.resolve("media").resolve("audio_vocals.wav");
        Path translationPath = sessionDir.resolve("metadata").resolve("translation." + task.getTargetLanguage() + ".json");
        Path outputDir = sessionDir.resolve("segments");
        processor.splitAudio(task, vocalsPath, translationPath, outputDir);
    }

    private void executeTts(Task task, Path sessionDir) throws Exception {
        String provider = settingsRepository.get("tts.provider", appProperties.getTts().getProvider());
        TtsProvider ttsProvider = ttsProviders.get(provider);
        if (ttsProvider == null) {
            throw new RuntimeException("未找到 TTS 适配器：" + provider);
        }
        Path textPath = sessionDir.resolve("metadata").resolve("translation." + task.getTargetLanguage() + ".json");
        Path outputDir = sessionDir.resolve("segments");
        ttsProvider.synthesize(task, textPath, outputDir);
    }

    private void executeMergeAudio(Task task, Path sessionDir) throws Exception {
        AudioProcessor processor = audioProcessors.get(FFMPEG_AUDIO);
        Path ttsDir = sessionDir.resolve("segments").resolve("tts");
        Path translationPath = sessionDir.resolve("metadata").resolve("translation." + task.getTargetLanguage() + ".json");
        Path outputDir = sessionDir.resolve("tmp");
        processor.mergeAudio(task, ttsDir, translationPath, outputDir);
    }

    private void executeMergeVideo(Task task, Path sessionDir) throws Exception {
        VideoProcessor processor = videoProcessors.get(FFMPEG_VIDEO);
        Path videoPath = sessionDir.resolve("media").resolve("video_source.mp4");
        Path dubbingPath = sessionDir.resolve("tmp").resolve("audio_dubbing.wav");
        Path bgmPath = sessionDir.resolve("media").resolve("audio_bgm.wav");
        Path timingsPath = sessionDir.resolve("tmp").resolve("timings.json");
        Path outputDir = sessionDir.resolve("media");
        processor.mergeVideo(task, videoPath, dubbingPath, bgmPath, timingsPath, outputDir);
    }

    /**
     * 阶段执行完后检查：如果 adapter 显式标记跳过（输出文件已存在），
     * 且备份中有该阶段的原始时间戳，则恢复之，保留上次的耗时数据。
     */
    private void restoreStageTimestampsIfSkipped(String taskId, String stageName) {
        Map<String, String[]> backup = stageTimestampsBackup.get(taskId);
        if (backup == null) {
            return;
        }

        String[] orig = backup.get(stageName);
        if (orig == null || orig[0] == null) {
            return;
        }

        log.info("阶段 {} 缓存命中，恢复原始时间戳", stageName);
        taskRepository.updateStageTimestamps(taskId, stageName, orig[0], orig[1]);
    }

    private TaskStage findStage(List<TaskStage> stages, String name) {
        if (stages == null) {
            return null;
        }
        return stages.stream().filter(s -> name.equals(s.getName())).findFirst().orElse(null);
    }

    private String nowIso() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private record StageDef(String name, String label) {}
}
