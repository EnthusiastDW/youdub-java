package com.youdub.replica.service.adapter.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.repository.TaskRepository;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.service.adapter.AdapterSkipTracker;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.FFMPEG_VIDEO;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 视频处理适配器。
 * 混合配音音轨和背景音乐，生成 SRT 字幕，合成最终视频。
 */
@Slf4j
@Component(FFMPEG_VIDEO)
@RequiredArgsConstructor
public class FfmpegVideoProcessor implements VideoProcessor {

    private static final long TIMEOUT_MS = -1L;
    private static final double DUBBING_VOLUME = 1.0;
    private static final double BGM_VOLUME = 1.0;

    private volatile String videoEncoder;
    private volatile List<String> encoderArgs;

    private static final String[] HW_ENCODERS = {
        "h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox"
    };
    private static final String[][] HW_ENCODER_ARGS = {
        {"-preset", "p7", "-cq", "23"},
        {"-preset", "veryfast", "-global_quality", "23"},
        {"-quality", "quality", "-qp_i", "23", "-qp_p", "23"},
        {"-quality", "100"},
    };

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;
    private final TaskRepository taskRepository;
    private final AdapterSkipTracker skipTracker;

    /**
     * Auto-detect the best available video encoder.
     *
     * Priority:
     * 1. `app.ffmpeg.encoder` override (nvenc / qsv / amf / videotoolbox)
     * 2. Probe `ffmpeg -hide_banner -encoders` for hardware encoders
     * 3. Fallback to software libx264
     *
     * Results are cached in `videoEncoder` and `encoderArgs`.
     */
    private String detectEncoder() {
        // 配置覆盖：nvenc / qsv / amf / videotoolbox / software
        AppProperties.Ffmpeg config = settingsService.getGlobalConfig("ffmpeg", AppProperties.Ffmpeg.class);
        String override = config.getEncoder();
        if (override != null && !override.isBlank()) {
            String lower = override.toLowerCase();
            for (int i = 0; i < HW_ENCODERS.length; i++) {
                if (lower.contains(HW_ENCODERS[i].replace("h264_", ""))) {
                    videoEncoder = HW_ENCODERS[i];
                    encoderArgs = List.of(HW_ENCODER_ARGS[i]);
                    log.info("使用配置的编码器: {} (override)", videoEncoder);
                    return videoEncoder;
                }
            }
            videoEncoder = "libx264";
            encoderArgs = List.of("-preset", "veryfast", "-crf", "23");
            log.info("编码器配置 {} 不识别，回退 libx264", override);
            return videoEncoder;
        }

        String ffmpegPath = config.getPath();
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            videoEncoder = "libx264";
            encoderArgs = List.of("-preset", "veryfast", "-crf", "23");
            return videoEncoder;
        }

        try {
            Process process = new ProcessBuilder(ffmpegPath, "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(3, TimeUnit.SECONDS);
            process.destroyForcibly();

            for (int i = 0; i < HW_ENCODERS.length; i++) {
                if (output.contains(HW_ENCODERS[i])) {
                    log.info("检测到硬件编码器: {}，正在验证...", HW_ENCODERS[i]);
                    if (probeEncoderWorks(ffmpegPath, HW_ENCODERS[i], HW_ENCODER_ARGS[i])) {
                        videoEncoder = HW_ENCODERS[i];
                        encoderArgs = List.of(HW_ENCODER_ARGS[i]);
                        log.info("硬件编码器可用: {}", videoEncoder);
                        return videoEncoder;
                    }
                    log.warn("硬件编码器 {} 不可用（驱动/库缺失），跳过", HW_ENCODERS[i]);
                }
            }
        } catch (Exception e) {
            log.warn("探测硬件编码器失败: {}", e.getMessage());
        }

        log.info("未检测到硬件编码器，使用 libx264 软件编码");
        videoEncoder = "libx264";
        encoderArgs = List.of("-preset", "veryfast", "-crf", "23");
        return videoEncoder;
    }

    /**
     * Thread-safe lazy initializer for encoder detection.
     *
     * Uses double-checked locking so `detectEncoder()` runs at most once
     * on the first call to `mergeVideo`.
     */
    private void ensureEncoder() {
        if (videoEncoder == null) {
            synchronized (this) {
                if (videoEncoder == null) {
                    detectEncoder();
                }
            }
        }
    }

