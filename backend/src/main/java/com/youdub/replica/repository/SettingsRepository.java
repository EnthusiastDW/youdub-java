package com.youdub.replica.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置数据访问层。
 * 键值对存储，使用 SQLite settings 表。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取设置值，不存在时返回默认值。
     */
    public String get(String key, String defaultValue) {
        try {
            String sql = "SELECT value FROM settings WHERE key = ?";
            List<String> values = jdbcTemplate.queryForList(sql, String.class, key);
            if (values.isEmpty()) {
                return defaultValue;
            }
            return values.get(0);
        } catch (Exception e) {
            log.warn("读取设置 {} 失败：{}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 保存设置值（UPSERT）。
     */
    public void set(String key, String value) {
        String sql = """
                INSERT INTO settings (key, value, updated_at)
                VALUES (?, ?, datetime('now'))
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = datetime('now')
                """;
        jdbcTemplate.update(sql, key, value);
    }

    /**
     * 获取所有设置。
     */
    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT key, value FROM settings";
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("key"), rs.getString("value"));
        });
        return result;
    }
}
