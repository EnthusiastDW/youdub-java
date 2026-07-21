package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import site.dengwei.onnxruntime.separator.AudioSeparator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.youdub.replica.service.adapter.AdapterConstants.ONNX;

/**
 * ONNX Runtime 本地人声分离适配器。
 * <p>
 * 在 Java 进程内加载 MDX-NET ONNX 模型完成人声分离（vocals + bgm），
 * 无需外部 Python 服务。模型实例在首次调用时加载并缓存，后续复用。
 */
@Slf4j
@Component(ONNX)
@RequiredArgsConstructor
public class OnnxSeparator extends BaseSourceSeparator {

    private static final String MODEL_FILE = "UVR-MDX-NET-Inst_HQ_3.onnx";
    private static final String MODEL_URL = "https://huggingface.co/debugzxcv/uvr/resolve/main/UVR-MDX-NET-Inst_HQ_3.onnx";
    private final OkHttpClient httpClient;

    private volatile AudioSeparator separator;
    private final Object initLock = new Object();

    @Override
    public void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }

        long inputSize = Files.size(audioPath);
        log.info("ONNX 分离开始：task={}, input={}, size={}MB",
                task.getId(), audioPath, inputSize / (1024 * 1024));

        Files.createDirectories(outputDir);

        Path vocalsOut = outputDir.resolve("audio_vocals.wav");
        Path bgmOut = outputDir.resolve("audio_bgm.wav");
        if (Files.exists(vocalsOut) && Files.exists(bgmOut)) {
            log.info("分离结果已存在，跳过：{}", outputDir);
            return;
        }

        Path wavPath = extractAudio(task, audioPath, outputDir);
        boolean isTemp = !wavPath.equals(audioPath);

        initModel(device);

        long t0 = System.currentTimeMillis();
        separator.separate(wavPath, outputDir);
        long elapsed = System.currentTimeMillis() - t0;

        long vocalSize = Files.size(vocalsOut);
        long bgmSize = Files.size(bgmOut);
        log.info("ONNX 分离完成：task={}, total={}ms, vocals={}MB, bgm={}MB",
                task.getId(), elapsed,
                vocalSize / (1024 * 1024), bgmSize / (1024 * 1024));

        if (isTemp) {
            Files.deleteIfExists(wavPath);
        }
    }

    private void initModel(String device) throws Exception {
        if (separator != null) return;

        synchronized (initLock) {
            if (separator != null) {
                return;
            }

            Path modelPath = resolveModel();
            ensureModelExists(modelPath);

            boolean useGpu = isCudaAvailable()
                    || (device != null && !device.isBlank() && !"cpu".equalsIgnoreCase(device));

            log.info("加载 ONNX 模型：model={}, gpu={}", modelPath, useGpu);

            AudioSeparator sep = new AudioSeparator(modelPath, useGpu);
            sep.warmUp();
            this.separator = sep;
        }
    }

    /**
     * 解析模型文件路径。
     * 搜索顺序：1) data/separator-models/（Docker 挂载目录） 2) models/  3) onnxruntime/models/
     */
    private static Path resolveModel() {
        Path dockerModels = Paths.get("data", "separator-models", MODEL_FILE);
        if (Files.exists(dockerModels)) return dockerModels.toAbsolutePath();

        Path cwdModels = Paths.get("models", MODEL_FILE);
        if (Files.exists(cwdModels)) return cwdModels.toAbsolutePath();

        Path moduleModels = Paths.get("onnxruntime", "models", MODEL_FILE);
        if (Files.exists(moduleModels)) return moduleModels.toAbsolutePath();

        // 默认：下载到 data/separator-models/
        return dockerModels.toAbsolutePath();
    }

    /**
     * 若模型文件不存在，自动从 HuggingFace 下载。
     */
    private void ensureModelExists(Path modelPath) throws IOException {
        if (Files.exists(modelPath)) {
            log.info("模型文件已存在：{}", modelPath);
            return;
        }

        Path parent = modelPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        log.info("下载模型文件（{}）：{}", MODEL_FILE, MODEL_URL);
        log.info("保存到：{}", modelPath);
        log.warn("模型文件约 66.8 MB，请耐心等待...");

        try( Response response = httpClient.newCall(new Request.Builder()
                        .url(MODEL_URL)
                        .build()).execute();
        ) {
            if (response.code() != 200 || response.body() == null) {
                throw new IOException("下载模型失败，HTTP " + response.code()
                        + " — 请手动下载并放到 " + modelPath
                        + "\n  下载地址：" + MODEL_URL);
            }
            long totalBytes;
            try (InputStream in = response.body().byteStream()) {
                totalBytes = Files.copy(in, modelPath);
            }
            log.info("模型下载完成：{} ({} MB)", modelPath, totalBytes / (1024 * 1024));
        }
    }

    /**
     * 检测 CUDA 是否可用（nvidia-smi 可执行且退出码 0）。
     */
    private static boolean isCudaAvailable() {
        try {
            Process process = new ProcessBuilder("nvidia-smi")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("nvidia-smi 不可用，回退到 CPU：{}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    void closeModel() {
        if (separator != null) {
            separator.close();
        }
    }
}
