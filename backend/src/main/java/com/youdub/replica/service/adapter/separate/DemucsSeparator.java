package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;

import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.DEMUCS;

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
@Component(DEMUCS)
@RequiredArgsConstructor
public class DemucsSeparator extends BaseSourceSeparator {
    private static final long TIMEOUT_MS = 600_000L;

    private final SettingsService settingsService;

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        long tTotal = System.currentTimeMillis();

        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        long inputSize = Files.size(audioPath);
        log.info("Demucs 分离开始：task={}, input={}, size={}MB, device={}",
                task.getId(), audioPath, inputSize / (1024 * 1024), device != null ? device : "cpu");

        Files.createDirectories(outputDir);

        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");
        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }
        String model = settingsService.getProviderConfig(DEMUCS, AppProperties.Separate.Demucs.class).getModel();
        if (model == null || model.isBlank()) {
            model = "htdemucs_ft";
        }

        List<String> command = new ArrayList<>();
        command.add("python");
        command.add("-m");
        command.add("demucs");
        command.add("--two-stems");
        command.add("vocals");
        command.add("-n");

        command.add(model);
        command.add("-o");
        command.add(outputDir.toString());
        boolean useGpu = device != null && !device.isBlank() && !"cpu".equalsIgnoreCase(device);
        if (useGpu) {
            command.add("--device");
            command.add(device);
        }
        command.add(audioPath.toString());

        log.info("执行 Demucs 分离：task={}, model={}, device={}", task.getId(), model, useGpu ? device : "cpu");
        long t0 = System.currentTimeMillis();
        try {
            CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
        } catch (RuntimeException e) {
            throw new RuntimeException("Demucs 分离失败（请确认 demucs 已安装：pip install demucs）： " + e.getMessage(), e);
        }
        long demucsElapsed = System.currentTimeMillis() - t0;
        log.info("Demucs 推理完成：task={}, duration={}ms", task.getId(), demucsElapsed);

        // Demucs 输出结构：{outputDir}/htdemucs_ft/{baseName}/vocals.wav 和 no_vocals.wav
        Path baseName = Path.of(audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", ""));
        Path separatedDir = outputDir.resolve(model).resolve(baseName);
        Path vocalsFile = separatedDir.resolve("vocals.wav");
        Path noVocalsFile = separatedDir.resolve("no_vocals.wav");

        if (!Files.exists(vocalsFile) || !Files.exists(noVocalsFile)) {
            throw new RuntimeException("Demucs 输出文件不存在：" + vocalsFile);
        }

        t0 = System.currentTimeMillis();
        Files.copy(vocalsFile, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(noVocalsFile, bgmOut, StandardCopyOption.REPLACE_EXISTING);
        log.info("结果文件复制完成：task={}, duration={}ms", task.getId(), System.currentTimeMillis() - t0);

        long vocalSize = Files.size(vocalsOut);
        long bgmSize = Files.size(bgmOut);
        log.info("Demucs 分离完成：task={}, total={}ms, vocals={}MB, bgm={}MB",
                task.getId(), System.currentTimeMillis() - tTotal,
                vocalSize / (1024 * 1024), bgmSize / (1024 * 1024));
    }
}
