package com.youdub.replica.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行器工具类。所有方法均为静态，直接调用 {@link #run(Command)} 即可。
 *
 * <p>用法示例：
 * <pre>{@code
 * // 字符串解析模式
 * CommandResult r = CommandRunner.run(Command.builder()
 *     .parse("ffmpeg -i input.wav output.mp3")
 *     .timeout(300_000)
 *     .onLine(line -> log.info("[ffmpeg] {}", line))
 *     .build());
 *
 * // Builder 参数模式
 * CommandResult r = CommandRunner.run(Command.builder()
 *     .add("whisper-cli", "-m", "base.bin", "-f", audioPath)
 *     .workDir(Path.of("/tmp"))
 *     .timeout(600_000)
 *     .retry(2, 10_000)
 *     .onExit(code -> log.info("exit: {}", code))
 *     .throwOnNonZero(false)
 *     .build());
 * }</pre>
 */
@Slf4j
public final class CommandRunner {

    private CommandRunner() {}

    /**
     * 执行命令。支持重试、超时、实时日志回调和退出码回调。
     *
     * @param cmd 命令配置（通过 Command.builder() 构建）
     * @return 执行结果
     * @throws RuntimeException 执行失败（启动失败、超时、退出码非零且 throwOnNonZero=true）
     */
    public static CommandResult run(Command cmd) {
        log.info("执行命令: {}", String.join(" ", cmd.args()));
        if (cmd.workDir() != null) {
            log.debug("  workDir={}", cmd.workDir());
        }
        log.debug("  timeout={}ms, retry={}/{}ms, maxOutputLines={}, throwOnNonZero={}",
                cmd.timeoutMs(), cmd.retryCount(), cmd.retryDelayMs(),
                cmd.maxOutputLines(), cmd.throwOnNonZero());

        for (int attempt = 0; attempt <= cmd.retryCount(); attempt++) {
            try {
                return executeOnce(cmd);
            } catch (Exception e) {
                if (attempt < cmd.retryCount()) {
                    log.warn("执行失败(第{}/{}次): {}, {}ms后重试",
                            attempt + 1, cmd.retryCount() + 1,
                            e.getMessage(), cmd.retryDelayMs());
                    try {
                        Thread.sleep(cmd.retryDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                } else {
                    log.error("执行失败(已耗尽{}次重试): {}", cmd.retryCount() + 1, e.getMessage());
                    throw e;
                }
            }
        }
        throw new RuntimeException("不可达代码");
    }

    private static CommandResult executeOnce(Command cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd.args());
        if (cmd.workDir() != null) {
            pb.directory(cmd.workDir().toFile());
        }
        pb.redirectErrorStream(true);

        Instant start = Instant.now();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("启动进程失败: " + String.join(" ", cmd.args()), e);
        }

        // 实时读取输出（使用虚拟线程）
        List<String> lines = new ArrayList<>();
        StringBuilder fullOutput = new StringBuilder();

        Thread readerThread = Thread.ofVirtual().name("cmd-reader").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 收集输出（output 始终完整，lines 受 maxOutputLines 限制）
                    fullOutput.append(line).append(System.lineSeparator());
                    if (cmd.maxOutputLines() < 0 || lines.size() < cmd.maxOutputLines()) {
                        lines.add(line);
                    }

                    // 调用自定义回调
                    if (cmd.onLine() != null) {
                        try {
                            cmd.onLine().accept(line);
                        } catch (Exception e) {
                            log.warn("onLine 回调异常: {}", e.getMessage());
                        }
                    }
                    // 默认 DEBUG 日志
                    log.debug("[CMD] {}", line);
                }
            } catch (IOException e) {
                // 进程销毁时流关闭，属于正常情况
            }
        });

        // 等待进程完成
        try {
            boolean finished;
            if (cmd.timeoutMs() > 0) {
                finished = process.waitFor(cmd.timeoutMs(), TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
            if (!finished) {
                process.destroyForcibly();
                if (cmd.onTimeout() != null) {
                    try {
                        cmd.onTimeout().run();
                    } catch (Exception e) {
                        log.warn("onTimeout 回调异常: {}", e.getMessage());
                    }
                }
                throw new RuntimeException("进程超时(" + cmd.timeoutMs() + "ms): "
                        + String.join(" ", cmd.args()));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeException("进程执行被中断: " + String.join(" ", cmd.args()), e);
        }

        // 等待读取线程排空缓冲区（虚拟线程很快退出，join 不会阻塞）
        try {
            readerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Duration elapsed = Duration.between(start, Instant.now());
        int exitCode = process.exitValue();

        // 调用退出码回调
        for (var handler : cmd.onExit()) {
            try {
                handler.accept(exitCode);
            } catch (Exception e) {
                log.warn("onExit 回调异常: {}", e.getMessage());
            }
        }

        // 非零退出码处理
        if (cmd.throwOnNonZero() && exitCode != 0) {
            log.error("进程退出码 {}，命令：{}", exitCode, String.join(" ", cmd.args()));
            throw new RuntimeException("进程退出码 " + exitCode + "，输出：\n" + fullOutput);
        }

        return new CommandResult(exitCode, fullOutput.toString(), List.copyOf(lines), elapsed);
    }
}
