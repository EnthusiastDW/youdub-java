package com.youdub.replica.controller;

import com.youdub.replica.dto.ContinueTaskRequest;
import com.youdub.replica.dto.TaskCreateRequest;
import com.youdub.replica.dto.TaskResponse;
import com.youdub.replica.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 任务管理接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public TaskResponse createTask(@RequestBody TaskCreateRequest request) {
        return taskService.createTask(request);
    }

    @PostMapping("/upload")
    public TaskResponse uploadLocalVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "executionMode", required = false, defaultValue = "auto") String executionMode,
            @RequestParam(value = "direction", required = false, defaultValue = "en-zh") String direction,
            @RequestParam(value = "subtitleFile", required = false) MultipartFile subtitleFile) throws Exception {
        return taskService.uploadLocalVideo(file, executionMode, direction, subtitleFile);
    }

    @GetMapping
    public Map<String, List<TaskResponse>> listTasks(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Map.of("tasks", taskService.listTasks(limit));
    }

    @GetMapping("/current")
    public TaskResponse getCurrentTask() {
        return taskService.getCurrentTask();
    }

    @GetMapping("/{id}")
    public TaskResponse getTask(@PathVariable String id) {
        return taskService.getTask(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable String id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rerun")
    public TaskResponse rerunTask(@PathVariable String id) {
        return taskService.rerunTask(id);
    }

    @PostMapping("/{id}/resume")
    public TaskResponse resumeTask(@PathVariable String id) {
        return taskService.resumeTask(id);
    }

    @PostMapping("/{id}/continue")
    public TaskResponse continueTask(@PathVariable String id, @RequestBody(required = false) ContinueTaskRequest request) {
        return taskService.continueTask(id, request);
    }

    @PostMapping("/{id}/stages/{stageName}/redo")
    public TaskResponse redoStage(@PathVariable String id, @PathVariable String stageName) {
        return taskService.redoStage(id, stageName);
    }

    @GetMapping(value = "/{id}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getTaskLog(@PathVariable String id) {
        return taskService.getTaskLog(id);
    }

    @GetMapping("/{id}/artifact/final-video")
    public ResponseEntity<Resource> getFinalVideo(
            @PathVariable String id,
            @RequestParam(value = "download", defaultValue = "false") boolean download) {
        Path video = taskService.getFinalVideo(id);
        Resource resource = new FileSystemResource(video);
        HttpHeaders headers = new HttpHeaders();
        if (download) {
            headers.setContentDispositionFormData("attachment", video.getFileName().toString());
        }
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().headers(headers).body(resource);
    }
}
