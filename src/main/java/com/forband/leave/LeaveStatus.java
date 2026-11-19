package com.forband.leave;

/** 申请单状态与待签行决定（docs/05 §5.5）。 */
public final class LeaveStatus {

    // leave_request.status
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELED = "CANCELED";

    private LeaveStatus() {
    }
}
