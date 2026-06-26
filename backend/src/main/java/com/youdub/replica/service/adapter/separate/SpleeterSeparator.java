package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Spleeter 人声分离适配器。
 * 通过 Python 子进程调用 spleeter 模块，使用 2-stems 模型。
 */
@Slf4j
@Component("spleeter")
@RequiredArgsConstructor
public class SpleeterSeparator implements SourceSeparator {

    private static final long TIMEOUT_MS = 600_000L;

    @Override
    public String getName() {
        return "spleeter";
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

        List<String> command = new ArrayList<>();
        command.add("python");
        command.add("-m");
        command.add("spleeter");
        command.add("separate");
        command.add("-i");
        command.add(audioPath.toString());
        command.add("-o");
        command.add(outputDir.toString());
        command.add("-p");
        command.add("spleeter:2stems");

        log.info("执行 Spleeter 分离：task={}, audio={}", task.getId(), audioPath);
        try {
            CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
        } catch (RuntimeException e) {
            throw new RuntimeException("Spleeter 分离失败（请确认 spleeter 已安装：pip install spleeter）： " + e.getMessage(), e);
        }

        // Spleeter 输出结构：{outputDir}/{baseName}/vocals.wav 和 accompaniment.wav
        Path baseName = Path.of(audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", ""));
        Path separatedDir = outputDir.resolve(baseName);
        Path vocalsFile = separatedDir.resolve("vocals.wav");
        Path accompanimentFile = separatedDir.resolve("accompaniment.wav");

        if (!Files.exists(vocalsFile) || !Files.exists(accompanimentFile)) {
            throw new RuntimeException("Spleeter 输出文件不存在：" + vocalsFile);
        }

        Files.copy(vocalsFile, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(accompanimentFile, bgmOut, StandardCopyOption.REPLACE_EXISTING);
        log.info("分离完成：task={}, vocals={}, bgm={}", task.getId(), vocalsOut, bgmOut);
    }
}
