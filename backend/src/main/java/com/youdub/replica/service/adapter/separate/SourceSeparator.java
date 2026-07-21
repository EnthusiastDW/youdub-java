package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 音源分离适配器接口。
 * 当前方案：OnnxSeparator（ONNX Runtime 本地推理）、DemucsSeparator（本地 Python 模型）、FfmpegSimpleSeparator（FFmpeg 频率滤波）
 */
public interface SourceSeparator {
    void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception;
}
