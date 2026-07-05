package com.youdub.replica.util;

import com.youdub.replica.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.DEMUCS;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 设备解析器。
 * 检测可用计算设备（cuda > cpu），支持组件级环境变量覆盖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceResolver {

    private final AppProperties appProperties;

    /**
     * 检测可用设备：通过检查 nvidia-smi 命令是否可执行。
     *
     * @return "cuda" 或 "cpu"
     */
    public String resolveDevice() {
        if (isCudaAvailable()) {
            return "cuda";
        }
        return "cpu";
    }

    /**
     * 根据组件名称解析设备。
     * 优先读取环境变量 DEMUCS_DEVICE / WHISPER_DEVICE，未设置则回退到全局检测。
     *
     * @param component 组件名称（如 "demucs"、"whisper"）
     * @return 设备字符串
     */
    public String getDeviceForComponent(String component) {
        if (component == null) return resolveDevice();
        String envValue = switch (component.toLowerCase()) {
            case DEMUCS -> appProperties.getDeviceConfig().getDemucs();
            case "whisper" -> appProperties.getDeviceConfig().getWhisper();
            default -> null;
        };
        if (envValue != null && !envValue.isBlank()) {
            log.info("组件 {} 使用配置的设备：{}", component, envValue);
            return envValue.trim();
        }
        return resolveDevice();
    }

    /**
     * 检查 CUDA 是否可用（nvidia-smi 可执行且退出码 0）。
     */
    private boolean isCudaAvailable() {
        try {
            Process process = new ProcessBuilder("nvidia-smi")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("nvidia-smi 不可用，回退到 CPU：{}", e.getMessage());
            return false;
        }
    }
}
