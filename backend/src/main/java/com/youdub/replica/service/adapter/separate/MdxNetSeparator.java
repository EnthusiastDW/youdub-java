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
 * MDX-Net 人声分离适配器。
 * 通过 Python 子进程调用，使用 MDX-Net 模型，分离质量接近 Demucs。
 */
@Slf4j
@Component("mdx-net")
@RequiredArgsConstructor
public class MdxNetSeparator implements SourceSeparator {

    private static final long TIMEOUT_MS = 600_000L;

    @Override
    public String getName() {
        return "mdx-net";
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

        Path tempDir = Files.createTempDirectory("mdx-net");

        try {
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add("-c");
            command.add(String.format("""
                from demucs.apply import apply_model
                from demucs.pretrained import get_model
                model = get_model('mdx_extra_q')
                apply_model(model, ['%s'], '%s', device='%s', shifts=1)
                """, audioPath, tempDir, device != null && !device.isBlank() && !"cpu".equalsIgnoreCase(device) ? device : "cpu"));

            log.info("执行 MDX-Net 分离：task={}, audio={}", task.getId(), audioPath);
            try {
                CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
            } catch (RuntimeException e) {
                throw new RuntimeException("MDX-Net 分离失败（请确认 demucs 已安装：pip install demucs）： " + e.getMessage(), e);
            }

            String baseName = audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            Path separatedDir = tempDir.resolve(baseName);
            Path vocalsFile = separatedDir.resolve("vocals.wav");
            Path noVocalsFile = separatedDir.resolve("no_vocals.wav");

            if (!Files.exists(vocalsFile) || !Files.exists(noVocalsFile)) {
                throw new RuntimeException("MDX-Net 输出文件不存在：" + vocalsFile);
            }

            Files.copy(vocalsFile, vocalsOut, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(noVocalsFile, bgmOut, StandardCopyOption.REPLACE_EXISTING);
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