    /**
     * Verify a hardware encoder actually works by encoding a single null frame.
     *
     * Some FFmpeg builds list encoders that are compiled in but fail at runtime
     * due to missing drivers (e.g., `nvcuda.dll` for NVENC).
     */
    private boolean probeEncoderWorks(String ffmpegPath, String encoder, String[] args) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-hide_banner");
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-i");
            cmd.add("nullsrc=s=1920x1080:d=0.1");
            cmd.add("-c:v");
            cmd.add(encoder);
            cmd.addAll(List.of(args));
            cmd.add("-f");
            cmd.add("null");
            cmd.add("-");

            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("编码器 {} 探针超时（5s），强制终止", encoder);
                process.destroyForcibly();
                return false;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (output.contains("Cannot load") || output.contains("Error while opening encoder")) {
                log.warn("编码器探针失败: {}", output.lines().filter(l -> l.contains("Cannot load") || l.contains("Error")).findFirst().orElse(""));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("编码器探针异常: {}", e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Merge dubbing audio, BGM, and subtitles into the final video.
     *
     * Pipeline:
     * 1. Generate SRT subtitles from `timingsPath` (translation timings)
     * 2. Mix dubbing + background music via `mixAudio()` (if BGM exists)
     * 3. FFmpeg: overlay subtitles + replace audio track → output mp4
     *
     * Encoder is auto-detected on first call (see `detectEncoder()`).
     * Skips if `video_final.mp4` already exists and is non-empty.
     *
     * @param task        current task (for DB updates)
     * @param videoPath   original video file
     * @param dubbingPath dubbed audio (mono mp3-in-wav, 24kHz)
     * @param bgmPath     background music (optional, can be null)
     * @param timingsPath translation timings JSON (optional, can be null)
     * @param outputDir   output directory
     */
    @Override
    public void mergeVideo(Task task, Path videoPath, Path dubbingPath, Path bgmPath, Path timingsPath, Path outputDir) throws Exception {
        if (videoPath == null || !Files.exists(videoPath)) {
            throw new IllegalArgumentException("视频文件不存在：" + videoPath);
        }
        if (dubbingPath == null || !Files.exists(dubbingPath)) {
            throw new IllegalArgumentException("配音文件不存在：" + dubbingPath);
        }

        Files.createDirectories(outputDir);

        Path finalVideo = outputDir.resolve("video_final.mp4");
        if (Files.exists(finalVideo) && Files.size(finalVideo) > 0) {
            log.info("最终视频已存在，跳过：{}", finalVideo);
            taskRepository.updateField(task.getId(), "final_video_path", finalVideo.toAbsolutePath().toString());
            skipTracker.markSkipped();
            return;
        }

        String ffmpegPath = settingsService.getGlobalConfig("ffmpeg", AppProperties.Ffmpeg.class).getPath();

        // 步骤1：生成 SRT 字幕文件
        Path srtFile = outputDir.resolve("subtitles.srt");
        if (timingsPath != null && Files.exists(timingsPath)) {
            generateSrt(timingsPath, srtFile, task.getTargetLanguage());
        }

        // 步骤2：混合配音和 BGM
        Path mixedAudio = outputDir.resolve("audio_mixed.m4a");
        if (bgmPath != null && Files.exists(bgmPath)) {
            mixAudio(dubbingPath, bgmPath, mixedAudio, ffmpegPath);
        } else {
            // 没有 BGM，直接使用配音
            mixedAudio = dubbingPath;
        }

        // 步骤3：合成最终视频
        ensureEncoder();

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-i");
        command.add(videoPath.toString());
        command.add("-i");
        command.add(mixedAudio.toString());

        boolean hasSrt = Files.exists(srtFile);
        if (hasSrt) {
            // 硬字幕（烧入画面）：所有编码器统一使用 subtitles filter
            // 用相对路径避免 Windows 冒号冲突
            command.add("-vf");
            command.add("subtitles=filename='subtitles.srt':force_style='FontName=Noto Sans CJK SC,FontSize=20,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=2,Shadow=1'");
        }

        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("1:a:0");

        command.add("-c:v");
        command.add(videoEncoder);
        command.addAll(encoderArgs);

        command.add("-c:a");
        command.add("aac");

        command.add("-movflags");
        command.add("+faststart");
        command.add("-shortest");
        command.add(finalVideo.toString());

        log.info("合成最终视频：task={}, video={}", task.getId(), finalVideo);
        CommandRunner.run(Command.builder().add(command).maxOutputLines(1000).timeout(TIMEOUT_MS).workDir(outputDir).build());

        if (!Files.exists(finalVideo)) {
            throw new RuntimeException("最终视频生成失败：" + finalVideo);
        }

        taskRepository.updateField(task.getId(), "final_video_path", finalVideo.toAbsolutePath().toString());
        log.info("视频合成完成：task={}, file={}", task.getId(), finalVideo);
    }

    /**
     * Mix dubbing and BGM into a single AAC audio file.
     *
     * Uses FFmpeg `volume` + `amix` filter graph:
     * ```
     * [0:a]volume=1.0[a0]; [1:a]volume=0.30[a1]; [a0][a1]amix=inputs=2:duration=longest:normalize=0
     * ```
     * Output is AAC in M4A container, same duration as the longest input.
     *
     * @param dubbing    dubbed audio input (input 0)
     * @param bgm        background music input (input 1)
     * @param output     output M4A path
     * @param ffmpegPath path to FFmpeg binary
     */
    private void mixAudio(Path dubbing, Path bgm, Path output, String ffmpegPath) throws Exception {
        String filter = String.format(
                "[0:a]volume=%.2f[a0];[1:a]volume=%.2f[a1];[a0][a1]amix=inputs=2:duration=longest:normalize=0,afade=t=in:d=0.03[aout]",
                DUBBING_VOLUME, BGM_VOLUME
        );

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-fflags");
        command.add("+genpts");
        command.add("-i");
        command.add(dubbing.toString());
        command.add("-i");
        command.add(bgm.toString());
        command.add("-filter_complex");
        command.add(filter);
        command.add("-map");
        command.add("[aout]");
        command.add("-c:a");
        command.add("aac");
        command.add(output.toString());

        log.info("混合音频：dubbing={}, bgm={}, output={}", dubbing, bgm, output);
        CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(output.getParent()).build());
    }

    /**
     * Generate an SRT subtitle file from translation timings JSON.
     *
     * Expected JSON structure:
     * ```json
     * { "translation": [{ "dst": "text", "actual_start_time": 0, "actual_end_time": 1000 }, ...] }
     * ```
     *
     * Falls back to `start_time` / `end_time` when `actual_*` fields are absent.
     * Skips entries with empty `dst`.
     *
     * @param timingsPath   input JSON file
     * @param srtFile       output SRT file
     * @param targetLanguage language tag (logged only)
     */
    private void generateSrt(Path timingsPath, Path srtFile, String targetLanguage) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(timingsPath));
        JsonNode translation = root.path("translation");
        if (!translation.isArray()) {
            log.warn("timings 中没有 translation 数组，跳过 SRT 生成");
            return;
        }

        StringBuilder srt = new StringBuilder();
        int index = 1;
        for (JsonNode item : translation) {
            String text = item.path("dst").asText("").trim();
            if (text.isEmpty()) continue;

            long startMs = item.path("actual_start_time").asLong(item.path("start_time").asLong(0));
            long endMs = item.path("actual_end_time").asLong(item.path("end_time").asLong(0));

            srt.append(index).append("\n");
            srt.append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs)).append("\n");
            srt.append(text).append("\n\n");
            index++;
        }

        Files.writeString(srtFile, srt.toString());
        log.info("SRT 字幕生成完成：{}", srtFile);
    }

    /**
     * Format milliseconds to SRT timestamp (`HH:MM:SS,mmm`).
     *
     * Example: `1500` → `00:00:01,500`
     *
     * @param milliseconds time in milliseconds
     * @return formatted SRT timestamp string
     */
    private String formatSrtTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long ms = milliseconds % 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, ms);
    }
}
