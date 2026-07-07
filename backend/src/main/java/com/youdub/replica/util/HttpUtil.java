package com.youdub.replica.util;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * HTTP 调用工具。
 * 提供可中断的 HTTP 调用包装，替代 java.net.http.HttpClient.send() 的阻塞调用。
 * <p>
 * HttpClient.send() 不响应 Thread.interrupt()，导致无法在外部中断进行中的请求。
 * sendInterruptible() 内部使用 sendAsync() + future.get()，
 * future.get() 在等待线程被中断时会抛出 InterruptedException，
 * 随后调用 future.cancel(true) 关闭底层 TCP 连接，真正中止请求。
 */
public final class HttpUtil {

    private HttpUtil() {}

    /**
     * 可中断的 HTTP 调用。
     * 在等待响应的线程被中断时，会取消底层 HTTP 请求并抛出 InterruptedException。
     */
    public static <T> HttpResponse<T> sendInterruptible(
            HttpClient client, HttpRequest request,
            HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {

        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, handler);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
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
}
