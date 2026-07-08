package com.youdub.replica.util;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

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
}
