package com.forband.member;

/** 团员状态四态（docs/03）。数据库存字符串常量，不用枚举映射——SQL 保持显式可见。 */
public final class MemberStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String LEFT = "LEFT";
    public static final String GRADUATED = "GRADUATED";

    private MemberStatus() {
    }
}
