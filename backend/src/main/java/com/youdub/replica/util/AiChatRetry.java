package com.youdub.replica.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;

/**
 * AI Chat API 调用的通用重试工具。
 * <p>
 * 封装了针对 LLM 的 retry 策略，包括：
 * <ul>
 *   <li>HTTP 级重试：429（限流）、5xx（服务端错误）</li>
 *   <li>AI 级重试：拒绝回答、返回空内容等</li>
 *   <li>网络级重试：IO 异常</li>
 *   <li>指数退避：可配置初始延迟、乘数、上限</li>
 *   <li>中断响应：每次尝试前及退避休眠期间均检查 {@link Thread#interrupted()}</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>{@code
 * // 标准 OpenAI Chat Completion
 * String content = AiChatRetry.executeChat(httpClient, request, config, objectMapper);
 *
 * // 自定义提取逻辑
 * String json = AiChatRetry.execute(() -> {
 *     Response resp = HttpUtil.sendInterruptible(client, req);
 *     // ... 自定义解析和校验
 *     if (invalid) throw new AiChatRetry.AiRetryableException("...");
 *     return result;
 * }, config);
 * }</pre>
 */
@Slf4j
public final class AiChatRetry {

    /** 常见 LLM 拒绝回答的关键短语（大小写不敏感匹配）。 */
    private static final List<String> REFUSAL_PHRASES = List.of(
            "很抱歉",
            "没有足够的上下文",
            "无法回答",
            "无法提供",
            "i'm sorry",
            "i apologize",
            "don't have enough context",
            "cannot answer",
            "cannot provide"
    );

    private AiChatRetry() {
    }

    // ========== 拒绝检测 ==========

    /**
     * 判断 LLM 返回内容是否为拒绝回答。
     */
    public static boolean isRefusal(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return REFUSAL_PHRASES.stream().anyMatch(lower::contains);
    }

    // ========== 重试配置 ==========

    /** AI Chat 重试配置。使用 {@link RetryConfigBuilder} 构建。 */
    @Getter
    @Builder
    public static class RetryConfig {

        /** 最大尝试次数（含首次），默认 3。 */
        @Builder.Default
        private int maxAttempts = 3;

        /** 初始退避延迟（毫秒），默认 2000ms。 */
        @Builder.Default
        private long initialBackoffMs = 2000;

        /** 退避乘数，每次重试延迟乘以该值，默认 2.0。 */
        @Builder.Default
        private double backoffMultiplier = 2.0;

        /** 最大退避延迟（毫秒），默认 60000ms（1 分钟）。 */
        @Builder.Default
        private long maxBackoffMs = 60000;
    }

    // ========== 自定义异常（用于 retry 控制流） ==========

    /**
     * AI 层面的可重试异常（如拒绝回答、空内容、格式错误）。
     * 抛出此异常表示本次调用结果无效，应重试。
     */
    public static class AiRetryableException extends Exception {
        public AiRetryableException(String message) {
            super(message);
        }

        public AiRetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * HTTP 层面的可重试异常（429、5xx）。
     * 抛出此异常表示 HTTP 响应码可重试，应退避后重试。
     */
    public static class HttpRetryableException extends Exception {
        public HttpRetryableException(String message) {
            super(message);
        }
    }

    // ========== Callable 接口 ==========

    /**
     * AI Chat 调用任务，与 {@link #execute(AiCallable, RetryConfig)} 配合使用。
     * <p>
     * 在 call() 中：
     * <ul>
     *   <li>返回非 null 值表示成功</li>
     *   <li>抛 {@link AiRetryableException} 表示 AI 响应无效，应重试</li>
     *   <li>抛 {@link HttpRetryableException} 表示 HTTP 可重试，应退避重试</li>
     *   <li>抛 {@link IOException} 表示网络错误，应退避重试</li>
     *   <li>抛其它异常表示非可重试错误，立即透传</li>
     * </ul>
     */
    @FunctionalInterface
    public interface AiCallable<T> {
        T call() throws Exception;
    }

    // ========== 核心方法 ==========

