package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 音源分离适配器接口。
 * 当前方案：FfmpegSimpleSeparator（FFmpeg 频率滤波）、DemucsSeparator（本地 Python 模型）、AudioSeparatorApiSeparator（Docker 容器服务）
 */
public interface SourceSeparator {
    void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception;
}
