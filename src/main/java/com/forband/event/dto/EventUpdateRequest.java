package com.forband.event.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record EventUpdateRequest(
        @Size(max = 64) String title,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 64) String location,
        Long sectionId,
        String content) {
}
