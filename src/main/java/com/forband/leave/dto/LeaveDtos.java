package com.forband.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class LeaveDtos {

    private LeaveDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "请假理由不能为空") @Size(max = 255) String reason) {
    }

    public record DecideRequest(@NotBlank String decision, @Size(max = 255) String note) {
    }

    public record ApprovalView(long id, String requiredRole, Long approverId, String approverName,
                               String decision, LocalDateTime decidedAt, String note,
                               boolean iCanDecide) {
    }

    /**
     * overdue = 活动已结束仍 PENDING（docs/05：后台标红提醒审批人）。
     * {@code attendanceOverridden} = 这一次批准**覆盖了一条已有签到**（PRESENT → LEAVE）——
     * 界面据此当场提示（docs/05 §5.5：留痕 + 当场提示缺一不可，绝不许静默）。
     */
    public record RequestView(long id, long eventId, String eventTitle, String eventKind,
                              String eventDate, String eventStartTime, String eventStatus,
                              long memberId, String memberName, String sectionName,
                              String reason, String status, LocalDateTime appliedAt,
                              boolean overdue, List<ApprovalView> approvals,
                              boolean attendanceOverridden) {
    }
}
