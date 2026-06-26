package com.youdub.replica.config;

import com.youdub.replica.repository.SettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时将 DB settings 表的值合并到 AppProperties。
 * DB 中的值覆盖 application.yml + 环境变量的默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigInitializer {

    private final AppProperties appProperties;
    private final SettingsRepository settingsRepository;

    @PostConstruct
    public void init() {
        log.info("正在合并 DB 设置到 AppProperties ...");
        appProperties.mergeFromDb(settingsRepository);
        log.info("DB 设置合并完成");
    }
}
