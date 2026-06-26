package com.youdub.replica.service.adapter.separate;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 音源分离适配器接口。
 * 原方案：DemucsSeparator（通过 Demucs 子进程，HTDemucs-FT 模型）
 * 替代方案：SpleeterSeparator（通过 Spleeter 子进程）
 */
public interface SourceSeparator {
    String getName();
    void separate(Task task, Path audioPath, Path outputDir, String device) throws Exception;
}
