package com.youdub.replica.service.adapter.asr;

import lombok.extern.slf4j.Slf4j;
import site.dengwei.onnxruntime.util.Models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * whisper.cpp 模型文件管理（GGML 格式）。
 * <p>
 * 负责 Q5_0 转写模型与 Silero-VAD 模型的下载和验证。
 * 模型从 HuggingFace 自动下载，所有方法都是幂等的——文件已存在则跳过。
 */
@Slf4j
public final class WhisperCppModels {

    /** whisper.cpp GGML 模型仓库（含 ggml-* 量化模型） */
    private static final String HF_MODEL_BASE = "https://huggingface.co/ggerganov/whisper.cpp";
    /** Silero-VAD GGML 模型仓库 */
    private static final String HF_VAD_BASE = "https://huggingface.co/ggml-org/whisper-vad";

    /** 转写模型（大模型，懒下载） */
    public static final String WHISPER_MODEL = "ggml-large-v3-turbo-q5_0.bin";
    /** Silero-VAD 模型（小文件） */
    public static final String VAD_MODEL = "ggml-silero-v6.2.0.bin";

    private WhisperCppModels() {
    }

    /**
     * 确保转写模型存在，缺失则从 HuggingFace 下载。
     *
     * @param modelDir  模型目录
     * @param modelName 模型文件名（如 {@code ggml-large-v3-turbo-q5_0.bin}）
     * @return 模型文件路径
     */
    public static Path ensureWhisperModel(Path modelDir, String modelName) throws IOException {
        return ensureFile(modelDir, modelName, HF_MODEL_BASE + "/resolve/main/" + modelName);
    }

    /**
     * 确保 Silero-VAD 模型存在，缺失则从 HuggingFace 下载。
     *
     * @param vadModelDir 模型目录
     * @param vadModel    模型文件名（如 {@code ggml-silero-v6.2.0.bin}）
     * @return VAD 模型文件路径
     */
    public static Path ensureVadModel(Path vadModelDir, String vadModel) throws IOException {
        return ensureFile(vadModelDir, vadModel, HF_VAD_BASE + "/resolve/main/" + vadModel);
    }

    private static Path ensureFile(Path dir, String fileName, String url) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        if (Files.exists(file)) {
            log.info("whisper.cpp 模型已存在：{} ({} MB)", file, Files.size(file) / (1024 * 1024));
            return file;
        }
        log.info("下载 whisper.cpp 模型：{} ← {}", fileName, url);
        long bytes = Models.download(url, file);
        log.info("whisper.cpp 模型下载完成：{} ({} MB)", file, bytes / (1024 * 1024));
        return file;
    }
}
