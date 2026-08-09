package site.dengwei.onnxruntime.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP 下载和模型路径解析工具。
 * <p>
 * 提供跨领域的传输基础设施：代理配置、HTTP 下载、模型目录搜索。
 * 领域特定的下载职责（如 Whisper 文件清单、MDX-NET 模型）由
 * {@code WhisperModels} 和 {@code SeparatorModels} 承担。
 * <p>
 * 使用 Java 内置 {@link HttpClient}，无额外依赖。
 */
public final class Models {

    private static final Logger log = LoggerFactory.getLogger(Models.class);
    private static final HttpClient HTTP = createHttpClient();

    /**
     * 创建 HttpClient，代理按优先从系统属性 {@code http.proxyHost} / {@code http.proxyPort} 读取，
     * 回退到环境变量 {@code HTTPS_PROXY} / {@code HTTP_PROXY}，都未配置则无代理。
     */
    private static HttpClient createHttpClient() {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL);
        resolveProxy().ifPresent(builder::proxy);
        return builder.build();
    }

    private static Optional<ProxySelector> resolveProxy() {
        // 1. 系统属性（优先级最高）
        String host = System.getProperty("http.proxyHost");
        String portStr = System.getProperty("http.proxyPort");
        if (host != null && !host.isBlank()) {
            int port = 7890;
            if (portStr != null && !portStr.isBlank()) {
                try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
            }
            log.info("使用系统属性代理: {}:{}", host, port);
            return Optional.of(ProxySelector.of(new InetSocketAddress(host, port)));
        }
        // 2. 环境变量（次优先）
        String env = System.getenv("HTTPS_PROXY");
        if (env == null || env.isBlank()) env = System.getenv("HTTP_PROXY");
        if (env != null && !env.isBlank()) {
            try {
                URI uri = URI.create(env);
                String envHost = uri.getHost();
                int envPort = uri.getPort() > 0 ? uri.getPort() : 7890;
                if (envHost != null) {
                    log.info("使用环境变量代理: {}:{}", envHost, envPort);
                    return Optional.of(ProxySelector.of(new InetSocketAddress(envHost, envPort)));
                }
            } catch (Exception e) {
                log.warn("解析代理环境变量失败: {}", env);
            }
        }
        // 3. 无代理
        return Optional.empty();
    }

    private Models() {}

    // ──────────────────────────── 传输 ────────────────────────────

    /**
     * 下载文件到临时路径，下载成功后再原子重命名为目标路径。
     * <p>
     * 若下载过程中抛出异常，自动删除临时文件，避免破损文件残留在磁盘上。
     * 下载中断后重试时不会错误地认为文件已存在而跳过。
     */
    public static long download(String url, Path dest) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(30));
            String hfToken = System.getenv("HF_TOKEN");
            if (hfToken != null && !hfToken.isBlank()) {
                builder.header("Authorization", "Bearer " + hfToken);
            }
            HttpRequest request = builder.build();
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " — " + url);
            }
            long bytes;
            try (InputStream in = response.body()) {
                bytes = Files.copy(in, tmp);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return bytes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载中断：" + url, e);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /** 静默删除文件，忽略删除失败。 */
    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    // ──────────────────────────── 模型目录搜索 ────────────────────────────

    /**
     * 在标准候选路径中搜索模型目录。
     * <p>
     * 搜索顺序：{@code data/{subDir}/{modelName}} → {@code models/{modelName}} → {@code onnxruntime/models/{modelName}}。
     * 均未找到时返回 {@code data/{subDir}/{modelName}} 作为默认下载目标路径。
     *
     * @param modelName 模型名称（目录名）
     * @param subDir    data 下的子目录名（如 {@code "whisper-models"}）
     * @return 已存在的模型目录绝对路径，或默认目录绝对路径（目录可能不存在）
     */
    public static Path resolveModelDir(String modelName, String subDir) {
        Path[] candidates = {
                Paths.get("data", subDir, modelName),
                Paths.get("models", modelName),
                Paths.get("onnxruntime", "models", modelName),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath();
            }
        }
        return candidates[0].toAbsolutePath();
    }

    /**
     * 同 {@link #resolveModelDir(String, String)}，但额外验证目录中包含指定标记文件。
     *
     * @param modelName  模型名称
     * @param subDir     data 下的子目录名
     * @param markerFile 标记文件名（如 {@code "encoder_model.onnx"}）
     * @return 包含标记文件的模型目录绝对路径，或默认目录（即使标记文件不存在）
     */
    public static Path resolveModelDir(String modelName, String subDir, String markerFile) {
        Path[] candidates = {
                Paths.get("data", subDir, modelName),
                Paths.get("models", modelName),
                Paths.get("onnxruntime", "models", modelName),
        };
        for (Path p : candidates) {
            if (Files.exists(p.resolve(markerFile))) {
                return p.toAbsolutePath();
            }
        }
        return candidates[0].toAbsolutePath();
    }
}
