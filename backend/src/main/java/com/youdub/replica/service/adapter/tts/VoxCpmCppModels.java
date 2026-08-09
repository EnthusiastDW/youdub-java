package com.youdub.replica.service.adapter.tts;

import lombok.extern.slf4j.Slf4j;
import site.dengwei.onnxruntime.util.Models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * VoxCPM2 GGUF 模型文件管理（llama.cpp-omni voxcpm2-cli 使用）。
 * <p>
 * 负责 BaseLM 与 Acoustic 两个 GGUF 权重的下载和验证。
 * 模型从 HuggingFace 自动下载，所有方法都是幂等的——文件已存在则跳过。
 */
@Slf4j
public final class VoxCpmCppModels {

    /** VoxCPM2 GGUF 权重仓库 */
    private static final String HF_MODEL_BASE = "https://huggingface.co/DennisHuang648/VoxCPM2-GGUF";

    /** BaseLM 推荐量化权重（约 1.6GB） */
    public static final String BASE_LM_MODEL = "VoxCPM2-BaseLM-Q8_0.gguf";
    /** Acoustic 权重（约 1.7GB） */
    public static final String ACOUSTIC_MODEL = "VoxCPM2-Acoustic-F16.gguf";

    /**
     * 可选 BaseLM 权重清单（供前端下拉框选择）。
     * 均存在于 HF DennisHuang648/VoxCPM2-GGUF 仓库，可经 ensureBaseLmModel 懒下载。
     */
    public static final List<String> AVAILABLE_BASE_LM_MODELS = List.of(
            "VoxCPM2-BaseLM-Q8_0.gguf",
            "VoxCPM2-BaseLM-F16.gguf"
    );

    private VoxCpmCppModels() {
    }

    /**
     * 确保 BaseLM 模型存在，缺失则从 HuggingFace 下载。
     *
     * @param modelDir  模型目录
     * @param modelName 模型文件名（如 {@code VoxCPM2-BaseLM-Q8_0.gguf}）
     * @return 模型文件路径
     */
    public static Path ensureBaseLmModel(Path modelDir, String modelName) throws IOException {
        return ensureFile(modelDir, modelName, HF_MODEL_BASE + "/resolve/main/" + modelName);
    }

    /**
     * 确保 Acoustic 模型存在，缺失则从 HuggingFace 下载。
     *
     * @param modelDir  模型目录
     * @param modelName 模型文件名（如 {@code VoxCPM2-Acoustic-F16.gguf}）
     * @return 模型文件路径
     */
    public static Path ensureAcousticModel(Path modelDir, String modelName) throws IOException {
        return ensureFile(modelDir, modelName, HF_MODEL_BASE + "/resolve/main/" + modelName);
    }

    private static Path ensureFile(Path dir, String fileName, String url) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        if (Files.exists(file)) {
            log.info("voxcpm2 模型已存在：{} ({} MB)", file, Files.size(file) / (1024 * 1024));
            return file;
        }
        log.info("下载 voxcpm2 模型：{} ← {}", fileName, url);
        long bytes = Models.download(url, file);
        log.info("voxcpm2 模型下载完成：{} ({} MB)", file, bytes / (1024 * 1024));
        return file;
    }
}
