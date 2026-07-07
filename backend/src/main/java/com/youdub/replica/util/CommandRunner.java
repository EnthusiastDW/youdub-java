package com.youdub.replica.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // ── 进程注册表（Thread → 该线程启动的所有 Process）──
    // 由 WorkerService.interruptTask() 在中断线程后，调用 killProcesses() 清理其拉起的子进程。
    // 纯工具类不感知业务概念（如 taskId），仅以线程维度管理进程生命周期。
    private static final ConcurrentHashMap<Thread, Set<Process>> THREAD_PROCESSES = new ConcurrentHashMap<>();

    /** 注册进程到当前线程名下。调用者为 {@link #executeOnce} / {@link #runBinary}。 */
    private static void registerProcess(Process process) {
        THREAD_PROCESSES.computeIfAbsent(Thread.currentThread(), k -> ConcurrentHashMap.newKeySet()).add(process);
    }

    /** 从当前线程移除已完成的进程。 */
    private static void unregisterProcess(Process process) {
        Set<Process> processes = THREAD_PROCESSES.get(Thread.currentThread());
        if (processes != null) {
            processes.remove(process);
        }
    }

    /**
     * 强制终止指定线程上的所有正在运行的进程（及整个进程树）。
     * 由 {@code WorkerService.interruptTask()} 在 {@code thread.interrupt()} 之后调用。
     * <p>此方法绕过线程中断机制，直接通过 OS 信号杀掉子进程树，弥补仅靠
     * {@link Thread#interrupt()} 无法可靠终止子进程（尤其子进程再派生孙进程时）的问题。</p>
     */
    public static void killProcesses(Thread thread) {
        Set<Process> processes = THREAD_PROCESSES.remove(thread);
        if (processes == null || processes.isEmpty()) return;
        log.warn("强制终止线程 {} 的 {} 个运行中的进程", thread.getName(), processes.size());
        for (Process process : processes) {
            if (process.isAlive()) {
                killProcessTree(process);
            }
        }
    }

    /**
     * 执行命令。支持重试、超时、实时日志回调和退出码回调。
     *
     * @param cmd 命令配置（通过 Command.builder() 构建）
     * @return 执行结果
     * @throws RuntimeException 执行失败（启动失败、超时、退出码非零且 throwOnNonZero=true）
     */
    /**
     * 执行命令，带进程启动回调（用于调用方追踪已启动的 OS 进程）。
     * <p>回调在进程创建后立即调用，调用方可保存 {@link Process} 引用以便后续强制终止。</p>
     */
    public static CommandResult run(Command cmd, java.util.function.Consumer<Process> onProcessStart) {
        log.info("执行命令: {}", String.join(" ", cmd.args()));
        if (cmd.workDir() != null) {
            log.debug("  workDir={}", cmd.workDir());
        }
        log.debug("  timeout={}ms, retry={}/{}ms, maxOutputLines={}, throwOnNonZero={}",
                cmd.timeoutMs(), cmd.retryCount(), cmd.retryDelayMs(),
                cmd.maxOutputLines(), cmd.throwOnNonZero());

        for (int attempt = 0; attempt <= cmd.retryCount(); attempt++) {
            try {
                return executeOnce(cmd, onProcessStart);
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

    /** 无回调版本，委托给 {@link #run(Command, java.util.function.Consumer)}。 */
    public static CommandResult run(Command cmd) {
        return run(cmd, null);
    }

    private static CommandResult executeOnce(Command cmd, java.util.function.Consumer<Process> onProcessStart) {
        ProcessBuilder pb = new ProcessBuilder(cmd.args());
        if (cmd.workDir() != null) {
            pb.directory(cmd.workDir().toFile());
        }
        ProcessBuilder.Redirect errRedirect = cmd.errorRedirect();
        if (errRedirect != null) {
            pb.redirectError(errRedirect);
        } else {
            pb.redirectErrorStream(true);
        }

        Instant start = Instant.now();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("启动进程失败: " + String.join(" ", cmd.args()), e);
        }

        if (onProcessStart != null) onProcessStart.accept(process);
        registerProcess(process);
        try {
            return doExecuteOnce(cmd, process);
        } finally {
            unregisterProcess(process);
        }
    }

    private static CommandResult doExecuteOnce(Command cmd, Process process) {
        Instant start = Instant.now();

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
                killProcessTree(process);
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
            killProcessTree(process);
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

    /**
     * 执行命令并捕获 stdout 原始字节（适用于 ffmpeg 等二进制输出）。
     * <p>
     * 与 {@link #run(Command)} 的区别：
     * <ul>
     *   <li>按 {@link Command#errorRedirect()} 设置 stderr 重定向（默认丢弃）</li>
     *   <li>stdout 以字节流读取，不涉及字符编码</li>
     *   <li>返回 {@link BinaryResult}（exitCode + byte[]）</li>
     * </ul>
     *
     * @param cmd 命令配置
     * @return 二进制执行结果
     */
    public static BinaryResult runBinary(Command cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd.args());
        if (cmd.workDir() != null) {
            pb.directory(cmd.workDir().toFile());
        }
        ProcessBuilder.Redirect errRedirect = cmd.errorRedirect();
        if (errRedirect != null) {
            pb.redirectError(errRedirect);
        } else {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("启动二进制进程失败: " + String.join(" ", cmd.args()), e);
        }

        registerProcess(process);
        try {
            // 虚拟线程读取 stdout 字节
            byte[][] outputRef = new byte[1][];
            Thread reader = Thread.ofVirtual().name("bin-reader").start(() -> {
                try (var is = process.getInputStream()) {
                    outputRef[0] = is.readAllBytes();
                } catch (IOException e) {
                    // 进程销毁时流关闭，属于正常情况
                }
            });

            // 等待进程完成（带超时）
            boolean finished;
            try {
                if (cmd.timeoutMs() > 0) {
                    finished = process.waitFor(cmd.timeoutMs(), TimeUnit.MILLISECONDS);
                } else {
                    process.waitFor();
                    finished = true;
                }
            } catch (InterruptedException e) {
                killProcessTree(process);
                Thread.currentThread().interrupt();
                throw new RuntimeException("二进制进程被中断: " + String.join(" ", cmd.args()), e);
            }

            if (!finished) {
                killProcessTree(process);
                throw new RuntimeException("二进制进程超时(" + cmd.timeoutMs() + "ms): "
                        + String.join(" ", cmd.args()));
            }

            try {
                reader.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int exitCode = process.exitValue();

            if (cmd.throwOnNonZero() && exitCode != 0) {
                throw new RuntimeException("二进制进程退出码 " + exitCode + "，命令：" + String.join(" ", cmd.args()));
            }

            return new BinaryResult(exitCode, outputRef[0] != null ? outputRef[0] : new byte[0]);
        } finally {
            unregisterProcess(process);
        }
    }

    /**
     * 销毁进程及其整个子进程树。
     * <p>单纯的 {@link Process#destroyForcibly()} 仅终止直接子进程，
     * 但子进程可能已派生孙进程（如 Python 调用 ffmpeg），
     * 需要遍历 {@link Process#descendants()} 确保全部清理。</p>
     */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
