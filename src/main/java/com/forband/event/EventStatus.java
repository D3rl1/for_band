package com.forband.event;

/** 取消 ≠ 删除：置 CANCELED，签到关闭、统计整场排除（docs/04）。 */
public final class EventStatus {

    public static final String NORMAL = "NORMAL";
    public static final String CANCELED = "CANCELED";

    private EventStatus() {
    }
}
