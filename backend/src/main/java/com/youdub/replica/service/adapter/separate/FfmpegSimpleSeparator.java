package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.adapter.AdapterSkipTracker;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.FFMPEG_SIMPLE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpeg 简易人声分离适配器。
 *
 * 使用 FFmpeg 的 highpass 滤镜进行频率滤波，仅提取人声（≥200Hz）。
 * 不生成 BGM（背景音乐），因为纯频率分割产生的 BGM 噪声很大，
 * 混入后会严重降低配音质量。下游在无 BGM 文件时会自动跳过混音。
 *
 * 如需带 BGM 的完整分离效果，请使用 Demucs（AI 模型）或 audio-separator-api（Docker 服务）。
 */
@Slf4j
@Component(FFMPEG_SIMPLE)
@RequiredArgsConstructor
public class FfmpegSimpleSeparator extends BaseSourceSeparator {

    private static final long TIMEOUT_MS = 120_000L;

    private final AdapterSkipTracker skipTracker;

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        long inputSize = Files.size(audioPath);
        log.info("FFmpeg 分离开始：task={}, input={}, size={}MB", task.getId(), audioPath, inputSize / (1024 * 1024));

        Files.createDirectories(outputDir);

        long tTotal = System.currentTimeMillis();
        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        if (Files.exists(vocalsOut)) {
            log.info("人声分离结果已存在，跳过：{}", outputDir);
            skipTracker.markSkipped();
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

        // 不生成 BGM：FFmpeg 频率滤波（lowpass）产生的 BGM 噪声大、质量差，
        // 混入后会破坏配音效果。直接输出 vocals-only，下游会自动跳过混音。
        // 如需高质量 BGM，请选择 Demucs 或 audio-separator-api。

        if (isTemp) {
            Files.deleteIfExists(wavPath);
        }

        long vocalSize = Files.size(vocalsOut);
        log.info("FFmpeg 分离完成：task={}, total={}ms, vocals={}MB（不生成 BGM）",
                task.getId(), System.currentTimeMillis() - tTotal,
                vocalSize / (1024 * 1024));
    }
}
