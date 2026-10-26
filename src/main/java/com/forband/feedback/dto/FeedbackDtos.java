package com.forband.feedback.dto;

import java.time.LocalDateTime;

public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    /**
     * 管理端只读归档视图。device 由服务端把 UA 粗解析出来（与「登录设备」同一套规则）。
     * {@code migratedIssueId} 只对 MIGRATED 行有值，供归档页跳到对应的公开问题。
     */
    public record FeedbackView(long id, long memberId, String memberName, String sectionName,
                               String content, String pagePath, String device, String status,
                               Long handledBy, String handledByName, LocalDateTime handledAt,
                               String adminNote, LocalDateTime createdAt, Long migratedIssueId) {
    }
}
