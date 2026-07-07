package com.youdub.replica.service;

import com.youdub.replica.dto.TaskCreateRequest;
import com.youdub.replica.dto.TaskResponse;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.enums.TaskStatus;
import com.youdub.replica.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkerService 与 PipelineOrchestrator E2E 测试。
 * 验证任务入队、执行、失败恢复等流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerServiceE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void cleanup() throws InterruptedException {
        // 等待可能仍在运行的线程释放
        Thread.sleep(2000);

        // 清理数据库中的剩余任务
        org.springframework.http.ResponseEntity<Map> listResp = restTemplate.getForEntity("/api/tasks?limit=100", Map.class);
        if (listResp.getBody() != null) {
            Object tasks = listResp.getBody().get("tasks");
            if (tasks instanceof List<?> taskList) {
                for (Object task : taskList) {
                    if (task instanceof Map<?, ?> taskMap) {
                        String id = (String) taskMap.get("id");
                        if (id != null) {
                            restTemplate.delete("/api/tasks/" + id);
                        }
                    }
                }
            }
        }
    }

    @Test
    void workerService_shouldBeInitialized() {
        assertNotNull(workerService);
    }

    @Test
    void isRunning_nonExistentTask_shouldReturnFalse() {
        assertFalse(workerService.isRunning("non-existent-task-id"));
    }

    @Test
    void enqueue_shouldSubmitTaskForExecution() throws Exception {
        // 创建任务（使用无效 URL，yt-dlp 会快速失败）
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("invalid://enqueueTest");
        request.setExecutionMode("auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);

        org.springframework.http.ResponseEntity<TaskResponse> resp =
                restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        String taskId = resp.getBody().getId();

        // 等待任务被 worker 拾取并执行（由于缺少 yt-dlp 等工具，任务会失败）
        Thread.sleep(2000);

        // 验证任务已被处理（状态应为 running、failed 或 succeeded）
        Task task = taskRepository.findById(taskId);
        assertNotNull(task);
        // 任务应该不再处于 queued 状态（已被 worker 拾取）
        // 注意：由于线程调度，可能需要等待
        int attempts = 0;
        while (task.getStatus() == TaskStatus.QUEUED && attempts < 30) {
            Thread.sleep(500);
            task = taskRepository.findById(taskId);
            attempts++;
        }
        assertNotEquals(TaskStatus.QUEUED, task.getStatus(),
                "任务应已被 worker 拾取处理");
    }

    @Test
    void pipelineFailure_shouldMarkTaskAsFailed() throws Exception {
        // 创建任务（使用无效 URL，yt-dlp 会快速失败）
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("invalid://pipelineFailTest");
        request.setExecutionMode("auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);

        org.springframework.http.ResponseEntity<TaskResponse> resp =
                restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);

        String taskId = resp.getBody().getId();

        // 等待任务失败（由于 URL 无效，yt-dlp 会快速失败）
        int attempts = 0;
        Task task = taskRepository.findById(taskId);
        while (task.getStatus() != TaskStatus.FAILED && task.getStatus() != TaskStatus.SUCCEEDED && attempts < 40) {
            Thread.sleep(500);
            task = taskRepository.findById(taskId);
            attempts++;
        }

        // 验证任务最终失败（因为 URL 无效）
        assertEquals(TaskStatus.FAILED, task.getStatus(),
                "任务应因无效 URL 而失败");
        assertNotNull(task.getErrorMessage());
        assertFalse(task.getErrorMessage().isEmpty());
    }

}
