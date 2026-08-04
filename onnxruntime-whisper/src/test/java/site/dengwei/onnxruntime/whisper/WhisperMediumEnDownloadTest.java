package site.dengwei.onnxruntime.whisper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 whisper-medium.en 能否用当前 WhisperModels 下载逻辑拉取（只下载 config 等小文件，
 * 确认代理/网络可达，不下载大 ONNX）。
 */
@Tag("integration")
class WhisperMediumEnDownloadTest {

    private static Path projectRoot() {
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        if (cwd.toString().contains("onnxruntime")) {
            return cwd.getParent();
        }
        return cwd;
    }

    private static final Path MODEL_DIR = projectRoot().resolve("backend/data/whisper-models/whisper-medium.en");

    @Test
    void downloadSmallFiles() throws Exception {
        Files.createDirectories(MODEL_DIR);
        // ensureFiles 会尝试下载所有缺失文件（含大 ONNX）。
        // 这里不调用它，只测试 Models.download 对单个小文件的连通性。
        long bytes = site.dengwei.onnxruntime.util.Models.download(
                "https://huggingface.co/onnx-community/whisper-medium.en/raw/main/config.json",
                MODEL_DIR.resolve("config.json"));
        System.out.println("config.json 下载成功: " + bytes + " bytes -> " + MODEL_DIR.resolve("config.json"));
        assertTrue(bytes > 100, "config.json 太小，可能不是有效文件: " + bytes);
    }
}
