package com.youdub.replica.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Command} 和 {@link CommandResult} 的单元测试。
 */
class CommandTest {

    // ==================== parseArgs() ====================

    @Test
    void parseArgs_simpleSpaceSeparated() {
        assertEquals(List.of("echo", "hello"), Command.parseArgs("echo hello"));
    }

    @Test
    void parseArgs_multipleSpacesCompressed() {
        assertEquals(List.of("echo", "hello"), Command.parseArgs("echo   hello"));
    }

    @Test
    void parseArgs_doubleQuotePreservesSpaces() {
        assertEquals(List.of("echo", "hello world"), Command.parseArgs("echo \"hello world\""));
    }

    @Test
    void parseArgs_singleQuotePreservesSpaces() {
        assertEquals(List.of("echo", "hello world"), Command.parseArgs("echo 'hello world'"));
    }

    @Test
    void parseArgs_doubleQuoteWithBackslashEscape() {
        assertEquals(List.of("echo", "hello\"world"), Command.parseArgs("echo \"hello\\\"world\""));
    }

    @Test
    void parseArgs_mixedQuotes() {
        assertEquals(List.of("a", "b c", "d"), Command.parseArgs("a 'b c' d"));
    }

    @Test
    void parseArgs_emptyStringReturnsEmptyList() {
        assertTrue(Command.parseArgs("").isEmpty());
    }

    @Test
    void parseArgs_blankStringReturnsEmptyList() {
        assertTrue(Command.parseArgs("   ").isEmpty());
    }

    // ==================== Builder — add() ====================

    @Test
    void builder_addVarargs() {
        Command cmd = Command.builder().add("echo", "hello").build();
        assertEquals(List.of("echo", "hello"), cmd.args());
    }

    @Test
    void builder_addList() {
        Command cmd = Command.builder().add(List.of("ping", "127.0.0.1")).build();
        assertEquals(List.of("ping", "127.0.0.1"), cmd.args());
    }

    @Test
    void builder_addFiltersNullAndEmpty() {
        Command cmd = Command.builder().add("echo", null, "", "hello").build();
        assertEquals(List.of("echo", "hello"), cmd.args());
    }

    @Test
    void builder_addNullList() {
        Command.Builder builder = Command.builder().add((List<String>) null);
        // should not throw and args should still be empty
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    // ==================== Builder — parse() ====================

    @Test
    void builder_parseString() {
        Command cmd = Command.builder().parse("echo \"hello world\"").build();
        assertEquals(List.of("echo", "hello world"), cmd.args());
    }

    @Test
    void builder_parseWithNullAndBlank_noChange() {
        Command cmd = Command.builder().add("echo").parse(null).parse("").parse("   ").parse("test").build();
        assertEquals(List.of("echo", "test"), cmd.args());
    }

    // ==================== Builder — timeout ====================

    @Test
    void builder_timeoutDefaultZero() {
        Command cmd = Command.builder().add("x").build();
        assertEquals(0, cmd.timeoutMs(), "默认 timeoutMs=0 表示不超时");
    }

    @Test
    void builder_timeoutSetter() {
        Command cmd = Command.builder().add("x").timeout(5000).build();
        assertEquals(5000, cmd.timeoutMs());
    }

    // ==================== Builder — retry ====================

    @Test
    void builder_retryNormal() {
        Command cmd = Command.builder().add("x").retry(3, 10000).build();
        assertEquals(3, cmd.retryCount());
        assertEquals(10000, cmd.retryDelayMs());
    }

    @Test
    void builder_retryClampsNegative() {
        Command cmd = Command.builder().add("x").retry(-1, -1).build();
        assertEquals(0, cmd.retryCount());
        assertEquals(0, cmd.retryDelayMs());
    }

    // ==================== Builder — maxOutputLines ====================

    @Test
    void builder_maxOutputLinesDefault() {
        Command cmd = Command.builder().add("x").build();
        assertEquals(10000, cmd.maxOutputLines());
    }

    @Test
    void builder_maxOutputLinesSetter() {
        Command cmd = Command.builder().add("x").maxOutputLines(999).build();
        assertEquals(999, cmd.maxOutputLines());
    }

    // ==================== Builder — throwOnNonZero ====================

    @Test
    void builder_throwOnNonZeroDefault() {
        Command cmd = Command.builder().add("x").build();
        assertTrue(cmd.throwOnNonZero());
    }

    @Test
    void builder_throwOnNonZeroFalse() {
        Command cmd = Command.builder().add("x").throwOnNonZero(false).build();
        assertFalse(cmd.throwOnNonZero());
    }

    // ==================== Builder — callbacks ====================

    @Test
    void builder_callbacks() {
        Runnable timeoutAction = () -> {};
        Consumer<String> lineHandler = s -> {};
        Consumer<Integer> exitHandler = i -> {};
        Command cmd = Command.builder().add("x")
                .onTimeout(timeoutAction)
                .onLine(lineHandler)
                .onExit(exitHandler)
                .build();
        assertSame(timeoutAction, cmd.onTimeout());
        assertSame(lineHandler, cmd.onLine());
        assertEquals(1, cmd.onExit().size());
        assertSame(exitHandler, cmd.onExit().get(0));
    }

    @Test
    void builder_multipleOnExit() {
        Command cmd = Command.builder().add("x")
                .onExit(i -> {})
                .onExit(i -> {})
                .build();
        assertEquals(2, cmd.onExit().size());
    }

    @Test
    void builder_onExitNullIgnored() {
        Command cmd = Command.builder().add("x")
                .onExit(null)
                .onExit(i -> {})
                .build();
        assertEquals(1, cmd.onExit().size());
    }

    @Test
    void builder_onTimeoutNotSet() {
        Command cmd = Command.builder().add("x").build();
        assertNull(cmd.onTimeout());
    }

    @Test
    void builder_onLineNotSet() {
        Command cmd = Command.builder().add("x").build();
        assertNull(cmd.onLine());
    }

    // ==================== Builder — workDir ====================

    @Test
    void builder_workDir() {
        Path dir = Path.of("/tmp");
        Command cmd = Command.builder().add("x").workDir(dir).build();
        assertEquals(dir, cmd.workDir());
    }

    @Test
    void builder_workDirDefaultNull() {
        Command cmd = Command.builder().add("x").build();
        assertNull(cmd.workDir());
    }

    // ==================== Builder — build() validation ====================

    @Test
    void builder_buildWithEmptyArgs_throws() {
        assertThrows(IllegalArgumentException.class, () -> Command.builder().build());
    }

    // ==================== Immutability ====================

    @Test
    void builder_argsListIsUnmodifiable() {
        Command cmd = Command.builder().add("echo", "hello").build();
        assertThrows(UnsupportedOperationException.class, () -> cmd.args().add("pwned"));
    }

    @Test
    void builder_onExitListIsUnmodifiable() {
        Command cmd = Command.builder().add("x").onExit(i -> {}).build();
        assertThrows(UnsupportedOperationException.class, () -> cmd.onExit().add(i -> {}));
    }

    // ==================== CommandResult ====================

    @Test
    void commandResult_record() {
        CommandResult r = new CommandResult(0, "ok", List.of("ok"), Duration.ofSeconds(1));
        assertEquals(0, r.exitCode());
        assertEquals("ok", r.output());
        assertEquals(List.of("ok"), r.lines());
        assertEquals(Duration.ofSeconds(1), r.elapsed());
    }
}
