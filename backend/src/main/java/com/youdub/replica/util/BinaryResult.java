package com.youdub.replica.util;

/**
 * 二进制命令执行结果。
 *
 * @param exitCode 进程退出码
 * @param data     stdout 原始字节数据
 */
public record BinaryResult(int exitCode, byte[] data) {}
