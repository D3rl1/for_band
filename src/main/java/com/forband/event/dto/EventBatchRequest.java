package com.forband.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 批量生成（docs/04）：星期几 + 起止时间 + 日期范围 + 地点 + 目标。
 * 不做 RRULE，节假日靠事后逐场取消/改期。
 */
public record EventBatchRequest(
        @NotBlank String kind,
        @NotBlank @Size(max = 64) String title,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotEmpty List<Integer> weekdays,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @Size(max = 64) String location,
        Long sectionId,
        String content) {
}
