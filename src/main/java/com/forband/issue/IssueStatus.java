package com.forband.issue;

/** 问题状态四档（docs/15 §15.4）。存储值固定，界面文案与颜色由前端映射。 */
public enum IssueStatus {

    PENDING("待处理"),
    PROCESSING("处理中"),
    FIXED("已修复"),
    REJECTED("不处理");

    private final String label;

    IssueStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析失败返回 null；调用方负责转成 VALIDATION_FAILED。 */
    public static IssueStatus parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
