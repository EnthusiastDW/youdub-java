package com.youdub.replica.config;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * HTTP 客户端配置。
 * 使用 OkHttp 替代 JDK HttpClient，避免 JDK HttpClient sendAsync CompletableFuture 不 complete 的 bug。
 * 强制使用 HTTP/1.1，因为本地 faster-whisper 服务（uvicorn）不支持 HTTP/2。
 * <p>
 * 使用自定义 SocketFactory 启用 TCP keepalive，在长时间无数据流时探测连接活性，
 * 避免中间设备（docker-proxy、NAT、防火墙）静默断开连接后客户端无限等待。
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
                .socketFactory(new KeepAliveSocketFactory())
                .build();
    }

    /**
     * 启用 TCP keepalive 的 Socket 工厂。
     * 当连接空闲超过 KEEPIDLE 秒时，开始发送 keepalive 探测包，每次间隔 KEEPINTERVAL 秒，
     * 连续 KEEPCOUNT 次无回应则关闭连接（read() 返回 -1 / 抛异常），客户端可快速感知。
     */
    private static class KeepAliveSocketFactory extends SocketFactory {

        private static final SocketFactory DELEGATE = SocketFactory.getDefault();

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = DELEGATE.createSocket();
            try {
                socket.setKeepAlive(true);
                configureKeepAliveOptions(socket);
            } catch (IOException e) {
                // keepalive 已启用，扩展参数为 best-effort；失败不影响基础功能
            }
            return socket;
        }

        private static void configureKeepAliveOptions(Socket socket) {
            Set<SocketOption<?>> supported = socket.supportedOptions();
            for (SocketOption<?> opt : supported) {
                switch (opt.name()) {
                    case "TCP_KEEPIDLE" -> trySet(socket, opt, 60);       // 60s 空闲 → 发探测
                    case "TCP_KEEPINTERVAL" -> trySet(socket, opt, 10);   // 10s 探测间隔
                    case "TCP_KEEPCOUNT" -> trySet(socket, opt, 5);       // 5 次无回应 → 断开
                }
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void trySet(Socket socket, SocketOption opt, Object value) {
            try {
                socket.setOption(opt, value);
            } catch (IllegalArgumentException | IOException ignored) {
            }
        }

        // ---- 以下委托方法 OkHttp 用不到，但 SocketFactory 要求实现 ----

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return DELEGATE.createSocket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return DELEGATE.createSocket(address, port, localAddress, localPort);
        }
    }
}
