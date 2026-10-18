package com.forband.attendance;

/**
 * 考勤四态（DECISIONS D9 修订 / D33）：出勤 / 遗憾离场 / 请假 / 缺勤。
 * <p>
 * ⚠️ 缺勤通常**不落库**，靠"应到名单里没有记录"推导；ABSENT 行只可能来自人工改判已有记录，
 * 任何路径都不得为无记录者补一行。PARTIAL 计入出勤：统计口径里 present = PRESENT + PARTIAL，
 * 但不单列、不加第五个统计数。
 */
public final class AttendanceStatus {

    public static final String PRESENT = "PRESENT";
    public static final String PARTIAL = "PARTIAL";
    public static final String LEAVE = "LEAVE";
    public static final String ABSENT = "ABSENT";

    /** 是否计入"出勤"（PARTIAL 遗憾离场也到场了，按出勤算）。 */
    public static boolean isPresent(String status) {
        return PRESENT.equals(status) || PARTIAL.equals(status);
    }

    private AttendanceStatus() {
    }
}
