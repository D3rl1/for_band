package com.forband.log;

import java.time.LocalDateTime;

/** 日志视图。before/after 为 JSON 原文（不改写、不解析）。 */
public record LogView(
        long id,
        long operatorId,
        String operatorName,
        String action,
        String targetType,
        Long targetId,
        String before,
        String after,
        String ip,
        LocalDateTime createdAt) {
}
