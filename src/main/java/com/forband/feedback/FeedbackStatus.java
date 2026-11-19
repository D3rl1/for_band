package com.forband.feedback;

/** 反馈状态（docs/13 §13.3㉑）。 */
public final class FeedbackStatus {

    public static final String OPEN = "OPEN";
    public static final String HANDLED = "HANDLED";
    /** 已迁入问题讨论的旧反馈；只由迁移脚本写入，新反馈不再产生（docs/15 §15.3.2）。 */
    public static final String MIGRATED = "MIGRATED";

    private FeedbackStatus() {
    }
}
