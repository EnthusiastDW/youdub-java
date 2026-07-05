package com.youdub.replica.model.entity;

import com.youdub.replica.model.enums.TaskStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Task {
    private String id;
    private String url = "";
    private String title = "";
    private TaskStatus status = TaskStatus.QUEUED;
    private String currentStage;
    private String sessionPath = "";
    private String finalVideoPath = "";
    private String errorMessage = "";
    private String executionMode = "auto";
    private String sourceType = "";
    private String asrLanguage = "";
    private String targetLanguage = "";
    private double progress = 0.0;
    private String createdAt;
    private String startedAt;
    private String completedAt;
    private String notes = "";
    private String youtubeVideoId = "";

    private List<TaskStage> stages = new ArrayList<>();
}
