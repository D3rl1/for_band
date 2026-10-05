package com.forband.log;

import java.util.Map;

/**
 * 服务层存"改前 / 改后"快照，切面取出后写入 operation_log 并清空（docs/11 §11.2 要求两个都给）。
 * <p>
 * 只传 {@code putBefore} 时，切面的 `after` 会退回"取第一个入参"——对 {@code update(id, body)}
 * 这种签名那会记成**路径变量 id**，等于没有 after。所以需要准确 after 的端点要在改完后调
 * {@link #putAfter}。
 */
public final class OperationLogRecorder {

    private static final ThreadLocal<Map<String, Object>> BEFORE = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> AFTER = new ThreadLocal<>();

    private OperationLogRecorder() {
    }

    public static void putBefore(Map<String, Object> snapshot) {
        BEFORE.set(snapshot);
    }

    public static void putAfter(Map<String, Object> snapshot) {
        AFTER.set(snapshot);
    }

    /** 两个快照都必须无条件取走并清空——线程是复用的，留着会串到下一条日志上。 */
    public static Map<String, Object> drainBefore() {
        Map<String, Object> snapshot = BEFORE.get();
        BEFORE.remove();
        return snapshot;
    }

    public static Map<String, Object> drainAfter() {
        Map<String, Object> snapshot = AFTER.get();
        AFTER.remove();
        return snapshot;
    }
}
