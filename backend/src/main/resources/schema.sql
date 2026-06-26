CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(64) PRIMARY KEY,
    url TEXT NOT NULL DEFAULT '',
    title VARCHAR(512) DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    current_stage VARCHAR(32),
    session_path VARCHAR(1024) DEFAULT '',
    final_video_path VARCHAR(1024) DEFAULT '',
    error_message TEXT DEFAULT '',
    execution_mode VARCHAR(16) NOT NULL DEFAULT 'auto',
    source_type VARCHAR(32) DEFAULT '',
    asr_language VARCHAR(8) DEFAULT '',
    target_language VARCHAR(8) DEFAULT '',
    progress REAL DEFAULT 0.0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    started_at TEXT,
    completed_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_url ON tasks(url);

CREATE TABLE IF NOT EXISTS task_stages (
    task_id VARCHAR(64) NOT NULL,
    name VARCHAR(32) NOT NULL,
    label VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    started_at TEXT,
    completed_at TEXT,
    last_message VARCHAR(512) DEFAULT '',
    error_message TEXT DEFAULT '',
    PRIMARY KEY (task_id, name),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_task_stages_task_status ON task_stages(task_id, status);

CREATE TABLE IF NOT EXISTS settings (
    key VARCHAR(128) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
