package com.forband.piece;

import java.util.Set;

/**
 * 曲目四态（docs/06 §6.2）。
 * <p>
 * ⚠️ 规格**只定义了四个状态名，没有写流转规则**（谁能从哪个态到哪个态、能不能跳跃）——
 * 所以这里只做"取值必须在四个之内"的白名单校验，**不约束流转**。已在 `移交事项-2026-09-23.md` 里请规格补矩阵。
 */
public final class PieceStatus {

    public static final String CANDIDATE = "CANDIDATE";   // 想排但还没排
    public static final String REHEARSING = "REHEARSING"; // 当前正在排练
    public static final String READY = "READY";           // 能上台了
    public static final String PAUSED = "PAUSED";         // 练过但暂时不演了

    private static final Set<String> ALL = Set.of(CANDIDATE, REHEARSING, READY, PAUSED);

    private PieceStatus() {
    }

    public static boolean isValid(String status) {
        return status != null && ALL.contains(status);
    }

    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case CANDIDATE -> "备选";
            case REHEARSING -> "在练";
            case READY -> "已成熟";
            case PAUSED -> "已停排";
            default -> status;
        };
    }
}
