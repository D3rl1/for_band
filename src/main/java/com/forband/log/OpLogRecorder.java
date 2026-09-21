package com.forband.log;

import java.util.Map;

/** 服务层在修改前调用 putBefore 存快照，切面取出后写入 operation_log.before 并清空。 */
public final class OpLogRecorder {

    private static final ThreadLocal<Map<String, Object>> SNAPSHOT = new ThreadLocal<>();

    private OpLogRecorder() {
    }

    public static void putBefore(Map<String, Object> snapshot) {
        SNAPSHOT.set(snapshot);
    }

    public static Map<String, Object> drain() {
        Map<String, Object> snapshot = SNAPSHOT.get();
        SNAPSHOT.remove();
        return snapshot;
    }
}
