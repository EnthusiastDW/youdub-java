package com.youdub.replica.util;

import java.time.Duration;
import java.util.List;

/**
 * 命令执行结果。
 *
 * @param exitCode 进程退出码
 * @param output   完整输出文本（始终完整，不受 maxOutputLines 影响）
 * @param lines    按行分割的输出（受 maxOutputLines 限制）
 * @param elapsed  执行耗时
 */
public record CommandResult(
        int exitCode,
        String output,
        List<String> lines,
        Duration elapsed
) {}
