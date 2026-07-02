package com.youdub.replica.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Logback appender that writes log events to {@code {logDir}/task-{taskId}.log}
 * when MDC contains a {@code taskId} key.
 *
 * <p>Reads {@code APP_LOG_DIR} environment variable for the log directory,
 * defaulting to {@code data/logs}.
 *
 * <p>Designed to be used alongside the console appender — logs will appear
 * in both the console and the task-specific file when a task is active.
 */
public class TaskLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private Path logDir;

    @Override
    public void start() {
        String dir = System.getenv("APP_LOG_DIR");
        if (dir == null || dir.isBlank()) {
            dir = "data/logs";
        }
        this.logDir = Paths.get(dir);
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) return;

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null) return;
        String taskId = mdc.get("taskId");
        if (taskId == null || taskId.isBlank()) return;

        try {
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("task-" + taskId + ".log");

            String timestamp = TIME_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
            String line = String.format("[%s] [%-5s] %s%s",
                    timestamp,
                    event.getLevel().levelStr,
                    event.getFormattedMessage(),
                    System.lineSeparator());

            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            addError("Failed to write task log for taskId=" + taskId, e);
        }
    }
}
