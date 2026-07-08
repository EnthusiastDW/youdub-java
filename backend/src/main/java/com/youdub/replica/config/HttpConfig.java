package com.youdub.replica.config;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * HTTP 客户端配置。
 * 使用 OkHttp 替代 JDK HttpClient，避免 JDK HttpClient sendAsync CompletableFuture 不 complete 的 bug。
 * 强制使用 HTTP/1.1，因为本地 faster-whisper 服务（uvicorn）不支持 HTTP/2。
 */
@Configuration
public class HttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ZERO)       // 无读超时，与应用现有行为一致
                .writeTimeout(Duration.ofMinutes(30))
                .callTimeout(Duration.ZERO)        // 无总超时，由业务层控制
                .retryOnConnectionFailure(false)
                .build();
    }
}
