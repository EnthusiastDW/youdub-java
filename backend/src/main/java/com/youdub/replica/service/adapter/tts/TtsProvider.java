package com.youdub.replica.service.adapter.tts;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * TTS 配音适配器接口。
 * 原方案：VoxCpmTtsProvider（通过 VoxCPM2 子进程，声音克隆）
 * 替代方案：EdgeTtsProvider（通过 edge-tts 子进程，通用 TTS）
 *          OpenAiTtsProvider（通过 OpenAI TTS API）
 */
public interface TtsProvider {
    String getName();
    void synthesize(Task task, Path textPath, Path outputDir) throws Exception;
}
