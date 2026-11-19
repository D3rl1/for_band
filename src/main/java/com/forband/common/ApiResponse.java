package com.forband.common;

/**
 * 统一响应壳，形状与错误码规则见 docs/10-接口规范与错误码.md：
 * 成功 {code:0, message:"ok", data:...}，失败 data 为 null。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
