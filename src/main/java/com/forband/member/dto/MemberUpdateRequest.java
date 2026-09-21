package com.forband.member.dto;

import jakarta.validation.constraints.Size;

public record MemberUpdateRequest(
        @Size(max = 32) String name,
        Long sectionId,
        String role,
        String status,
        String studentNo,
        String instrument,
        String phone,
        String note) {
}
