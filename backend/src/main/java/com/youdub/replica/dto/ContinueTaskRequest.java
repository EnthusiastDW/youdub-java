package com.youdub.replica.dto;

import lombok.Data;

/**
 * 继续任务请求（手动模式下推进到下一阶段）。
 */
@Data
public class ContinueTaskRequest {
    private String executionMode;
}
