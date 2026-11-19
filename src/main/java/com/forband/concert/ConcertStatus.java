package com.forband.concert;

import java.util.Set;

/** 演出三态（docs/13 §13.3⑫）。 */
public final class ConcertStatus {

    public static final String PLANNED = "PLANNED";
    public static final String DONE = "DONE";
    public static final String CANCELED = "CANCELED";

    private static final Set<String> ALL = Set.of(PLANNED, DONE, CANCELED);

    private ConcertStatus() {
    }

    public static boolean isValid(String status) {
        return status != null && ALL.contains(status);
    }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case PLANNED -> "计划中";
            case DONE -> "已完成";
            case CANCELED -> "已取消";
            default -> status;
        };
    }
}
