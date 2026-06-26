package com.youdub.replica.dto;

import lombok.Data;

/**
 * 创建任务请求。
 */
@Data
public class TaskCreateRequest {
    private String url;
    private String executionMode = "auto";
}
