package com.youdub.replica.service.adapter.translate;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * 翻译适配器接口。
 * 原方案：OpenAiTranslator（通过 OpenAI 兼容 Chat Completions API）
 * 替代方案：OllamaTranslator（通过本地 Ollama API）
 */
public interface Translator {
    void translate(Task task, Path asrPath, Path outputDir, String model, String srcLang, String dstLang) throws Exception;

    /** 翻译单段文本（用于标题翻译等一次性调用） */
    String translateText(String text, String srcLang, String dstLang) throws Exception;
}
