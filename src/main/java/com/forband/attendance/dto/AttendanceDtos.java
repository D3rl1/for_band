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

    /**
     * 考勤明细的一行。{@code status} 只来自 attendance 表（**区分不出"申请中"**），所以额外带
     * {@code leaveStatus} = 该人在本场的请假状态（`PENDING` / `APPROVED` / `REJECTED` /
     * `CANCELED` / null）——界面靠它决定要不要出现「代签」（docs/05 §5.3 / §5.6）。
     * <p>
     * {@code avatar} = 预置头像的标识符（`null` = 不用头像），明细里要显示头像（docs/14 §14.6.4
     * 「名单里的头像」）。
     */
    public record AttendanceRow(Long recordId, long memberId, String name, String avatar,
                                String sectionName, String status, LocalDateTime markedAt,
                                Long markedBy, String markedByName, String leaveStatus) {
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
