package com.forband.member.dto;

/** 建号响应：初始密码只在此刻返回一次（docs/01）。 */
public record MemberCreateResponse(
        long id,
        String name,
        String account,
        String sectionName,
        String role,
        String initialPassword) {
}
