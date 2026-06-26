package com.youdub.replica.model.enums;

public enum StageStatus {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String dbValue;

    StageStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String toDbValue() {
        return dbValue;
    }

    public static StageStatus fromDbValue(String value) {
        for (StageStatus s : values()) {
            if (s.dbValue.equalsIgnoreCase(value)) return s;
        }
        return PENDING;
    }
}
