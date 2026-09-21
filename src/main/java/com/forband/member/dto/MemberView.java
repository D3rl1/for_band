package com.forband.member.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 名册视图。phone 由服务层按角色裁剪（仅管理员/团长/指挥可见）；绝不含密码字段。 */
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
        LocalDateTime lastLoginAt,
        String note) {
}
