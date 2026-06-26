package com.youdub.replica.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 命令配置类（不可变）。
 * 通过 Builder 模式构建，支持链式添加参数、字符串解析、超时/重试/回调配置。
 */
@Slf4j
public final class Command {

    private final List<String> args;
    private final Path workDir;
    private final long timeoutMs;
    private final int retryCount;
    private final long retryDelayMs;
    private final int maxOutputLines;
    private final Runnable onTimeout;
    private final Consumer<String> onLine;
    private final List<Consumer<Integer>> onExit;
    private final boolean throwOnNonZero;

    private Command(Builder builder) {
        this.args = List.copyOf(builder.args);
        this.workDir = builder.workDir;
        this.timeoutMs = builder.timeoutMs;
        this.retryCount = builder.retryCount;
        this.retryDelayMs = builder.retryDelayMs;
        this.maxOutputLines = builder.maxOutputLines;
        this.onTimeout = builder.onTimeout;
        this.onLine = builder.onLine;
        this.onExit = builder.onExit.isEmpty()
                ? List.of()
                : List.copyOf(builder.onExit);
        this.throwOnNonZero = builder.throwOnNonZero;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- getters ----

    public List<String> args() { return args; }
    public Path workDir() { return workDir; }
    public long timeoutMs() { return timeoutMs; }
    public int retryCount() { return retryCount; }
    public long retryDelayMs() { return retryDelayMs; }
    public int maxOutputLines() { return maxOutputLines; }
    public Runnable onTimeout() { return onTimeout; }
    public Consumer<String> onLine() { return onLine; }
    public List<Consumer<Integer>> onExit() { return onExit; }
    public boolean throwOnNonZero() { return throwOnNonZero; }

    // ---- Builder ----

    public static final class Builder {
        private final List<String> args = new ArrayList<>();
        private Path workDir;
        private long timeoutMs; // 0=不超时，>0=毫秒数
        private int retryCount = 0;
        private long retryDelayMs = 5_000;
        private int maxOutputLines = 10_000;
        private Runnable onTimeout;
        private Consumer<String> onLine;
        private final List<Consumer<Integer>> onExit = new ArrayList<>();
        private boolean throwOnNonZero = true;

        /** 添加一个或多个参数，忽略 null 和空字符串。 */
        public Builder add(String... args) {
            if (args != null) {
                for (String a : args) {
                    if (a != null && !a.isEmpty()) {
                        this.args.add(a);
                    }
                }
            }
            return this;
        }

        /** 添加参数列表。 */
        public Builder add(List<String> args) {
            if (args != null) {
                for (String a : args) {
                    if (a != null && !a.isEmpty()) {
                        this.args.add(a);
                    }
                }
            }
            return this;
        }

        /**
         * 解析命令字符串，支持引号分组。
         * 规则：
         *  - 按空白字符分割
         *  - 单引号 '...' 内的空白不分割，不支持转义
         *  - 双引号 "..." 内的空白不分割，支持反斜杠 '\' 转义
         */
        public Builder parse(String commandString) {
            if (commandString == null || commandString.isBlank()) {
                return this;
            }
            this.args.addAll(parseArgs(commandString));
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        public Builder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** 失败重试次数和间隔。retryCount=0 即不重试。 */
        public Builder retry(int retryCount, long retryDelayMs) {
            this.retryCount = Math.max(0, retryCount);
            this.retryDelayMs = Math.max(0, retryDelayMs);
            return this;
        }

        /** 最大输出收集行数。-1 表示不限。 */
        public Builder maxOutputLines(int maxOutputLines) {
            this.maxOutputLines = maxOutputLines;
            return this;
        }

        /** 超时回调（进程被强制终止后调用）。 */
        public Builder onTimeout(Runnable onTimeout) {
            this.onTimeout = onTimeout;
            return this;
        }

        /** 每行输出到达时的回调。默认自动 DEBUG 日志。 */
        public Builder onLine(Consumer<String> onLine) {
            this.onLine = onLine;
            return this;
        }

        /** 注册进程退出回调（可多次调用注册多个）。 */
        public Builder onExit(Consumer<Integer> onExit) {
            if (onExit != null) {
                this.onExit.add(onExit);
            }
            return this;
        }

        /** 非零退出码是否抛异常（默认 true）。 */
        public Builder throwOnNonZero(boolean throwOnNonZero) {
            this.throwOnNonZero = throwOnNonZero;
            return this;
        }

        /** 构建不可变 Command 对象。 */
        public Command build() {
            if (args.isEmpty()) {
                throw new IllegalArgumentException("Command arguments cannot be empty");
            }
            return new Command(this);
        }
    }

    // ---- 引号解析 ----

    /**
     * Shell 风格参数解析。
     * 处理单引号、双引号和普通参数。
     */
    static List<String> parseArgs(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(++i));
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (Character.isWhitespace(c)) {
                    flushToken(current, result);
                } else {
                    current.append(c);
                }
            }
        }
        flushToken(current, result);
        return result;
    }

    private static void flushToken(StringBuilder buf, List<String> target) {
        if (buf.length() > 0) {
            target.add(buf.toString());
            buf.setLength(0);
        }
    }
}
