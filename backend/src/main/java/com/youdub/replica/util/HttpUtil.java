package com.youdub.replica.util;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * HTTP 调用工具。
 * 基于 OkHttp 实现，提供可中断的 HTTP 调用包装。
 * <p>
 * 通过 enqueue + CompletableFuture 实现中断支持：
 * 调用线程被中断时 call.cancel() 关闭底层 TCP 连接，真正中止请求。
 * 相比 JDK HttpClient.sendAsync()，OkHttp 的异步机制更简单可靠，
 * 不存在 CompletableFuture 永不 complete 的 bug。
 */
public final class HttpUtil {

    private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);

    private HttpUtil() {}

    /**
     * 可中断的 HTTP 调用（异步 + future.get）。
     * 在等待响应的线程被中断时，会取消底层 HTTP 请求并抛出 InterruptedException。
     */
    public static Response sendInterruptible(OkHttpClient client, Request request)
            throws IOException, InterruptedException {

        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        CompletableFuture<Response> future = new CompletableFuture<>();
        Call call = client.newCall(request);

        call.enqueue(new Callback() {
            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                future.complete(response);
            }

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException e) {
            call.cancel();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("HTTP 调用异常", cause);
        }
    }

    /**
     * 同步 HTTP 调用（不可中断，但更简单可靠）。
     * 适用于不需要中断支持的场景（如 SettingsService 中的配置查询）。
     */
    public static Response send(OkHttpClient client, Request request) throws IOException {
        return client.newCall(request).execute();
    }

    /**
     * 调用 HTTP API 并自动重试可重试错误（5xx、429、网络异常）。
     * 使用指数退避：1s、2s、4s，最多重试 maxRetries 次。
     *
     * @param client     OkHttpClient 实例
     * @param request    HTTP 请求（OkHttp Request 不可变，可安全复用）
     * @param maxRetries 最大重试次数（含首次调用）
     * @return HTTP 200 时的响应体字符串
     * @throws IOException          所有重试均遇到网络错误时抛出
     * @throws InterruptedException 等待退避时线程被中断
     * @throws RuntimeException     非可重试 HTTP 错误或重试耗尽时抛出
     */
    public static String executeWithRetry(OkHttpClient client, Request request, int maxRetries)
            throws IOException, InterruptedException {
        int attempt = 0;
        RuntimeException lastHttpError = null;
        IOException lastIoError = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                Response response = sendInterruptible(client, request);
                String body = response.body() != null ? response.body().string() : "";
                int code = response.code();

                if (code == 200) {
                    return body;
                }

                if (isHttpRetryable(code)) {
                    log.warn("HTTP API 返回可重试状态码（第 {}/{} 次），code={}，body={}",
                            attempt, maxRetries, code, body);
                    lastHttpError = new RuntimeException("HTTP API 调用失败 [" + code + "]：" + body);
                    backoff(attempt);
                    continue;
                }

                throw new RuntimeException("HTTP API 调用失败 [" + code + "]：" + body);

            } catch (IOException e) {
                lastIoError = e;
                log.warn("HTTP API 网络错误（第 {}/{} 次）：{}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    backoff(attempt);
                }
            }
        }

        if (lastIoError != null) {
            throw lastIoError;
        }
        throw lastHttpError;
    }

    /**
     * 判断 HTTP 状态码是否应重试。
     * 可重试：5xx（服务端错误）、429（限流）。
     * 不可重试：4xx 除 429 外（认证/参数错误等），重试无意义。
     */
    private static boolean isHttpRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 指数退避休眠，尊重线程中断。
     * 序列：第 1 次 → 1s，第 2 次 → 2s，第 3 次 → 4s
     */
    private static void backoff(int attempt) throws InterruptedException {
        long millis = (long) Math.pow(2, attempt - 1) * 1000;
        Thread.sleep(millis);
    }
}
