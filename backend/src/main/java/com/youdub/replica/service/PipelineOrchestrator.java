package com.youdub.replica.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.entity.TaskStage;
import com.youdub.replica.model.enums.StageStatus;
import com.youdub.replica.model.enums.TaskStatus;
import com.youdub.replica.repository.SettingsRepository;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.service.adapter.asr.AsrCorrector;
import com.youdub.replica.service.adapter.asr.SpeechRecognizer;
import com.youdub.replica.service.adapter.asr.SubtitleParser;
import com.youdub.replica.service.adapter.audio.AudioProcessor;
import com.youdub.replica.service.adapter.download.Downloader;
import com.youdub.replica.service.adapter.separate.SourceSeparator;
import com.youdub.replica.service.adapter.translate.Translator;
import com.youdub.replica.service.adapter.tts.TtsProvider;
import com.youdub.replica.service.adapter.video.VideoProcessor;
import com.youdub.replica.util.DeviceResolver;
import com.youdub.replica.util.FilenameUtils;
import com.youdub.replica.util.TaskDirResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static com.youdub.replica.service.adapter.AdapterConstants.*;

/**
 * 管线编排器。
 * 按 10 个阶段顺序执行任务，支持缓存命中、暂停/取消检查、手动模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final TaskRepository taskRepository;
    private final SettingsRepository settingsRepository;
    private final SettingsService settingsService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final TaskDirResolver taskDirResolver;
    private final DeviceResolver deviceResolver;

    // 适配器通过 Spring Map<String, Interface> 注入，key 是 bean 名称
    private final Map<String, Downloader> downloaders;
    private final Map<String, SourceSeparator> separators;
    private final Map<String, SpeechRecognizer> recognizers;
    private final Map<String, AsrCorrector> asrCorrectors;
    private final Map<String, Translator> translators;
    private final Map<String, TtsProvider> ttsProviders;
    private final Map<String, AudioProcessor> audioProcessors;
    private final Map<String, VideoProcessor> videoProcessors;

    private final Map<String, Semaphore> stageGates = new ConcurrentHashMap<>();
    private final Set<String> taskStopFlags = ConcurrentHashMap.newKeySet();

    /** 标记任务需要停止（由 TaskService 触发）。停止时整个任务退出，不会继续执行下一阶段。 */
    public void markTaskStop(String taskId) {
        taskStopFlags.add(taskId);
    }

    private boolean isTaskStopped(String taskId) {
        return taskStopFlags.contains(taskId);
    }

    private void clearTaskStop(String taskId) {
        taskStopFlags.remove(taskId);
    }

    private static final Map<String, Integer> DEFAULT_STAGE_CONCURRENCY = Map.ofEntries(
            Map.entry("download", 2),
            Map.entry("separate", 1),
            Map.entry("asr", 2),
            Map.entry("asr_correct", 2),
            Map.entry("asr_fix", 4),
            Map.entry("translate", 3),
            Map.entry("split_audio", 4),
            Map.entry("tts", 1),
            Map.entry("merge_audio", 2),
            Map.entry("merge_video", 1)
    );

    @PostConstruct
    public void initStageGates() {
        Map<String, Integer> config = appProperties.getPipeline().getConcurrency();
        DEFAULT_STAGE_CONCURRENCY.forEach((stage, defaultPermits) -> {
            int permits = config.getOrDefault(stage, defaultPermits);
            stageGates.put(stage, new Semaphore(permits));
            log.info("步骤并发门控初始化：{} = {}", stage, permits);
        });
    }

    /** 10 个阶段定义 */
    private static final List<StageDef> STAGES = List.of(
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

    // -- 常量 --

    private static final String CHINESE_LANG = "zh";
    private static final String ENGLISH_LANG = "en";
    private static final String TITLE_BILINGUAL_FILE = "title_bilingual.json";
    private static final String VIDEO_FINAL_FILE = "video_final.mp4";

    /**
     * 执行管线。
     */
    public void execute(String taskId) {
        Task task = taskRepository.findById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在：" + taskId);
        }

        // 根据 DB 中预创建的阶段确定活跃阶段列表（initStages 已按模式+配置过滤）
        List<String> activeStageNames = task.getStages().stream()
                .map(TaskStage::getName)
                .toList();
        List<StageDef> activeStages = STAGES.stream()
                .filter(s -> activeStageNames.contains(s.name))
                .toList();
        log.info("任务 {} 选用阶段：{}", taskId, activeStages.stream().map(StageDef::name).toList());

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
                    return;
                }
                if (current.getStatus() == TaskStatus.CANCELLED) {
                    log.info("任务已取消，退出管线：task={}", taskId);
                    return;
                }
                // 检查任务停止标记（用户点击停止按钮时触发）
                if (isTaskStopped(taskId)) {
                    log.warn("任务已被用户停止：task={}", taskId);
                    clearTaskStop(taskId);
                    taskRepository.updateStatus(taskId, TaskStatus.FAILED, task.getProgress());
                    taskRepository.updateField(taskId, "error_message", "用户停止");
                    taskRepository.updateField(taskId, "completed_at", nowIso());
                    return;
                }

                TaskStage stageState = findStage(current.getStages(), stage.name);
                // 阶段未被预创建（因模式或配置跳过），不执行
                if (stageState == null) {
                    continue;
                }
                if (stageState.getStatus() == StageStatus.SUCCEEDED) {
                    log.info("阶段已成功，跳过：task={}, stage={}", taskId, stage.name);
                    continue;
                }

                taskRepository.updateField(taskId, "current_stage", stage.name);
                taskRepository.updateStageStatus(taskId, stage.name, StageStatus.RUNNING, 0, "");
                double stageProgressBase = (activeStages.indexOf(stage) * 100.0) / activeStages.size();
                taskRepository.updateStatus(taskId, TaskStatus.RUNNING, stageProgressBase);

                try {
                    executeStage(stage.name, task, sessionDir);
                    taskRepository.updateStageStatus(taskId, stage.name, StageStatus.SUCCEEDED, 100, "");
                    double overallProgress = ((activeStages.indexOf(stage) + 1) * 100.0) / activeStages.size();
                    taskRepository.updateStatus(taskId, TaskStatus.RUNNING, overallProgress);
                    log.info("阶段完成：task={}, stage={}", taskId, stage.name);
                } catch (Exception e) {
                    // 用户停止整个任务 → 标记为 FAILED，退出管线
                    if (isTaskStopped(taskId)) {
                        log.warn("任务被用户停止：task={}, stage={}", taskId, stage.name);
                        clearTaskStop(taskId);
                        Thread.interrupted();
                        taskRepository.updateStageStatus(taskId, stage.name, StageStatus.FAILED, 0, "用户停止");
                        taskRepository.updateStatus(taskId, TaskStatus.FAILED, stageProgressBase);
                        taskRepository.updateField(taskId, "error_message", "用户停止");
                        taskRepository.updateField(taskId, "completed_at", nowIso());
                        return;
                    }
                    // 真实失败 → 退出整个任务
                    log.error("阶段失败：task={}, stage={}", taskId, stage.name, e);
                    taskRepository.updateStageStatus(taskId, stage.name, StageStatus.FAILED, 0, e.getMessage());
                    taskRepository.updateStatus(taskId, TaskStatus.FAILED, stageProgressBase);
                    taskRepository.updateField(taskId, "error_message", "阶段 " + stage.label + " 失败：" + e.getMessage());
                    taskRepository.updateField(taskId, "completed_at", nowIso());
                    return;
                }

                // 手动模式下，每个阶段成功后暂停
                if (isManualMode(task)) {
                    log.info("手动模式，暂停任务：task={}, stage={}", taskId, stage.name);
                    taskRepository.updateStatus(taskId, TaskStatus.PAUSED, stageProgressBase + 100.0 / activeStages.size());
                    return;
                }
            }

            // 所有阶段完成 → 重命名最终视频为双语文件名
            renameFinalVideo(sessionDir, taskId);

            taskRepository.updateStatus(taskId, TaskStatus.SUCCEEDED, 100.0);
            taskRepository.updateField(taskId, "completed_at", nowIso());
            log.info("管线执行完成：task={}", taskId);

        } catch (Exception e) {
            log.error("管线执行异常：task={}", taskId, e);
            taskRepository.updateStatus(taskId, TaskStatus.FAILED, 0.0);
            taskRepository.updateField(taskId, "error_message", e.getMessage());
            taskRepository.updateField(taskId, "completed_at", nowIso());
        } finally {
            taskStopFlags.remove(taskId);
        }
    }

    /**
     * 根据阶段名称调用对应适配器。
     */
    private void executeStage(String stageName, Task task, Path sessionDir) throws Exception {
        Semaphore gate = stageGates.get(stageName);
        if (gate != null) {
            log.info("等待步骤门控：stage={}, 当前排队={}", stageName, gate.getQueueLength());
            gate.acquire();
        }
        try {
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
            case "asr_correct" -> executeAsrCorrect(task, sessionDir);
            case "asr_fix" -> executeAsrFix(task, sessionDir);
            case "translate" -> executeTranslate(task, sessionDir);
            case "split_audio" -> executeSplitAudio(task, sessionDir);
            case "tts" -> executeTts(task, sessionDir);
            case "merge_audio" -> executeMergeAudio(task, sessionDir);
            case "merge_video" -> executeMergeVideo(task, sessionDir);
            default -> throw new RuntimeException("未知阶段：" + stageName);
            }
        } finally {
            if (gate != null) {
                gate.release();
            }
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

    private void executeAsrCorrect(Task task, Path sessionDir) throws Exception {
        var correctorCfg = settingsService.getProviderConfig(OPENAI_ASR_CORRECTOR,
                AppProperties.AsrCorrectorConfig.OpenaiAsrCorrector.class);
        if (!correctorCfg.isEnabled()) {
            log.info("ASR 纠错未启用，跳过：task={}", task.getId());
            return;
        }

        Path metadataDir = sessionDir.resolve("metadata");
        Path asrFile = metadataDir.resolve("asr.json");
        if (!Files.exists(asrFile)) {
            log.warn("ASR 文件不存在，跳过纠错：task={}", task.getId());
            return;
        }

        String provider = OPENAI_ASR_CORRECTOR;
        AsrCorrector corrector = asrCorrectors.get(provider);
        if (corrector == null) {
            throw new RuntimeException("未找到 ASR 纠错适配器：" + provider);
        }

        corrector.correct(task, asrFile, metadataDir);
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
        Path metadataDir = sessionDir.resolve("metadata");
        Path correctedFile = metadataDir.resolve("asr_corrected.json");
        Path asrFile = Files.exists(correctedFile) ? correctedFile : metadataDir.resolve("asr.json");
        Path fixedFile = metadataDir.resolve("asr_fixed.json");
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

        // 字幕翻译完成后，翻译视频标题用于文件命名
        // 注意：task 是 execute() 开头取的，title 可能已被下载阶段更新，需重新查询
        String title = taskRepository.findById(task.getId()).getTitle();
        if (title != null && !title.isBlank()) {
            Path titleFile = outputDir.resolve(TITLE_BILINGUAL_FILE);
            if (!Files.exists(titleFile)) {
                try {
                    String translated = translator.translateText(title, srcLang, dstLang);
                    ObjectNode titleInfo = objectMapper.createObjectNode();
                    titleInfo.put("original", title);
                    titleInfo.put("translated", translated.isBlank() ? title : translated);
                    titleInfo.put("original_lang", srcLang);
                    titleInfo.put("translated_lang", dstLang);
                    Files.writeString(titleFile, objectMapper.writeValueAsString(titleInfo));
                    log.info("标题翻译完成：task={}, original={}, translated={}", task.getId(), title, translated);
                } catch (Exception e) {
                    log.warn("标题翻译失败，跳过：task={}, error={}", task.getId(), e.getMessage());
                }
            }
        }
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
        Path outputDir = sessionDir.resolve("media");

        if ("subtitle-only".equalsIgnoreCase(task.getExecutionMode())) {
            Path timingsPath = sessionDir.resolve("metadata").resolve("translation." + task.getTargetLanguage() + ".json");
            processor.mergeVideoSubtitleOnly(task, videoPath, timingsPath, outputDir);
        } else {
            Path dubbingPath = sessionDir.resolve("tmp").resolve("audio_dubbing.wav");
            Path bgmPath = sessionDir.resolve("media").resolve("audio_bgm.wav");
            Path timingsPath = sessionDir.resolve("tmp").resolve("timings.json");
            processor.mergeVideo(task, videoPath, dubbingPath, bgmPath, timingsPath, outputDir);
        }
    }

    /**
     * 管线完成后重命名最终视频为双语文件名，格式由语言对决定：
     * - 含中文时：{中文名} - {英文名}.mp4
     * - 不含中文时：{原语言名} - {目标语言名}.mp4
     */
    private void renameFinalVideo(Path sessionDir, String taskId) {
        Path titleFile = sessionDir.resolve("metadata").resolve(TITLE_BILINGUAL_FILE);
        if (!Files.exists(titleFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(titleFile));
            String original = root.path("original").asText("");
            String translated = root.path("translated").asText("");
            String srcLang = root.path("original_lang").asText("");
            String dstLang = root.path("translated_lang").asText("");

            String leftName, rightName;
            boolean srcIsZh = CHINESE_LANG.equals(srcLang);
            boolean dstIsZh = CHINESE_LANG.equals(dstLang);
            boolean srcIsEn = ENGLISH_LANG.equals(srcLang);
            boolean dstIsEn = ENGLISH_LANG.equals(dstLang);

            if (srcIsZh || dstIsZh) {
                // 含中文：保证 {中文名} - {英文名}
                leftName = srcIsZh ? original : translated;
                rightName = srcIsEn ? original : (dstIsEn ? translated : (srcIsZh ? translated : original));
                // 兜底：若另一侧不是英文，fallback 到对方
                if (rightName.equals(leftName)) {
                    if (srcIsZh) { rightName = translated; } else { rightName = original; }
                }
            } else {
                // 不含中文：{original} - {translated}
                leftName = original;
                rightName = translated;
            }

            if (leftName.isBlank()) leftName = rightName;
            if (rightName.isBlank()) rightName = leftName;

            Path finalVideo = sessionDir.resolve("media").resolve(VIDEO_FINAL_FILE);
            if (!Files.exists(finalVideo)) {
                return;
            }

            String newName = FilenameUtils.sanitize(leftName, true) + " - " + FilenameUtils.sanitize(rightName, true) + ".mp4";
            Path newPath = finalVideo.resolveSibling(newName);
            Files.move(finalVideo, newPath);
            log.info("最终视频重命名：{} → {}", finalVideo.getFileName(), newPath.getFileName());
            taskRepository.updateField(taskId, "final_video_path", newPath.toAbsolutePath().toString());
        } catch (Exception e) {
            log.warn("重命名最终视频失败：task={}, error={}", taskId, e.getMessage());
        }
    }

    private TaskStage findStage(List<TaskStage> stages, String name) {
        if (stages == null) {
            return null;
        }
        return stages.stream().filter(s -> name.equals(s.getName())).findFirst().orElse(null);
    }

    private boolean isManualMode(Task task) {
        return "manual".equalsIgnoreCase(task.getExecutionMode());
    }

    private String nowIso() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private record StageDef(String name, String label) {}
}
