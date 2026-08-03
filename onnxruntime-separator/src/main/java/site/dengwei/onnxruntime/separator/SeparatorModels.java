package site.dengwei.onnxruntime.separator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.dengwei.onnxruntime.util.Models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MDX-NET 音频分离模型文件管理。
 * <p>
 * 负责 MDX-NET ONNX 模型文件的下载和验证。
 * 模型从 HuggingFace {@code debugzxcv/uvr} 自动下载。
 * 所有方法都是幂等的——文件已存在则跳过。
 */
public final class SeparatorModels {

    private static final Logger log = LoggerFactory.getLogger(SeparatorModels.class);

    private static final String HF_MDXNET  = "https://huggingface.co/debugzxcv/uvr/resolve/main";
    private static final String MDXNET_FILE = "UVR-MDX-NET-Inst_HQ_3.onnx";

    private SeparatorModels() {}

    /**
     * 确保 MDX-NET 人声分离模型文件存在。
     * 缺失时从 HuggingFace {@code debugzxcv/uvr} 自动下载（约 66.8 MB）。
     *
     * @param file .onnx 文件路径
     */
    public static void ensureMdxNetFile(Path file) throws IOException {
        if (Files.exists(file)) {
            log.info("MDX-NET 模型文件已存在：{}", file);
            return;
        }
        Files.createDirectories(file.getParent());
        String url = HF_MDXNET + "/" + MDXNET_FILE;
        log.info("下载 MDX-NET 模型（约 66.8 MB）：{}", url);
        long bytes = Models.download(url, file);
        log.info("MDX-NET 模型下载完成：{} ({} MB)", file, bytes / (1024 * 1024));
    }
}
