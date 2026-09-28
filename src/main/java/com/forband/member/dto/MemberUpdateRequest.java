package com.forband.member.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record MemberUpdateRequest(
        @Size(max = 32) String name,
        Long sectionId,
        String role,
        String status,
        String studentNo,
        String instrument,
        String phone,
        String note,
        LocalDate honoraryUntil,
        // 入团日期（docs/03 §3.4 上线迁移：老团员按真实入团日期录）。不传 = 不改；
        // 它门控考勤（应到名单要求 joined_at ≤ 活动日期），改动会动出勤率分母。
        LocalDate joinedAt,
        // 个人页四列（docs/03 §3.4「谁能改什么」：这几个**管理员也能改**）。不传 = 不改。
        // ⚠️ avatar 是**受控标识符**，服务端校验 ∈ 预置清单 ∪ {null}。
        String avatar,
        @Size(max = 100) String intro,
        @Size(max = 128) String contact,
        LocalDate birthday,
        // ⚠️ 清空声部必须带这个标记（docs/03 §3.4 边界②）：`sectionId` 为 null 在本接口一律当"未提供"
        // （`updateById` 忽略 null 字段），只发 `sectionId: null` 会被静默忽略 ⇒ 界面上"选无声部"点了没反应。
        Boolean clearSection) {
}
