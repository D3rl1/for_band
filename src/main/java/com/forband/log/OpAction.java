package com.forband.log;

/**
 * `operation_log.action` 的串常量（`docs/11` §11.3）。
 * <p>
 * ⚠️ **值即数据**：这些串已经落进了历史日志行、也是前端 `logs.html` 的 `actionLabels` 的键——
 * **一个字符都不许改**（改了旧行就对不上、日志页会显示英文裸串）。收敛成常量只为**防拼错**：
 * 注解 `@OpLog(action = ...)` 与业务层 `OperationLogWriter.write(..., action, ...)` 都引用它。
 * <p>
 * ⚠️ **这里只放"应用会写"的**。由**服务器脚本**直插 `operation_log` 的那几个
 * （`BACKUP_RUN` / `BACKUP_FAILED` / `ADMIN_PASSWORD_EMERGENCY_RESET`，见 `docs/12` §12.5、
 * `docs/11` §11.3）**故意不放**——应用从不产出它们，放进来会让人以为有代码在用。
 * 脚本侧请**逐字**照抄规格给的串。
 */
public final class OpAction {

    private OpAction() {
    }

    // ---------- 账号（AdminMemberController） ----------
    public static final String ACCOUNT_CREATE = "ACCOUNT_CREATE";
    public static final String ACCOUNT_UPDATE = "ACCOUNT_UPDATE";
    public static final String ACCOUNT_RESET_PASSWORD = "ACCOUNT_RESET_PASSWORD";
    public static final String ACCOUNT_REVOKE_SESSIONS = "ACCOUNT_REVOKE_SESSIONS";
    public static final String ACCOUNT_EXPORT = "ACCOUNT_EXPORT";

    // ---------- 活动（EventController） ----------
    public static final String EVENT_CREATE = "EVENT_CREATE";
    public static final String EVENT_BATCH_CREATE = "EVENT_BATCH_CREATE";
    public static final String EVENT_UPDATE = "EVENT_UPDATE";
    public static final String EVENT_CANCEL = "EVENT_CANCEL";
    public static final String EVENT_SET_PIECES = "EVENT_SET_PIECES";

    // ---------- 考勤（AttendanceService 手写；注解不经过这里） ----------
    public static final String ATTENDANCE_MARK_FOR = "ATTENDANCE_MARK_FOR";
    public static final String ATTENDANCE_SET_STATUS = "ATTENDANCE_SET_STATUS";
    public static final String ATTENDANCE_OVERRIDE_BY_LEAVE = "ATTENDANCE_OVERRIDE_BY_LEAVE";

    // ---------- 请假（LeaveController / LeaveService） ----------
    public static final String LEAVE_APPLY = "LEAVE_APPLY";
    public static final String LEAVE_DECIDE = "LEAVE_DECIDE";
    public static final String LEAVE_CANCEL = "LEAVE_CANCEL";

    // ---------- 曲目（PieceController） ----------
    public static final String PIECE_UPDATE = "PIECE_UPDATE";
    public static final String PIECE_ATTACHMENT_DELETE = "PIECE_ATTACHMENT_DELETE";

    // ---------- 演出档案（ConcertController） ----------
    public static final String CONCERT_UPDATE = "CONCERT_UPDATE";
    public static final String CONCERT_PHOTO_DELETE = "CONCERT_PHOTO_DELETE";

    // ---------- 录音（RecordingController） ----------
    public static final String RECORDING_DELETE = "RECORDING_DELETE";

    // ---------- 声部长留言（SectionNoteController；写与改**共用**一个串） ----------
    public static final String SECTION_NOTE_WRITE = "SECTION_NOTE_WRITE";

    // ---------- 专场策展（AdminShowcaseController；串名由实现方定，见交接单 §三） ----------
    public static final String SHOWCASE_CREATE = "SHOWCASE_CREATE";
    public static final String SHOWCASE_TOGGLE = "SHOWCASE_TOGGLE";
    public static final String SHOWCASE_PHOTO_ADD = "SHOWCASE_PHOTO_ADD";
    public static final String SHOWCASE_PHOTO_ORDER = "SHOWCASE_PHOTO_ORDER";
    public static final String SHOWCASE_PHOTO_NOTE = "SHOWCASE_PHOTO_NOTE";
    public static final String SHOWCASE_PHOTO_DELETE = "SHOWCASE_PHOTO_DELETE";
}
