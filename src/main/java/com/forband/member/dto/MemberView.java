package com.forband.member.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 名册视图。可见性**全部由服务层裁剪**，绝不含密码字段：
 * `phone` 仅管理员 / 团长 / 指挥可见；`birthday` **仅本人 + 管理员**（docs/13 §13.3②）。
 */
public record MemberView(
        long id,
        String name,
        String studentNo,
        Long sectionId,
        String sectionName,
        String instrument,
        String phone,
        String role,
        String status,
        String account,
        boolean activated,
        LocalDate joinedAt,
        LocalDate leftAt,
        LocalDate honoraryUntil,
        LocalDateTime lastLoginAt,
        String note,
        String avatar,
        String intro,
        String contact,
        LocalDate birthday) {
}
