package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
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
public class FfmpegSimpleSeparator extends BaseSourceSeparator {

    private static final long TIMEOUT_MS = 120_000L;

    @Override
    public String getName() {
        return "ffmpeg-simple";
    }

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        long tTotal = System.currentTimeMillis();

        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        long inputSize = Files.size(audioPath);
        log.info("FFmpeg 分离开始：task={}, input={}, size={}MB", task.getId(), audioPath, inputSize / (1024 * 1024));

        Files.createDirectories(outputDir);

        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");
        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }

        Path wavPath = extractAudio(task, audioPath, outputDir);
        boolean isTemp = !wavPath.equals(audioPath);

        long t0 = System.currentTimeMillis();
        List<String> vocalsCmd = new ArrayList<>();
        vocalsCmd.add("ffmpeg");
        vocalsCmd.add("-i");
        vocalsCmd.add(wavPath.toString());
        vocalsCmd.add("-af");
        vocalsCmd.add("highpass=f=200");
        vocalsCmd.add("-y");
        vocalsCmd.add(vocalsOut.toString());

        log.info("FFmpeg 提取人声：task={}", task.getId());
        CommandRunner.run(Command.builder().add(vocalsCmd).timeout(TIMEOUT_MS).workDir(outputDir).build());
        log.info("人声过滤完成：task={}, duration={}ms, output={}", task.getId(), System.currentTimeMillis() - t0, vocalsOut);

        t0 = System.currentTimeMillis();
        List<String> bgmCmd = new ArrayList<>();
        bgmCmd.add("ffmpeg");
        bgmCmd.add("-i");
        bgmCmd.add(wavPath.toString());
        bgmCmd.add("-af");
        bgmCmd.add("lowpass=f=300");
        bgmCmd.add("-y");
        bgmCmd.add(bgmOut.toString());

        log.info("FFmpeg 提取背景音乐：task={}", task.getId());
        CommandRunner.run(Command.builder().add(bgmCmd).timeout(TIMEOUT_MS).workDir(outputDir).build());
        log.info("背景音乐过滤完成：task={}, duration={}ms, output={}", task.getId(), System.currentTimeMillis() - t0, bgmOut);

        if (isTemp) {
            Files.deleteIfExists(wavPath);
        }

        long vocalSize = Files.size(vocalsOut);
        long bgmSize = Files.size(bgmOut);
        log.info("FFmpeg 分离完成：task={}, total={}ms, vocals={}MB, bgm={}MB",
                task.getId(), System.currentTimeMillis() - tTotal,
                vocalSize / (1024 * 1024), bgmSize / (1024 * 1024));
    }
}
