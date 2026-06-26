package com.youdub.replica.dto;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.entity.TaskStage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务响应 DTO。
 * 包含 Task 的所有字段及阶段列表，枚举值转为字符串。
 */
@Data
public class TaskResponse {
    private String id;
    private String url;
    private String title;
    private String status;
    private String currentStage;
    private String sessionPath;
    private String finalVideoPath;
    private String errorMessage;
    private String executionMode;
    private String sourceType;
    private String asrLanguage;
    private String targetLanguage;
    private double progress;
    private String createdAt;
    private String startedAt;
    private String completedAt;
    private List<TaskStageResponse> stages = new ArrayList<>();

    /**
     * 从 entity 转换为响应 DTO。
     */
    public static TaskResponse from(Task task) {
        TaskResponse resp = new TaskResponse();
        resp.setId(task.getId());
        resp.setUrl(task.getUrl());
        resp.setTitle(task.getTitle());
        resp.setStatus(task.getStatus() == null ? null : task.getStatus().toDbValue());
        resp.setCurrentStage(task.getCurrentStage());
        resp.setSessionPath(task.getSessionPath());
        resp.setFinalVideoPath(task.getFinalVideoPath());
        resp.setErrorMessage(task.getErrorMessage());
        resp.setExecutionMode(task.getExecutionMode());
        resp.setSourceType(task.getSourceType());
        resp.setAsrLanguage(task.getAsrLanguage());
        resp.setTargetLanguage(task.getTargetLanguage());
        resp.setProgress(task.getProgress());
        resp.setCreatedAt(task.getCreatedAt());
        resp.setStartedAt(task.getStartedAt());
        resp.setCompletedAt(task.getCompletedAt());

        if (task.getStages() != null) {
            for (TaskStage stage : task.getStages()) {
                resp.getStages().add(TaskStageResponse.from(stage));
            }
        }
        return resp;
    }

    /**
     * 阶段响应 DTO。
     */
    @Data
    public static class TaskStageResponse {
        private String name;
        private String label;
        private String status;
        private int progress;
        private String startedAt;
        private String completedAt;
        private String lastMessage;
        private String errorMessage;

        public static TaskStageResponse from(TaskStage stage) {
            TaskStageResponse resp = new TaskStageResponse();
            resp.setName(stage.getName());
            resp.setLabel(stage.getLabel());
            resp.setStatus(stage.getStatus() == null ? null : stage.getStatus().toDbValue());
            resp.setProgress(stage.getProgress());
            resp.setStartedAt(stage.getStartedAt());
            resp.setCompletedAt(stage.getCompletedAt());
            resp.setLastMessage(stage.getLastMessage());
            resp.setErrorMessage(stage.getErrorMessage());
            return resp;
        }
    }
}
