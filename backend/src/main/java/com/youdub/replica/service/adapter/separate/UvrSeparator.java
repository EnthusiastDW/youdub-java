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
 * UVR5 人声分离适配器。
 * 通过 Python 子进程调用 UVR 模型，适用于处理复杂混音。
 */
@Slf4j
@Component("uvr")
@RequiredArgsConstructor
public class UvrSeparator implements SourceSeparator {

    private static final long TIMEOUT_MS = 600_000L;

    @Override
    public String getName() {
        return "uvr";
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

        Path tempDir = Files.createTempDirectory("uvr");

        try {
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add("-c");
            command.add(String.format("""
                import sys
                sys.path.insert(0, './UVR5')
                from uvr5_lib import UVR5
                uvr = UVR5(model_path='uvr5_weights/model.pth', device='%s')
                uvr.separate('%s', '%s')
                """, device != null && !device.isBlank() && !"cpu".equalsIgnoreCase(device) ? "cuda" : "cpu", audioPath, tempDir));

            log.info("执行 UVR5 分离：task={}, audio={}", task.getId(), audioPath);
            try {
                CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
            } catch (RuntimeException e) {
                throw new RuntimeException("UVR5 分离失败（请确认 UVR5 已安装并配置模型路径）： " + e.getMessage(), e);
            }

            Path vocalsFile = tempDir.resolve("vocals.wav");
            Path instrumentalFile = tempDir.resolve("instrumental.wav");

            if (!Files.exists(vocalsFile)) {
                vocalsFile = tempDir.resolve("Vocal.wav");
            }
            if (!Files.exists(instrumentalFile)) {
                instrumentalFile = tempDir.resolve("Instrumental.wav");
            }

            if (!Files.exists(vocalsFile) || !Files.exists(instrumentalFile)) {
                throw new RuntimeException("UVR5 输出文件不存在");
            }

            Files.copy(vocalsFile, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(instrumentalFile, bgmOut, StandardCopyOption.REPLACE_EXISTING);
            log.info("分离完成：task={}, vocals={}, bgm={}", task.getId(), vocalsOut, bgmOut);
        } finally {
            try {
                java.nio.file.Files.walk(tempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.delete(p);
                            } catch (Exception e) {
                                log.warn("清理临时文件失败：{}", p);
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }
}
