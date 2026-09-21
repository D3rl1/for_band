package com.forband.event.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * updatedBy != createdBy 时前端显示「已被 XXX 修改于 …」（docs/04 H2 痕迹，期 1 只做 UI 层）。
 */
public record EventView(
        long id,
        String kind,
        String title,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String location,
        Long sectionId,
        String sectionName,
        String content,
        String batchId,
        String status,
        long createdBy,
        String createdByName,
        Long updatedBy,
        String updatedByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String myAttendanceStatus) {

    /** 列表用轻量视图：不查本人考勤。 */
    public static EventView of(com.forband.event.Event e, String sectionName,
                               String createdByName, String updatedByName) {
        return new EventView(e.getId(), e.getKind(), e.getTitle(), e.getDate(), e.getStartTime(),
                e.getEndTime(), e.getLocation(), e.getSectionId(), sectionName, e.getContent(),
                e.getBatchId(), e.getStatus(), e.getCreatedBy(), createdByName, e.getUpdatedBy(),
                updatedByName, e.getCreatedAt(), e.getUpdatedAt(), null);
    }
}
