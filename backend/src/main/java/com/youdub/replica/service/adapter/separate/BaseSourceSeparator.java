package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandResult;
import com.youdub.replica.util.CommandRunner;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 音源分离适配器基类。
 * 封装了从视频文件中提取音频为 WAV 的公共逻辑，
 * 子类只需关注各自的分离核心逻辑。
 */
@Slf4j
public abstract class BaseSourceSeparator implements SourceSeparator {

    protected static final long EXTRACT_TIMEOUT_MS = 120_000L;

    /**
     * 若输入不是 WAV 文件（如 MP4 视频），则用 FFmpeg 提取音频为 WAV。
     * 已是 WAV 则直接返回原路径，不产生临时文件。
     *
     * @return WAV 文件路径（可能是新提取的临时文件，也可能是原文件）
     */
    protected Path extractAudio(Task task, Path audioPath, Path outputDir) throws Exception {
        String fileName = audioPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".wav")) {
            log.info("输入已是 WAV 格式，跳过提取：task={}, path={}", task.getId(), audioPath);
            return audioPath;
        }

        long inputSize = Files.size(audioPath);
        log.info("提取音频：task={}, input={}, size={}MB", task.getId(), audioPath, inputSize / (1024 * 1024));

        Path tempWav = outputDir.resolve("temp_audio.wav");
        if (!Files.exists(tempWav)) {
            // 用 FFprobe 获取音频时长，据此选择参数确保 WAV 不超过 1GB
            double durationSec = probeDuration(audioPath);
            int sampleRate;
            int channels;
            if (durationSec > 0) {
                // 预估 WAV 大小：duration * sampleRate * channels * 2 (16-bit)
                long estWavMB = (long) (durationSec * 44100 * 2 * 2 / (1024 * 1024)); // 按 44100 立体声估算
                log.info("音频时长 {}min，预估 WAV {}MB", Math.round(durationSec / 60), estWavMB);
                if (estWavMB > 1000) {
                    sampleRate = 16000;
                    channels = 1;
                } else if (estWavMB > 500) {
                    sampleRate = 22050;
                    channels = 1;
                } else {
                    sampleRate = 44100;
                    channels = 2;
                }
            } else {
                // 无法获取时长，保守使用 22050 单声道
                sampleRate = 22050;
                channels = 1;
            }
            log.info("音频提取参数：sampleRate={}, channels={}", sampleRate, channels);

            long t0 = System.currentTimeMillis();
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-i");
            cmd.add(audioPath.toString());
            cmd.add("-vn");
            cmd.add("-acodec");
            cmd.add("pcm_s16le");
            cmd.add("-ar");
            cmd.add(String.valueOf(sampleRate));
            cmd.add("-ac");
            cmd.add(String.valueOf(channels));
            cmd.add("-y");
            cmd.add(tempWav.toString());

            CommandRunner.run(Command.builder().add(cmd).timeout(EXTRACT_TIMEOUT_MS).workDir(outputDir).build());
            long elapsed = System.currentTimeMillis() - t0;
            log.info("音频提取完成：task={}, duration={}ms, output={}", task.getId(), elapsed, tempWav);
        } else {
            log.info("音频提取缓存命中：task={}, path={}", task.getId(), tempWav);
        }
        return tempWav;
    }

    /**
     * 用 FFprobe 获取音频时长（秒）。
     */
    private double probeDuration(Path audioPath) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffprobe");
        cmd.add("-v");
        cmd.add("error");
        cmd.add("-show_entries");
        cmd.add("format=duration");
        cmd.add("-of");
        cmd.add("csv=p=0");
        cmd.add(audioPath.toString());

        CommandResult result = CommandRunner.run(Command.builder()
                .add(cmd)
                .timeout(30_000)
                .throwOnNonZero(false)
                .build());
        if (result.exitCode() != 0) {
            log.warn("FFprobe 获取时长失败：exit={}", result.exitCode());
            return -1;
        }
        String output = result.output().trim();
        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            log.warn("FFprobe 返回异常：{}", output);
            return -1;
        }
    }
}
