package com.youdub.replica;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 上下文加载测试。
 * 验证 Spring 应用上下文能够正常启动，所有 Bean 能够被正确装配。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class YouDubApplicationTest {

    @Autowired
    private com.youdub.replica.controller.HealthController healthController;

    @Autowired
    private com.youdub.replica.controller.TaskController taskController;

    @Autowired
    private com.youdub.replica.controller.SettingsController settingsController;

    @Autowired
    private com.youdub.replica.repository.TaskRepository taskRepository;

    @Autowired
    private com.youdub.replica.repository.SettingsRepository settingsRepository;

    @Autowired
    private com.youdub.replica.service.TaskService taskService;

    @Test
    void contextLoads() {
        assertNotNull(healthController);
        assertNotNull(taskController);
        assertNotNull(settingsController);
        assertNotNull(taskRepository);
        assertNotNull(settingsRepository);
        assertNotNull(taskService);
    }
}
