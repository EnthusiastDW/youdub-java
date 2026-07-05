package com.youdub.replica.service.adapter;

import org.springframework.stereotype.Component;

/**
 * 阶段级跳过跟踪器。
 * <p>
 * 通过 ThreadLocal 标记当前线程（即当前任务）的某个 adapter 阶段是否因输出文件已存在而跳过。
 * PipelineOrchestrator 在阶段执行后检查此标记，决定是否恢复原始时间戳（而非保留 ≈0ms 的运行时间）。
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * // 在 adapter 中，当检测到输出文件已存在时：
 * if (Files.exists(outputFile)) {
 *     skipTracker.markSkipped();
 *     return;
 * }
 *
 * // 在 PipelineOrchestrator 中，阶段执行后：
 * if (skipTracker.isSkipped()) {
 *     restoreStageTimestampsIfSkipped(taskId, stageName);
 * }
 * skipTracker.clear(); // finally 块中保证清理
 * }</pre>
 * </p>
 */
@Component
public class AdapterSkipTracker {

    private static final ThreadLocal<Boolean> SKIPPED = ThreadLocal.withInitial(() -> false);

    /**
     * 标记当前阶段的 adapter 因输出已存在而跳过实际工作。
     */
    public void markSkipped() {
        SKIPPED.set(true);
    }

    /**
     * 检查当前阶段的 adapter 是否跳过了实际工作。
     */
    public boolean isSkipped() {
        return SKIPPED.get();
    }

    /**
     * 消费后清理，防止 ThreadLocal 内存泄漏（虚拟线程池会复用线程）。
     * 必须在 finally 块中调用。
     */
    public void clear() {
        SKIPPED.remove();
    }
}
