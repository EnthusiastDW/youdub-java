package com.youdub.replica.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP 客户端配置。
 * 用于调用 OpenAI 兼容 API 及其他外部 HTTP 服务。
 * 注意：强制使用 HTTP/1.1，因为本地 faster-whisper 服务（uvicorn）
 * 不支持 HTTP/2，Java 的 HTTP/2→HTTP/1.1 降级在某些版本会丢失请求体。
 */
@Configuration
public class HttpConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
}
