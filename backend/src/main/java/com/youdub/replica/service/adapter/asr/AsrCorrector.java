package com.youdub.replica.service.adapter.asr;

import com.youdub.replica.model.entity.Task;
import java.nio.file.Path;

/**
 * ASR 转写文本纠错适配器接口。
 * <p>
 * 在 ASR 完成后、时间修正前执行，用 LLM 根据全文上下文
 * 自动纠正领域特定术语的误识别（如 "trades" → "traits"）。
 */
public interface AsrCorrector {

    /**
     * 对 ASR 转写结果进行文本纠错。
     *
     * @param task      当前任务
     * @param asrPath   asr.json 路径
     * @param outputDir 输出目录（写入 asr_corrected.json）
     */
    void correct(Task task, Path asrPath, Path outputDir) throws Exception;
}
