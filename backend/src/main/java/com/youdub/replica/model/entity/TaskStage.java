package com.youdub.replica.model.entity;

import com.youdub.replica.model.enums.StageStatus;
import lombok.Data;

@Data
public class TaskStage {
    private String taskId;
    private String name;
    private String label;
    private StageStatus status = StageStatus.PENDING;
    private int progress;
    private String startedAt;
    private String completedAt;
    private String lastMessage = "";
    private String errorMessage = "";
}
