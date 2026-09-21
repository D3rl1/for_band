package com.forband.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class LeaveDtos {

    private LeaveDtos() {
    }

    public record CreateRequest(@Size(max = 255) String reason) {
    }

    public record DecideRequest(@NotBlank String decision, @Size(max = 255) String note) {
    }

    public record ApprovalView(long id, String requiredRole, Long approverId, String approverName,
                               String decision, LocalDateTime decidedAt, String note,
                               boolean iCanDecide) {
    }

    /** overdue = 活动已结束仍 PENDING（docs/05：后台标红提醒审批人）。 */
    public record RequestView(long id, long eventId, String eventTitle, String eventKind,
                              String eventDate, String eventStartTime, String eventStatus,
                              long memberId, String memberName, String sectionName,
                              String reason, String status, LocalDateTime appliedAt,
                              boolean overdue, List<ApprovalView> approvals) {
    }
}
