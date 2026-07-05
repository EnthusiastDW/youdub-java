package com.youdub.replica.service.adapter.audio;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 音频处理适配器接口。
 * 处理音频切分、合并、速度调整等操作。
 */
public interface AudioProcessor {
    void splitAudio(Task task, Path vocalsPath, Path translationPath, Path outputDir) throws Exception;
    void mergeAudio(Task task, Path ttsDir, Path translationPath, Path outputDir) throws Exception;
}
