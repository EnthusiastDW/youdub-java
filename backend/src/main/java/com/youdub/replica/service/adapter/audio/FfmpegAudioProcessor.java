package com.youdub.replica.service.adapter.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.BinaryResult;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandResult;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.FFMPEG_AUDIO;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * FFmpeg 音频处理适配器。
 * 负责音频切分（按句裁剪原始人声）和音频合并（速度对齐 TTS 片段）。
 */
@Slf4j
@Component(FFMPEG_AUDIO)
@RequiredArgsConstructor
public class FfmpegAudioProcessor implements AudioProcessor {

    private static final long TIMEOUT_MS = 300_000L;
    private static final long PRE_PADDING_MS = 80L;
    private static final long POST_PADDING_MS = 160L;
    private static final int SILENCE_THRESHOLD = 200;          // 静音检测振幅阈值
    private static final long MIN_TRAILING_SILENCE_MS = 80L;    // 裁剪后保留的最小尾部空白（ms）

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Override
    public void splitAudio(Task task, Path vocalsPath, Path translationPath, Path outputDir) throws Exception {
        if (vocalsPath == null || !Files.exists(vocalsPath)) {
            throw new IllegalArgumentException("人声文件不存在：" + vocalsPath);
        }
        if (translationPath == null || !Files.exists(translationPath)) {
            throw new IllegalArgumentException("翻译文件不存在：" + translationPath);
        }

        Path vocalsSegmentDir = outputDir.resolve("vocals");
        Files.createDirectories(vocalsSegmentDir);

        // 读取翻译结果，获取每句的时间戳
        JsonNode root = objectMapper.readTree(Files.readString(translationPath));
        JsonNode translation = root.path("translation");
        if (!translation.isArray()) {
            log.warn("翻译结果为空，跳过切分");
            return;
        }

        String ffmpegPath = settingsService.getGlobalConfig("ffmpeg", AppProperties.Ffmpeg.class).getPath();

        int index = 0;
        for (JsonNode item : translation) {
            long startMs = item.path("start_time").asLong(0);
            long endMs = item.path("end_time").asLong(0);
            if (endMs <= startMs) continue;

            // 小幅时间扩展
            long start = Math.max(0, startMs - PRE_PADDING_MS);
            long end = endMs + POST_PADDING_MS;

            Path outputFile = vocalsSegmentDir.resolve(String.format("%04d.wav", index));
            if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                index++;
                continue;
            }

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(vocalsPath.toString());
            command.add("-ss");
            command.add(formatTime(start));
            command.add("-to");
            command.add(formatTime(end));
            command.add("-c");
            command.add("copy");
            command.add(outputFile.toString());

            try {
                CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
            } catch (RuntimeException e) {
                log.warn("切分片段 {} 失败：{}", index, e.getMessage());
            }
            index++;
        }
        log.info("音频切分完成：task={}, 共 {} 段", task.getId(), index);
    }

    @Override
    public void mergeAudio(Task task, Path ttsDir, Path translationPath, Path outputDir) throws Exception {
        if (ttsDir == null || !Files.exists(ttsDir)) {
            throw new IllegalArgumentException("TTS 目录不存在：" + ttsDir);
        }
        if (translationPath == null || !Files.exists(translationPath)) {
            throw new IllegalArgumentException("翻译文件不存在：" + translationPath);
        }

        Files.createDirectories(outputDir);

        Path dubbingFile = outputDir.resolve("audio_dubbing.wav");
        Path timingsFile = outputDir.resolve("timings.json");
        if (Files.exists(dubbingFile) && Files.exists(timingsFile)) {
            log.info("合并结果已存在，跳过：{}", dubbingFile);
            return;
        }

        // 读取翻译结果
        JsonNode root = objectMapper.readTree(Files.readString(translationPath));
        JsonNode translation = root.path("translation");
        if (!translation.isArray() || translation.isEmpty()) {
            log.warn("翻译结果为空，跳过合并");
            return;
        }

        // 收集所有 TTS 片段
        List<Path> ttsFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(ttsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".wav"))
                    .sorted()
                    .forEach(ttsFiles::add);
        }

        String ffmpegPath = settingsService.getGlobalConfig("ffmpeg", AppProperties.Ffmpeg.class).getPath();
        int sampleRate = 24000;
        int bytesPerMs = sampleRate * 2 / 1000; // 16-bit mono: 48 bytes/ms

        // 按原始时间轴构建配音音频，片段间插入静音保持时间对齐
        ByteArrayOutputStream fullAudio = new ByteArrayOutputStream();
        ArrayNode timingsArray = objectMapper.createArrayNode();
        long currentMs = 0;
        int segIdx = 0;

        for (JsonNode item : translation) {
            String text = item.path("dst").asText("");
            long startMs = item.path("start_time").asLong(0);
            long endMs = item.path("end_time").asLong(0);
            if (endMs <= startMs) {
                continue;
            }

            if (text.isBlank()) {
                // 空片段：跳过，后续非空片段会自动通过 startMs > currentMs 逻辑填充静音
                // 避免使用 asr_fix 膨胀后的 end_time 引入多余的静音
                continue;
            }

            if (segIdx >= ttsFiles.size()) {
                break;
            }
            Path ttsFile = ttsFiles.get(segIdx++);

            // 片头静音：距上一片段结尾到当前开始
            if (startMs > currentMs) {
                int gapBytes = (int) ((startMs - currentMs) * bytesPerMs);
                fullAudio.write(new byte[gapBytes]);
            } else if (currentMs > startMs) {
                // 上一片段超时，当前实际开始后移
                startMs = currentMs;
            }

            // 将 TTS 片段解码为 PCM（16-bit mono 24000Hz）
            Command decodeCmd = Command.builder()
                    .add(ffmpegPath, "-y", "-i", ttsFile.toAbsolutePath().toString(),
                            "-f", "s16le", "-ac", "1", "-ar", String.valueOf(sampleRate), "-")
                    .timeout(30_000)
                    .throwOnNonZero(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .build();
            BinaryResult decodeResult;
            try {
                decodeResult = CommandRunner.runBinary(decodeCmd);
            } catch (RuntimeException e) {
                log.warn("TTS 解码异常: {}, {}", ttsFile, e.getMessage());
                long durMs = endMs - startMs;
                fullAudio.write(new byte[(int) (durMs * bytesPerMs)]);
                currentMs = endMs;
                continue;
            }
            int exitCode = decodeResult.exitCode();
            byte[] pcmData = decodeResult.data();

            if (exitCode != 0 || pcmData.length == 0) {
                log.warn("TTS 片段解码失败: {}, exit={}", ttsFile, exitCode);
                long durMs = endMs - startMs;
                fullAudio.write(new byte[(int) (durMs * bytesPerMs)]);
                currentMs = endMs;
                continue;
            }

            // 淡入淡出：消除片段衔接处的咔嗒声和 mp3 解码结尾噪声
            ShortBuffer sb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int totalSamples = sb.remaining();
            int fadeInSamples = Math.min(sampleRate * 5 / 1000, totalSamples);     // 5ms
            int fadeOutSamples = Math.min(sampleRate * 10 / 1000, totalSamples);   // 10ms
            for (int s = 0; s < fadeInSamples; s++) {
                float gain = (float) s / fadeInSamples;
                sb.put(s, (short) (sb.get(s) * gain));
            }
            for (int s = totalSamples - fadeOutSamples; s < totalSamples; s++) {
                float gain = (float) (totalSamples - s) / fadeOutSamples;
                sb.put(s, (short) (sb.get(s) * gain));
            }

            long actualDurationMs = (long) pcmData.length / bytesPerMs;
            long targetDurationMs = endMs - startMs;

            if (actualDurationMs < targetDurationMs) {
                // 实际 < 目标：不拉伸，保留原始音质；空隙由后续静音填充
                log.debug("片段 {}: actual={}ms < target={}ms，不拉伸", segIdx - 1, actualDurationMs, targetDurationMs);
            } else if (actualDurationMs > targetDurationMs && targetDurationMs > 50) {
                long excessMs = actualDurationMs - targetDurationMs;

                // 优先裁剪尾部空白，避免变速导致的音质损失
                long trailingSilenceMs = detectTrailingSilenceMs(pcmData, sampleRate);
                long trimableMs = Math.max(0, trailingSilenceMs - MIN_TRAILING_SILENCE_MS);
                long trimMs = Math.min(excessMs, trimableMs);

                if (trimMs > 0) {
                    int trimBytes = (int) (trimMs * bytesPerMs);
                    trimBytes = Math.min(trimBytes, pcmData.length);
                    pcmData = Arrays.copyOf(pcmData, pcmData.length - trimBytes);
                    actualDurationMs = (long) pcmData.length / bytesPerMs;
                    excessMs = actualDurationMs - targetDurationMs;
                    log.debug("片段 {}: 裁剪尾部空白 {}ms，剩余超出 {}ms", segIdx - 1, trimMs, Math.max(0, excessMs));
                }

                // 裁剪后仍然超出，用 atempo 加速（仅加速，不减速）
                if (excessMs > 50 && targetDurationMs > 200) {
                    double speed = Math.clamp((double) actualDurationMs / targetDurationMs, 1.0, 2.0);
                    try {
                        pcmData = applyAtempo(ffmpegPath, pcmData, sampleRate, speed);
                        actualDurationMs = (long) pcmData.length / bytesPerMs;
                    } catch (Exception e) {
                        log.warn("时间压缩失败 (speed={}): {}", speed, e.getMessage());
                    }
                }
            }

            fullAudio.write(pcmData);
            currentMs = startMs + actualDurationMs;

            ObjectNode newItem = item.deepCopy();
            newItem.put("actual_start_time", startMs);
            newItem.put("actual_end_time", currentMs);
            timingsArray.add(newItem);
        }

        // 写入 PCM WAV（javax.sound 自动生成正确 WAV 头）
        byte[] audioData = fullAudio.toByteArray();
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        try (var bais = new ByteArrayInputStream(audioData);
             var ais = new AudioInputStream(bais, fmt, audioData.length / 2)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, dubbingFile.toFile());
        }

        ObjectNode timingsRoot = objectMapper.createObjectNode();
        timingsRoot.set("translation", timingsArray);
        Files.writeString(timingsFile, objectMapper.writeValueAsString(timingsRoot));

        log.info("音频合并完成：task={}, dubbing={} ({} bytes), timings={}",
                task.getId(), dubbingFile, audioData.length, timingsFile);
    }

    /**
     * 检测 PCM 16-bit mono 数据尾部连续静音的时长（毫秒）。
     */
    private long detectTrailingSilenceMs(byte[] pcmData, int sampleRate) {
        ShortBuffer sb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int totalSamples = sb.remaining();
        int idx = totalSamples - 1;
        while (idx >= 0 && Math.abs(sb.get(idx)) < SILENCE_THRESHOLD) {
            idx--;
        }
        int silentSamples = totalSamples - 1 - idx;
        return (long) silentSamples * 1000 / sampleRate;
    }

    /**
     * 将毫秒格式化为 HH:MM:SS.mmm 格式。
     */
    private String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long ms = milliseconds % 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
    }

    /**
     * 对 PCM 音频施加 FFmpeg atempo 变速滤波。
     * 使用临时文件而非管道，避免 stdin/stdout 缓冲区死锁。
     */
    private byte[] applyAtempo(String ffmpegPath, byte[] pcmData, int sampleRate, double speed) throws Exception {
        Path tmpInput = Files.createTempFile("atempo_in_", ".pcm");
        Path tmpOutput = Files.createTempFile("atempo_out_", ".pcm");
        try {
            Files.write(tmpInput, pcmData);

            String filter = buildAtempoFilter(speed);
            CommandResult result = CommandRunner.run(Command.builder()
                    .add(ffmpegPath, "-y",
                            "-f", "s16le", "-ac", "1", "-ar", String.valueOf(sampleRate),
                            "-i", tmpInput.toAbsolutePath().toString(),
                            "-filter:a", filter,
                            "-f", "s16le", "-ac", "1", "-ar", String.valueOf(sampleRate),
                            tmpOutput.toAbsolutePath().toString())
                    .timeout(30_000)
                    .throwOnNonZero(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .build());

            if (result.exitCode() != 0 || Files.size(tmpOutput) == 0) {
                throw new RuntimeException("atempo 失败: exit=" + result.exitCode());
            }
            return Files.readAllBytes(tmpOutput);
        } finally {
            Files.deleteIfExists(tmpInput);
            Files.deleteIfExists(tmpOutput);
        }
    }

    /**
     * 构建 atempo 滤波参数字符串，支持 0.5x-2.0x 范围。
     */
    private String buildAtempoFilter(double speed) {
        if (speed >= 0.5 && speed <= 2.0) {
            return String.format("atempo=%.6f", speed);
        }
        List<String> filters = new ArrayList<>();
        while (speed > 2.0) {
            filters.add("atempo=2.0");
            speed /= 2.0;
        }
        while (speed < 0.5) {
            filters.add("atempo=0.5");
            speed /= 0.5;
        }
        filters.add(String.format("atempo=%.6f", speed));
        return String.join(",", filters);
    }
}
