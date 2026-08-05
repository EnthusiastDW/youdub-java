package site.dengwei.onnxruntime.whisper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.dengwei.onnxruntime.util.Models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whisper ONNX 模型文件管理。
 * <p>
 * 负责 Whisper 模型（encoder + decoder ONNX、config、vocab）的下载和验证。
 * 模型从 HuggingFace {@code onnx-community/{modelName}} 自动下载。
 * 所有方法都是幂等的——文件已存在则跳过。
 */
public final class WhisperModels {

    private static final Logger log = LoggerFactory.getLogger(WhisperModels.class);

    private static final String HF_WHISPER = "https://huggingface.co/onnx-community";

    /** HuggingFace 仓库名 → 回退仓库名映射（用于 repo 重命名/迁移后仍兼容旧配置）。 */
    private static final java.util.Map<String, String> REPO_FALLBACKS = java.util.Map.of(
            "whisper-small",    "whisper-small-ONNX",
            "whisper-medium",   "whisper-medium-ONNX",
            "whisper-medium.en", "whisper-medium.en_timestamped",
            "whisper-large",    "whisper-large-ONNX",
            "whisper-large-v2", "whisper-large-v2-ONNX",
            "whisper-large-v3", "whisper-large-v3-ONNX"
    );

    /** Whisper 模型需下载的文件列表。ONNX 文件在 {@code onnx/} 子目录下。
     *  quantized(int8) 变体为优先选择（内存最小且 IO 保持 float32），
     *  fp32 变体作为回退（WhisperConfig 按优先级自动选择已存在文件）。 */
    private static final String[][] WHISPER_FILES = {
            {"config.json",                     "/raw/main/config.json"},
            {"generation_config.json",          "/raw/main/generation_config.json"},
            {"encoder_model_quantized.onnx",    "/resolve/main/onnx/encoder_model_quantized.onnx"},
            {"encoder_model.onnx",              "/resolve/main/onnx/encoder_model.onnx"},
            {"decoder_model_quantized.onnx",    "/resolve/main/onnx/decoder_model_quantized.onnx"},
            {"decoder_model_merged_quantized.onnx", "/resolve/main/onnx/decoder_model_merged_quantized.onnx"},
            {"decoder_model.onnx",              "/resolve/main/onnx/decoder_model.onnx"},
            {"decoder_model_merged.onnx",       "/resolve/main/onnx/decoder_model_merged.onnx"},
            {"vocab.json",                      "/raw/main/vocab.json"},
            {"merges.txt",                      "/raw/main/merges.txt"},
    };

    private WhisperModels() {}

    /**
     * 确保指定模型目录下所有必需文件存在。
     * 缺失的文件从 HuggingFace {@code onnx-community/{modelName}} 自动下载。
     * 若 {@code modelName} 在 REPO_FALLBACKS 中有映射，且主仓库返回 401，
     * 自动降级到回退仓库（适应 onnx-community 下的 repo 重命名）。
     *
     * @param dir       模型目录
     * @param modelName HuggingFace 仓库名（如 {@code whisper-tiny.en}）
     */
    public static void ensureFiles(Path dir, String modelName) throws IOException {
        Files.createDirectories(dir);
        String fallback = REPO_FALLBACKS.get(modelName);
        try {
            downloadAll(dir, modelName);
        } catch (IOException e) {
            if (fallback != null && e.getMessage() != null && e.getMessage().contains("401")) {
                log.warn("仓库 {} 返回 401, 降级到回退仓库 {}", modelName, fallback);
                downloadAll(dir, fallback);
                return;
            }
            throw e;
        }
    }

    private static void downloadAll(Path dir, String modelName) throws IOException {
        long totalBytes = 0;
        String repoUrl = HF_WHISPER + "/" + modelName;
        for (String[] f : WHISPER_FILES) {
            Path file = dir.resolve(f[0]);
            if (Files.exists(file)) {
                totalBytes += Files.size(file);
                continue;
            }
            String url = repoUrl + f[1];
            log.info("下载 Whisper 模型文件：{} ({})", f[0], url);
            long bytes = Models.download(url, file);
            totalBytes += bytes;
            log.info("  ✓ {} ({} MB)", f[0], bytes / (1024 * 1024));
        }
        log.info("Whisper 模型就绪：{} ({} files, {} MB)", dir,
                WHISPER_FILES.length, totalBytes / (1024 * 1024));
    }

    /**
     * 等同于 {@link #ensureFiles(Path, String)}，模型名从目录名推断。
     */
    public static void ensureFiles(Path dir) throws IOException {
        String modelName = dir.getFileName().toString();
        ensureFiles(dir, modelName);
    }
}
