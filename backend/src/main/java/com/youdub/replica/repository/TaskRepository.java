package com.youdub.replica.repository;

import com.youdub.replica.model.entity.Task;
import com.youdub.replica.model.entity.TaskStage;
import com.youdub.replica.model.enums.StageStatus;
import com.youdub.replica.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务数据访问层（基于 JdbcTemplate，不使用 JPA）。
 * 时间戳以 TEXT 类型存储 ISO 格式。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Task> TASK_ROW_MAPPER = (rs, rowNum) -> {
        Task task = new Task();
        task.setId(rs.getString("id"));
        task.setUrl(rs.getString("url"));
        task.setTitle(rs.getString("title"));
        task.setStatus(TaskStatus.fromDbValue(rs.getString("status")));
        task.setCurrentStage(rs.getString("current_stage"));
        task.setSessionPath(rs.getString("session_path"));
        task.setFinalVideoPath(rs.getString("final_video_path"));
        task.setErrorMessage(rs.getString("error_message"));
        task.setExecutionMode(rs.getString("execution_mode"));
        task.setSourceType(rs.getString("source_type"));
        task.setAsrLanguage(rs.getString("asr_language"));
        task.setTargetLanguage(rs.getString("target_language"));
        task.setProgress(rs.getDouble("progress"));
        task.setCreatedAt(rs.getString("created_at"));
        task.setStartedAt(rs.getString("started_at"));
        task.setCompletedAt(rs.getString("completed_at"));
        task.setNotes(rs.getString("notes"));
        task.setYoutubeVideoId(rs.getString("youtube_video_id"));
        return task;
    };

    private static final RowMapper<TaskStage> STAGE_ROW_MAPPER = (rs, rowNum) -> {
        TaskStage stage = new TaskStage();
        stage.setTaskId(rs.getString("task_id"));
        stage.setName(rs.getString("name"));
        stage.setLabel(rs.getString("label"));
        stage.setStatus(StageStatus.fromDbValue(rs.getString("status")));
        stage.setProgress(rs.getInt("progress"));
        stage.setStartedAt(rs.getString("started_at"));
        stage.setCompletedAt(rs.getString("completed_at"));
        stage.setLastMessage(rs.getString("last_message"));
        stage.setErrorMessage(rs.getString("error_message"));
        return stage;
    };

    /**
     * 插入任务。
     */
    public void insert(Task task) {
        String sql = """
                INSERT INTO tasks (id, url, title, status, current_stage, session_path, final_video_path,
                                   error_message, execution_mode, source_type, asr_language, target_language,
                                   progress, created_at, started_at, completed_at, notes, youtube_video_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                task.getId(),
                task.getUrl(),
                task.getTitle(),
                task.getStatus().toDbValue(),
                task.getCurrentStage(),
                task.getSessionPath(),
                task.getFinalVideoPath(),
                task.getErrorMessage(),
                task.getExecutionMode(),
                task.getSourceType(),
                task.getAsrLanguage(),
                task.getTargetLanguage(),
                task.getProgress(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getNotes(),
                task.getYoutubeVideoId()
        );
    }

    /**
     * 插入阶段。
     */
    public void insertStage(TaskStage stage) {
        String sql = """
                INSERT INTO task_stages (task_id, name, label, status, progress, started_at, completed_at,
                                         last_message, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                stage.getTaskId(),
                stage.getName(),
                stage.getLabel(),
                stage.getStatus().toDbValue(),
                stage.getProgress(),
                stage.getStartedAt(),
                stage.getCompletedAt(),
                stage.getLastMessage(),
                stage.getErrorMessage()
        );
    }

    /**
     * 按 ID 查找任务（包含阶段列表）。
     */
    public Task findById(String id) {
        String sql = "SELECT * FROM tasks WHERE id = ?";
        List<Task> tasks = jdbcTemplate.query(sql, TASK_ROW_MAPPER, id);
        if (tasks.isEmpty()) {
            return null;
        }
        Task task = tasks.get(0);
        task.setStages(findStagesByTaskId(id));
        return task;
    }

    /**
     * 查找任务列表（按创建时间倒序，不含阶段），支持分页。
     */
    public List<Task> findAll(int offset, int limit) {
        String sql = "SELECT * FROM tasks ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, TASK_ROW_MAPPER, limit, offset);
    }

    /**
     * 统计任务总数。
     */
    public int countAll() {
        String sql = "SELECT COUNT(*) FROM tasks";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    /**
     * 查找当前运行中的任务（status=running）。
     */
    public Task findCurrent() {
        String sql = "SELECT * FROM tasks WHERE status = ? ORDER BY started_at DESC LIMIT 1";
        List<Task> tasks = jdbcTemplate.query(sql, TASK_ROW_MAPPER, TaskStatus.RUNNING.toDbValue());
        if (tasks.isEmpty()) {
            return null;
        }
        Task task = tasks.get(0);
        task.setStages(findStagesByTaskId(task.getId()));
        return task;
    }

    /**
     * 更新任务状态与进度。
     */
    public void updateStatus(String id, TaskStatus status, double progress) {
        String sql = "UPDATE tasks SET status = ?, progress = ? WHERE id = ?";
        jdbcTemplate.update(sql, status.toDbValue(), progress, id);
    }

    /**
     * 更新阶段状态。
     */
    public void updateStageStatus(String taskId, String stageName, StageStatus status, int progress, String error) {
        String sql = """
                UPDATE task_stages
                SET status = ?, progress = ?, error_message = ?,
                    started_at = CASE WHEN ? = 'running' AND started_at IS NULL THEN datetime('now') ELSE started_at END,
                    completed_at = CASE WHEN ? IN ('succeeded','failed') THEN datetime('now') ELSE completed_at END
                WHERE task_id = ? AND name = ?
                """;
        jdbcTemplate.update(sql,
                status.toDbValue(),
                progress,
                error == null ? "" : error,
                status.toDbValue(),
                status.toDbValue(),
                taskId,
                stageName
        );
    }

    /**
     * 通用字段更新（白名单）。
     */
    public void updateField(String id, String field, Object value) {
        List<String> allowed = List.of(
                "url", "title", "status", "current_stage", "session_path", "final_video_path",
                "error_message", "execution_mode", "source_type", "asr_language", "target_language",
                "progress", "started_at", "completed_at", "notes", "youtube_video_id"
        );
        if (!allowed.contains(field)) {
            throw new IllegalArgumentException("不允许更新的字段：" + field);
        }
        String sql = "UPDATE tasks SET " + field + " = ? WHERE id = ?";
        jdbcTemplate.update(sql, value, id);
    }

    /**
     * 查找任务的所有阶段。
     */
    public List<TaskStage> findStagesByTaskId(String taskId) {
        String sql = "SELECT * FROM task_stages WHERE task_id = ? ORDER BY rowid";
        return jdbcTemplate.query(sql, STAGE_ROW_MAPPER, taskId);
    }
    /**
     * 直接更新阶段的 started_at/completed_at（用于恢复原始时间戳）。
     */
    public void updateStageTimestamps(String taskId, String stageName, String startedAt, String completedAt) {
        String sql = "UPDATE task_stages SET started_at = ?, completed_at = ? WHERE task_id = ? AND name = ?";
        jdbcTemplate.update(sql, startedAt, completedAt, taskId, stageName);
    }

    /**
     * 从指定阶段开始重置为 pending（包括该阶段）。
     */
    public void resetStagesFrom(String taskId, String stageName) {
        // 获取该任务所有阶段的顺序（按 rowid）
        String selectSql = "SELECT name FROM task_stages WHERE task_id = ? ORDER BY rowid";
        List<String> names = jdbcTemplate.queryForList(selectSql, String.class, taskId);

        int startIdx = names.indexOf(stageName);
        if (startIdx < 0) {
            log.warn("未找到阶段 {}，任务 {}", stageName, taskId);
            return;
        }

        List<String> toReset = names.subList(startIdx, names.size());
        if (toReset.isEmpty()) {
            return;
        }

        String placeholders = String.join(",", toReset.stream().map(n -> "?").toList());
        String sql = "UPDATE task_stages SET status = ?, progress = 0, started_at = NULL, completed_at = NULL, " +
                "error_message = '' WHERE task_id = ? AND name IN (" + placeholders + ")";
        List<Object> params = new ArrayList<>();
        params.add(StageStatus.PENDING.toDbValue());
        params.add(taskId);
        params.addAll(toReset);
        jdbcTemplate.update(sql, params.toArray());
    }

    /**
     * 硬删除任务（含阶段，依赖外键级联）。
     */
    public void hardDelete(String id) {
        jdbcTemplate.update("DELETE FROM task_stages WHERE task_id = ?", id);
        jdbcTemplate.update("DELETE FROM tasks WHERE id = ?", id);
    }

    /**
     * 按 URL 查找任务（用于重复检测）。
     */
    public Task findByUrl(String url) {
        String sql = "SELECT * FROM tasks WHERE url = ? ORDER BY created_at DESC LIMIT 1";
        List<Task> tasks = jdbcTemplate.query(sql, TASK_ROW_MAPPER, url);
        if (tasks.isEmpty()) {
            return null;
        }
        Task task = tasks.get(0);
        task.setStages(findStagesByTaskId(task.getId()));
        return task;
    }
}
