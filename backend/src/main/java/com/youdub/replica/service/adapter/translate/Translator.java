package com.youdub.replica.service.adapter.translate;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 翻译适配器接口。
 * 原方案：OpenAiTranslator（通过 OpenAI 兼容 Chat Completions API）
 * 替代方案：OllamaTranslator（通过本地 Ollama API）
 */
public interface Translator {
    String getName();
    void translate(Task task, Path asrPath, Path outputDir, String model, String srcLang, String dstLang) throws Exception;
}
