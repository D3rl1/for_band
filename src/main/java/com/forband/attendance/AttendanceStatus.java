package com.forband.attendance;

/**
 * 考勤只存 PRESENT / LEAVE（DECISIONS D9）。缺勤不存，靠"应到名单里没有记录"推导。
 */
public final class AttendanceStatus {

    public static final String PRESENT = "PRESENT";
    public static final String LEAVE = "LEAVE";

    private AttendanceStatus() {
    }
}
