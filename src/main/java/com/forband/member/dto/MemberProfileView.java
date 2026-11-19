package com.forband.member.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 个人页（docs/03 §3.3）。**只有本人可写的四个字段 + 只读的账号信息**——
 * 姓名 / 声部 / 乐器 / 角色 / 学号是**只读**的（页面上有一行说明：由管理员维护），
 * 所以请求体里**刻意没有这几个字段**：传了也进不来。
 */
public record MemberProfileView(long id, String name, String account, String role,
                                String sectionName, String instrument, String studentNo,
                                /** 入团年份要显示在头部标签行里，且**与个人栏浮层同源**（docs/14 §14.6.4）。 */
                                LocalDate joinedAt,
                                String avatar, String avatarFrame, String intro, String contact,
                                LocalDate birthday) {

    /** `null` = 这次不改；生日 / 头像框清空走显式开关（`null` 已经表示"不改"，不能兼两意）。 */
    public record ProfileRequest(@Size(max = 32) String avatar,
                                 @Size(max = 32) String avatarFrame,
                                 @Size(max = 100) String intro,
                                 @Size(max = 128) String contact,
                                 LocalDate birthday,
                                 Boolean clearBirthday,
                                 Boolean clearAvatarFrame) {
    }
}
