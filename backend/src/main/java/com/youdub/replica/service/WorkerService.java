package com.youdub.replica.service;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.enums.TaskStatus;
import com.youdub.replica.repository.TaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 任务执行 Worker 服务。
 * 使用线程池管理任务执行，跟踪运行中的任务，并在启动时恢复任务状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    @Qualifier("taskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;
    private final TaskRepository taskRepository;
    private final PipelineOrchestrator pipelineOrchestrator;

    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /**
     * 提交任务到线程池执行。
     */
    public void enqueue(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (isRunning(taskId)) {
            log.warn("任务已在运行中，跳过入队：{}", taskId);
            return;
        }
        log.info("入队任务：{}", taskId);
        Future<?> future = taskExecutor.submit(() -> {
            MDC.put("taskId", taskId);
            try {
                log.info("开始执行任务：{}", taskId);
                pipelineOrchestrator.execute(taskId);
            } catch (Exception e) {
                log.error("任务执行异常：taskId={}", taskId, e);
                try {
                    taskRepository.updateStatus(taskId, TaskStatus.FAILED, 0.0);
                    taskRepository.updateField(taskId, "error_message", e.getMessage() == null ? "未知错误" : e.getMessage());
                } catch (Exception ex) {
                    log.error("更新任务状态失败：taskId={}", taskId, ex);
                }
            } finally {
                runningTasks.remove(taskId);
                MDC.remove("taskId");
            }
        });
        runningTasks.put(taskId, future);
    }

    /**
     * 取消运行中的任务。
     */
    public void cancelTask(String taskId) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null) {
            future.cancel(true);
            runningTasks.remove(taskId);
            log.info("已取消任务：{}", taskId);
        } else {
            log.warn("任务未在运行中：{}", taskId);
        }
        try {
            taskRepository.updateStatus(taskId, TaskStatus.CANCELLED, 0.0);
        } catch (Exception e) {
            log.warn("更新任务状态为 CANCELLED 失败：{}", e.getMessage());
        }
    }

    /**
     * 检查任务是否在运行。
     */
    public boolean isRunning(String taskId) {
        Future<?> future = runningTasks.get(taskId);
        return future != null && !future.isDone();
    }

    /**
     * 取消所有运行中的任务。
     * 用于测试清理或应用关闭时释放线程池资源。
     */
    public void cancelAllRunningTasks() {
        for (String taskId : new java.util.ArrayList<>(runningTasks.keySet())) {
            cancelTask(taskId);
        }
    }

    /**
     * 启动时恢复任务状态。
     * 将 running 状态的任务标记为 failed，将 queued 状态的任务重新入队。
     */
    @PostConstruct
    public void recoverOnStartup() {
        log.info("启动时恢复任务状态...");
        List<Task> allTasks = taskRepository.findAll(1000);
        int failedCount = 0;
        int queuedCount = 0;
        for (Task task : allTasks) {
            if (task.getStatus() == TaskStatus.RUNNING) {
                // 重启时 running 状态的任务是孤立的，标记为 failed
                taskRepository.updateStatus(task.getId(), TaskStatus.FAILED, task.getProgress());
                taskRepository.updateField(task.getId(), "error_message", "后端在任务完成前重启。");
                failedCount++;
                log.info("将孤立运行任务标记为失败：{}", task.getId());
            } else if (task.getStatus() == TaskStatus.QUEUED) {
                // 重新入队 queued 任务
                enqueue(task.getId());
                queuedCount++;
            }
        }
        log.info("启动恢复完成：标记 {} 个任务为失败，重新入队 {} 个任务", failedCount, queuedCount);
    }
}
