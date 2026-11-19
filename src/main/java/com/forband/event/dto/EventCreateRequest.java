package com.forband.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record EventCreateRequest(
        @NotBlank String kind,
        @NotBlank @Size(max = 64) String title,
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @Size(max = 64) String location,
        Long sectionId,
        String content) {
}
