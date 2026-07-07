package com.youdub.replica.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youdub.replica.dto.TaskCreateRequest;
import com.youdub.replica.dto.TaskResponse;
import com.youdub.replica.service.WorkerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务管理 E2E 测试。
 * 覆盖任务的创建、查询、删除等核心流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskControllerE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkerService workerService;

    @BeforeEach
    void cleanup() throws InterruptedException {
        // 等待可能仍在运行的线程释放
        Thread.sleep(500);

        // 清理已有任务，避免重复检测干扰
        ResponseEntity<Map> listResp = restTemplate.getForEntity("/api/tasks?limit=100", Map.class);
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
    void healthEndpoint_shouldReturnOk() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/health", Map.class);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("ok", resp.getBody().get("status"));
    }

    @Test
    void createTask_shouldReturnTaskResponse() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        request.setExecutionMode("auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<TaskResponse> resp = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getId());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", resp.getBody().getUrl());
        assertEquals("queued", resp.getBody().getStatus());
        assertEquals("auto", resp.getBody().getExecutionMode());
        assertEquals("youtube", resp.getBody().getSourceType());
        assertEquals("en", resp.getBody().getAsrLanguage());
        assertEquals("zh", resp.getBody().getTargetLanguage());
        assertNotNull(resp.getBody().getStages());
        assertEquals(9, resp.getBody().getStages().size());
    }

    @Test
    void createTask_withBlankUrl_shouldReturn400() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> resp = restTemplate.postForEntity("/api/tasks", entity, Map.class);

        assertEquals(400, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(400, resp.getBody().get("status"));
    }

    @Test
    void createTask_duplicateUrl_shouldReturnSameTask() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=abc12345678");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<TaskResponse> resp1 = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        ResponseEntity<TaskResponse> resp2 = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);

        assertEquals(200, resp1.getStatusCode().value());
        assertEquals(200, resp2.getStatusCode().value());
        assertEquals(resp1.getBody().getId(), resp2.getBody().getId());
    }

    @Test
    void getTask_shouldReturnTask() {
        // 先创建任务
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=getTaskTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        // 查询任务
        ResponseEntity<TaskResponse> resp = restTemplate.getForEntity("/api/tasks/" + taskId, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(taskId, resp.getBody().getId());
    }

    @Test
    void getTask_notFound_shouldReturn404() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/tasks/nonexistent-id", Map.class);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void listTasks_shouldReturnTasksList() {
        // 创建一个任务
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=listTasksTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);

        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/tasks?limit=20", Map.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        Object tasks = resp.getBody().get("tasks");
        assertNotNull(tasks);
        assertTrue(tasks instanceof List<?>);
        assertFalse(((List<?>) tasks).isEmpty());
    }

    @Test
    void deleteTask_shouldReturn204() {
        // 先创建任务
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=deleteTaskTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        // 删除任务
        restTemplate.delete("/api/tasks/" + taskId);

        // 验证已删除
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/tasks/" + taskId, Map.class);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void rerunTask_shouldResetToQueued() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=rerunTaskTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        ResponseEntity<TaskResponse> resp = restTemplate.postForEntity("/api/tasks/" + taskId + "/rerun", null, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("queued", resp.getBody().getStatus());
    }

    @Test
    void resumeTask_shouldResetToQueued() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=resumeTaskTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        ResponseEntity<TaskResponse> resp = restTemplate.postForEntity("/api/tasks/" + taskId + "/resume", null, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("queued", resp.getBody().getStatus());
    }

    @Test
    void getTaskLog_shouldReturnTextPlain() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=logTaskTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        ResponseEntity<String> resp = restTemplate.getForEntity("/api/tasks/" + taskId + "/log", String.class);

        assertEquals(200, resp.getStatusCode().value());
        // 日志文件可能不存在，body 可能为 null 或空字符串，验证端点可访问即可
    }

    @Test
    void redoStage_shouldResetStageAndQueue() {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUrl("https://www.youtube.com/watch?v=redoStageTest1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TaskCreateRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<TaskResponse> created = restTemplate.postForEntity("/api/tasks", entity, TaskResponse.class);
        String taskId = created.getBody().getId();

        ResponseEntity<TaskResponse> resp = restTemplate.postForEntity(
                "/api/tasks/" + taskId + "/stages/download/redo", null, TaskResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("queued", resp.getBody().getStatus());
    }
}
