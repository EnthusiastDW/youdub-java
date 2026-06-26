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
 * Demucs 人声分离适配器。
 * 通过 Python 子进程调用 demucs 模块，使用 HTDemucs-FT 模型。
 */
@Slf4j
@Component("demucs")
@RequiredArgsConstructor
public class DemucsSeparator implements SourceSeparator {

    private static final long TIMEOUT_MS = 600_000L;
    private static final String MODEL_NAME = "htdemucs_ft";

    @Override
    public String getName() {
        return "demucs";
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
        command.add("demucs");
        command.add("--two-stems");
        command.add("vocals");
        command.add("-n");
        command.add(MODEL_NAME);
        command.add("-o");
        command.add(outputDir.toString());
        if (device != null && !device.isBlank() && !"cpu".equalsIgnoreCase(device)) {
            command.add("--device");
            command.add(device);
        }
        command.add(audioPath.toString());

        log.info("执行 Demucs 分离：task={}, audio={}", task.getId(), audioPath);
        try {
            CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
        } catch (RuntimeException e) {
            throw new RuntimeException("Demucs 分离失败（请确认 demucs 已安装：pip install demucs）： " + e.getMessage(), e);
        }

        // Demucs 输出结构：{outputDir}/htdemucs_ft/{baseName}/vocals.wav 和 no_vocals.wav
        Path baseName = Path.of(audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", ""));
        Path separatedDir = outputDir.resolve(MODEL_NAME).resolve(baseName);
        Path vocalsFile = separatedDir.resolve("vocals.wav");
        Path noVocalsFile = separatedDir.resolve("no_vocals.wav");

        if (!Files.exists(vocalsFile) || !Files.exists(noVocalsFile)) {
            throw new RuntimeException("Demucs 输出文件不存在：" + vocalsFile);
        }

        Files.copy(vocalsFile, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(noVocalsFile, bgmOut, StandardCopyOption.REPLACE_EXISTING);
        log.info("分离完成：task={}, vocals={}, bgm={}", task.getId(), vocalsOut, bgmOut);
    }
}
