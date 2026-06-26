package com.youdub.replica.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandRunner} 的集成测试。
 * 所有命令均使用 Windows 安全命令，不依赖 Unix 工具。
 */
class CommandRunnerTest {

    // ==================== 基础执行 ====================

    @Test
    void echoHello() {
        CommandResult r = CommandRunner.run(Command.builder()
                .add("cmd", "/c", "echo hello")
                .build());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("hello"),
                "Output should contain 'hello'. Actual: " + r.output());
    }

    @Test
    void outputCapture() {
        CommandResult r = CommandRunner.run(Command.builder()
                .add("cmd", "/c", "echo Hello World")
                .build());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("Hello World"),
                "Output should contain 'Hello World'. Actual: " + r.output());
        assertFalse(r.lines().isEmpty());
    }

    @Test
    void nonZeroExitThrows() {
        assertThrows(RuntimeException.class, () ->
                CommandRunner.run(Command.builder()
                        .add("cmd", "/c", "exit 1")
                        .build()));
    }

    @Test
    void nonZeroExitAllowed() {
        CommandResult r = CommandRunner.run(Command.builder()
                .add("cmd", "/c", "exit 1")
                .throwOnNonZero(false)
                .build());
        assertEquals(1, r.exitCode());
    }

    // ==================== 回调测试 ====================

    @Test
    void onLineCallback() {
        List<String> collected = new ArrayList<>();
        CommandRunner.run(Command.builder()
                .add("cmd", "/c", "echo Hello World")
                .onLine(collected::add)
                .build());
        assertFalse(collected.isEmpty());
        assertTrue(collected.stream().anyMatch(s -> s.contains("Hello World")),
                "Collected lines should contain 'Hello World'. Got: " + collected);
    }

    @Test
    void onExitCallback() {
        int[] exitBox = new int[1];
        CommandRunner.run(Command.builder()
                .add("cmd", "/c", "echo ok")
                .onExit(code -> exitBox[0] = code)
                .build());
        assertEquals(0, exitBox[0]);
    }

    // ==================== 超时测试 ====================

    @Test
    void timeout() {
        assertThrows(RuntimeException.class, () ->
                CommandRunner.run(Command.builder()
                        .add("cmd", "/c", "ping -n 5 127.0.0.1")
                        .timeout(1500)
                        .build()));
    }

    // ==================== workDir 测试 ====================

    @Test
    void workDir() throws IOException {
        Path tempDir = Files.createTempDirectory("youdub-test-");
        try {
            CommandResult r = CommandRunner.run(Command.builder()
                    .add("cmd", "/c", "cd")
                    .workDir(tempDir)
                    .build());
            // Normalize path separators for comparison
            String normalizedOutput = r.output().trim().replace("\\", "/");
            String normalizedDir = tempDir.toString().replace("\\", "/");
            assertTrue(normalizedOutput.contains(normalizedDir),
                    "Output should contain workDir path.\n  Output: " + r.output()
                            + "\n  workDir: " + tempDir);
        } finally {
            Files.deleteIfExists(tempDir);
        }
    }

    // ==================== retry 测试 ====================

    @Test
    void retry() {
        AtomicInteger exitCount = new AtomicInteger(0);
        assertThrows(RuntimeException.class, () ->
                CommandRunner.run(Command.builder()
                        .add("cmd", "/c", "exit 1")
                        .retry(2, 100)
                        .onExit(code -> exitCount.incrementAndGet())
                        .build()));
        // retry(2) = 3 total attempts, each attempt calls onExit before throwing
        assertTrue(exitCount.get() >= 2,
                "Expected at least 2 exit callbacks (3 attempts with retry(2)), got: " + exitCount.get());
    }

    // ==================== maxOutputLines 测试 ====================

    @Test
    void maxOutputLines() {
        CommandResult r = CommandRunner.run(Command.builder()
                .add("cmd", "/c", "for /l %i in (1,1,10) do @echo line%i")
                .maxOutputLines(2)
                .build());
        assertTrue(r.lines().size() <= 2,
                "Expected <= 2 lines, got: " + r.lines().size());
    }

    // ==================== 边缘情况 ====================

    @Test
    void simpleSingleLineOutput() {
        // 'ver' outputs Windows version string
        CommandResult r = CommandRunner.run(Command.builder()
                .add("cmd", "/c", "ver")
                .build());
        assertEquals(0, r.exitCode());
        assertFalse(r.output().isBlank(), "Output should not be blank");
    }
}
