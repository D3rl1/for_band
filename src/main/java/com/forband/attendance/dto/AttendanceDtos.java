package com.forband.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    /** 签到请求体可省（本人签）；带 memberId = 代签。 */
    public record CheckInRequest(Long memberId) {
    }

    public record AttendanceMarkRequest(String status) {
    }

    public record AttendanceRow(Long recordId, long memberId, String name, String sectionName,
                                String status, LocalDateTime markedAt,
                                Long markedBy, String markedByName) {
    }

    public record EventAttendance(long eventId, String eventTitle, LocalDate eventDate,
                                  String eventKind, String eventStatus,
                                  List<AttendanceRow> rows,
                                  int expected, int present, int leave, int absent, double rate) {
    }

    public record KindSummary(String kind, int events, int expected, int present,
                              int leave, int absent, double rate) {
    }

    /** 个人汇总：排练与声部课分开两列并排（docs/05 口径）。 */
    public record MemberSummary(long memberId, String name,
                                KindSummary rehearsal, KindSummary sectionClass) {
    }

    /** 聚合：两类口径并排展示；出勤率与请假数永远成对出现（docs/05）。 */
    public record AggregateStats(List<KindSummary> kinds) {
    }
}
