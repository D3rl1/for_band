package com.forband.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record MemberCreateRequest(
        @NotBlank @Size(max = 32) String name,
        @NotBlank @Size(max = 32) String account,
        Long sectionId,
        String role,
        String studentNo,
        String instrument,
        String phone,
        String note,
        LocalDate joinedAt) {
}
