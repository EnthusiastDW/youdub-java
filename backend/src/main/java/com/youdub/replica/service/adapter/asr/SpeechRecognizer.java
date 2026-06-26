package com.youdub.replica.service.adapter.asr;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 语音识别适配器接口。
 * 原方案：WhisperApiRecognizer（通过 OpenAI Whisper API）
 * 替代方案：WhisperCppRecognizer（通过 whisper.cpp 子进程或 faster-whisper API）
 */
public interface SpeechRecognizer {
    String getName();
    void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception;
}
