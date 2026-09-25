package com.forband.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码 → HTTP 状态码映射，与 docs/10-接口规范与错误码.md 一一对应。
 * 401（未登录/token 失效）与 403（已登录但无权）严格区分。
 */
public enum ErrorCode {
    BAD_REQUEST(10000, HttpStatus.BAD_REQUEST, "请求格式有误"),
    VALIDATION_FAILED(10001, HttpStatus.UNPROCESSABLE_ENTITY, "参数校验失败"),
    NOT_FOUND(10002, HttpStatus.NOT_FOUND, "资源不存在"),
    INTERNAL_ERROR(10003, HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后再试"),

    WRONG_CREDENTIALS(20001, HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    TOKEN_INVALID(20002, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    ACCOUNT_DISABLED(20003, HttpStatus.FORBIDDEN, "账号已停用，请联系管理员"),
    ACCOUNT_DUPLICATE(20004, HttpStatus.CONFLICT, "该账号名已存在，请换一个"),
    WRONG_OLD_PASSWORD(20005, HttpStatus.UNPROCESSABLE_ENTITY, "旧密码不正确"),
    PASSWORD_TOO_SHORT(20006, HttpStatus.UNPROCESSABLE_ENTITY, "新密码至少 8 位"),

    NO_PERMISSION(30001, HttpStatus.FORBIDDEN, "你没有权限执行此操作"),
    NOT_SELF(30002, HttpStatus.FORBIDDEN, "只能操作自己的数据"),
    NOT_YOUR_SECTION(30003, HttpStatus.FORBIDDEN, "只能操作本声部的数据"),

    EVENT_CANCELED(40001, HttpStatus.CONFLICT, "本场活动已取消"),
    ALREADY_CHECKED_IN(40002, HttpStatus.CONFLICT, "已签到"),
    NOT_IN_WINDOW(40003, HttpStatus.CONFLICT, "当前不在签到时间内"),
    LEAVE_DUPLICATE(40004, HttpStatus.CONFLICT, "你已提交过请假申请"),
    EVENT_STARTED(40005, HttpStatus.CONFLICT, "活动已开始，无法撤回"),
    ALREADY_DECIDED(40006, HttpStatus.CONFLICT, "该申请已处理完毕"),
    SECTION_LEADER_EXISTS(40007, HttpStatus.UNPROCESSABLE_ENTITY, "该声部已有声部长"),
    ALREADY_ON_LEAVE(40008, HttpStatus.UNPROCESSABLE_ENTITY, "该团员此场已请假，或已提交请假申请"),

    FILE_TYPE_UNSUPPORTED(50001, HttpStatus.UNPROCESSABLE_ENTITY, "文件类型不支持"),
    FILE_TOO_LARGE(50002, HttpStatus.UNPROCESSABLE_ENTITY, "文件超过大小限制"),
    // 备位：录音 3 分钟上限是暂定项 T5，**服务端不解析音频**（docs/08 §8.3），只在前端拦 ⇒ 服务端永不抛它。
    FILE_DURATION_EXCEEDED(50003, HttpStatus.UNPROCESSABLE_ENTITY, "录音时长超出限制");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
