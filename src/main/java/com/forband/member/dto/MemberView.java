package com.forband.member.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 名册视图。可见性**全部由服务层裁剪**（`docs/03` §3.4 的「`MemberView` 各字段的可见性表」），
 * 绝不含密码字段。
 * <p>
 * ⚠️ **`birthday` / `joinedAt` / `leftAt` 是字符串**，因为它们**对不同的看的人给不同粒度**：
 * 生日对外只到「月-日」、入团/退团时间对外只到年份，本人与管理员才拿全值（`docs/13` §13.3②）。
 * 用字符串而不是日期类型，是"只到"这条口径最直接的表达——日期类型给不出"部分"。
 * <p>
 * ⚠️ **两条口径是用户 2026-09-25 当场定的，与 `docs/03` §3.4 那张表相反**（见 `交接单.md` `#42` 的移交事项）：
 * `contact` **对全团可见**（"填了就说明不担心"）、`account` **维持可见**（"就是姓名拼音"）。
 * 改这里之前先看那两条，别照旧表把 `contact` 又收回去。
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
        Boolean activated,
        String joinedAt,
        String leftAt,
        LocalDate honoraryUntil,
        LocalDateTime lastLoginAt,
        String note,
        String avatar,
        String avatarFrame,
        String intro,
        String contact,
        String birthday) {
}