    /**
     * 通用 AI Chat 重试执行器。
     * <p>
     * 按配置的最大尝试次数重试，每次重试前检查线程中断状态，
     * 重试间隔使用指数退避（可配置初始延迟、乘数、上限）。
     *
     * @param <T>    返回类型
     * @param task   调用任务（见 {@link AiCallable}）
     * @param config 重试配置
     * @return 调用成功的返回值
     * @throws InterruptedException 线程被中断
     * @throws RuntimeException     所有重试均失败时抛出（封装最终错误原因）
     */
    public static <T> T execute(AiCallable<T> task, RetryConfig config)
            throws InterruptedException {
        int attempt = 0;
        Exception lastError = null;

        while (attempt < config.maxAttempts) {
            if (Thread.interrupted()) {
                throw new InterruptedException("AI chat 请求被中断");
            }

            attempt++;
            try {
                T result = task.call();
                return result;
            } catch (AiRetryableException e) {
                log.warn("AI 响应无效（第 {}/{} 次）：{}", attempt, config.maxAttempts, e.getMessage());
                lastError = e;
            } catch (HttpRetryableException e) {
                log.warn("HTTP 可重试错误（第 {}/{} 次）：{}", attempt, config.maxAttempts, e.getMessage());
                lastError = e;
            } catch (IOException e) {
                log.warn("网络错误（第 {}/{} 次）：{}", attempt, config.maxAttempts, e.getMessage());
                lastError = e;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("AI chat 非可重试错误", e);
            }

            if (attempt < config.maxAttempts) {
                backoff(config, attempt);
            }
        }

        String msg = "AI chat 调用失败：已重试 " + config.maxAttempts + " 次";
        if (lastError != null) {
            throw new RuntimeException(msg, lastError);
        }
        throw new RuntimeException(msg);
    }

    /**
     * 标准 OpenAI Chat Completion 调用的便捷方法。
     * <p>
     * 自动从 HTTP 200 响应中提取 {@code choices[0].message.content}，
     * 并对空内容、拒绝回答进行重试。
     *
     * @param client        OkHttpClient
     * @param request       HTTP 请求（必须可安全重复使用）
     * @param config        重试配置
     * @param objectMapper  Jackson ObjectMapper
     * @return choices[0].message.content 的去除首尾空格的文本
     * @throws InterruptedException 线程被中断
     * @throws RuntimeException     所有重试均失败或遇到非可重试错误时抛出
     */
    public static String executeChat(OkHttpClient client, Request request,
                                     RetryConfig config, ObjectMapper objectMapper)
            throws InterruptedException {
        return execute(() -> {
            Response response = HttpUtil.sendInterruptible(client, request);
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";

            if (code == 200) {
                JsonNode root = objectMapper.readTree(body);
                String content = root.path("choices")
                        .path(0).path("message").path("content")
                        .asText("").trim();

                if (content.isEmpty()) {
                    throw new AiRetryableException("AI 返回空内容");
                }
                if (isRefusal(content)) {
                    throw new AiRetryableException("AI 拒绝回答：" + truncate(content, 100));
                }
                return content;
            }

            if (isHttpRetryable(code)) {
                throw new HttpRetryableException("HTTP " + code + "：" + truncate(body, 200));
            }

            throw new RuntimeException("AI chat API 调用失败 [" + code + "]：" + truncate(body, 200));
        }, config);
    }

    // ========== 内部工具 ==========

    /** 判断 HTTP 状态码是否应重试（429 限流、5xx 服务端错误）。 */
    private static boolean isHttpRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 指数退避休眠。
     * 延迟 = min(initialBackoffMs * multiplier^(attempt-1), maxBackoffMs)
     */
    private static void backoff(RetryConfig config, int attempt) throws InterruptedException {
        long delay = Math.min(
                (long) (config.initialBackoffMs * Math.pow(config.backoffMultiplier, attempt - 1)),
                config.maxBackoffMs
        );
        Thread.sleep(delay);
    }

    /** 截断长文本到指定长度。 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
