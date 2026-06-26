package com.youdub.replica.util;

import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 任务会话目录解析器。
 * URL 来源：workfolder/{task.id}/（简化版，原方案为 workfolder/{uploader}/{title}__{videoId}/）
 * 本地来源：workfolder/local/{title}__{task.id}/
 */
@Component
@RequiredArgsConstructor
public class TaskDirResolver {

    private final AppProperties appProperties;

    /**
     * 解析任务的会话目录。
     */
    public Path resolveTaskDir(Task task) {
        Path base = Paths.get(appProperties.getWorkfolder()).toAbsolutePath().normalize();
        String sourceType = task.getSourceType() == null ? "" : task.getSourceType();
        String title = sanitize(task.getTitle() == null ? "" : task.getTitle());
        String taskId = task.getId();

        if ("local".equalsIgnoreCase(sourceType)) {
            return base.resolve("local").resolve(title + "__" + taskId);
        }
        return base.resolve(taskId);
    }

    /**
     * 解析上传目录：workfolder/_uploads/{taskId}/
     */
    public Path resolveUploadDir(String taskId) {
        Path base = Paths.get(appProperties.getWorkfolder()).toAbsolutePath().normalize();
        return base.resolve("_uploads").resolve(taskId);
    }

    /**
     * 清洗文件名中的非法字符。
     */
    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
