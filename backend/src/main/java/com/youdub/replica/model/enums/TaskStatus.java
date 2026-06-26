package com.youdub.replica.model.enums;

public enum TaskStatus {
    QUEUED("queued"),
    RUNNING("running"),
    PAUSED("paused"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String dbValue;

    TaskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String toDbValue() {
        return dbValue;
    }

    public static TaskStatus fromDbValue(String value) {
        for (TaskStatus s : values()) {
            if (s.dbValue.equalsIgnoreCase(value)) return s;
        }
        return QUEUED;
    }
}
