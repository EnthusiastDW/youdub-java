package com.youdub.replica.dto;

import lombok.Data;

/**
 * 获取 OpenAI 模型列表请求。
 */
@Data
public class OpenAiModelsRequest {
    private String baseUrl;
    private String apiKey;
}
