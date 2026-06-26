package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpeg 简易人声分离适配器。
 * 使用 FFmpeg 的 highpass/lowpass 滤镜进行简单的频率分离。
 */
@Slf4j
@Component("ffmpeg-simple")
@RequiredArgsConstructor
public class FfmpegSimpleSeparator implements SourceSeparator {

    private static final long TIMEOUT_MS = 120_000L;

    @Override
    public String getName() {
        return "ffmpeg-simple";
    }

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        Files.createDirectories(outputDir);

        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");
        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }

        Path tempWav = outputDir.resolve("temp_audio.wav");
        if (!Files.exists(tempWav)) {
            List<String> extractCmd = new ArrayList<>();
            extractCmd.add("ffmpeg");
            extractCmd.add("-i");
            extractCmd.add(audioPath.toString());
            extractCmd.add("-vn");
            extractCmd.add("-acodec");
            extractCmd.add("pcm_s16le");
            extractCmd.add("-ar");
            extractCmd.add("44100");
            extractCmd.add("-ac");
            extractCmd.add("2");
            extractCmd.add("-y");
            extractCmd.add(tempWav.toString());

            log.info("提取音频：task={}", task.getId());
            CommandRunner.run(Command.builder().add(extractCmd).timeout(TIMEOUT_MS).workDir(outputDir).build());
        }

        List<String> vocalsCmd = new ArrayList<>();
        vocalsCmd.add("ffmpeg");
        vocalsCmd.add("-i");
        vocalsCmd.add(tempWav.toString());
        vocalsCmd.add("-af");
        vocalsCmd.add("highpass=f=200");
        vocalsCmd.add("-y");
        vocalsCmd.add(vocalsOut.toString());

        log.info("FFmpeg 提取人声：task={}", task.getId());
        CommandRunner.run(Command.builder().add(vocalsCmd).timeout(TIMEOUT_MS).workDir(outputDir).build());

        List<String> bgmCmd = new ArrayList<>();
        bgmCmd.add("ffmpeg");
        bgmCmd.add("-i");
        bgmCmd.add(tempWav.toString());
        bgmCmd.add("-af");
        bgmCmd.add("lowpass=f=300");
        bgmCmd.add("-y");
        bgmCmd.add(bgmOut.toString());

        log.info("FFmpeg 提取背景音乐：task={}", task.getId());
        CommandRunner.run(Command.builder().add(bgmCmd).timeout(TIMEOUT_MS).workDir(outputDir).build());

        Files.deleteIfExists(tempWav);
        log.info("分离完成（简易模式）：task={}, vocals={}, bgm={}", task.getId(), vocalsOut, bgmOut);
    }
}
