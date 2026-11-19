package com.forband.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.fail(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), detail));
    }

    /** 请求体不是合法 JSON / 编码错误 → 10000/400，而不是 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(),
                        ErrorCode.BAD_REQUEST.getDefaultMessage()));
    }

    /**
     * 上传超过 servlet 的 multipart 上限（`spring.servlet.multipart.max-file-size`）→ 422，而不是冒成 500。
     * 执行顺序：**按类型的大小上限（20MB / 8MB）比 multipart 上限（25MB）小**，
     * 所以正常超限会先被业务校验拦成 10001；只有一次传了更大的东西才会落到这里。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), "上传文件过大"));
    }

    /**
     * 静态资源不存在（含浏览器每次必请求的 /favicon.ico）→ 404，不打 ERROR 堆栈（docs/14 §14.4）。
     * <p>
     * ⚠️ **`/api/**` 也落到这里**，那说明"这个路径上没有任何处理器"——最常见的原因是
     * **改了 Java 没重启**（Java 类是 JVM 启动时一次性加载的；`./refresh.sh` 只管静态资源）。
     * 所以这一支单独给一句能自解释的话 + 一条 WARN：否则它和"少了一张图"共用同一句
     * `10002 资源不存在`，看的人根本分不清是哪种（2026-09-28 真的这样白绕过一轮）。
     * ⚠️ **码与 HTTP 状态一个字不改**（`10002` / 404）：`docs/10` §10.5 那张表比的是
     * `ErrorCode` 里的**默认文案**，这里只动 `/api/**` 这一支的**运行时**文案。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException e) {
        String path = e.getResourcePath() == null ? "" : e.getResourcePath();
        if (path.equals("api") || path.startsWith("api/")) {
            log.warn("未映射的接口请求：{} /{} —— 这个进程里没有它的处理器（改了 Java 没重启？跑 ./restart.sh）",
                    e.getHttpMethod(), path);
            return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                    .body(ApiResponse.fail(ErrorCode.NOT_FOUND.getCode(),
                            "接口不存在（若刚更新过代码，服务需要重启）"));
        }
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND.getCode(),
                        ErrorCode.NOT_FOUND.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.getCode(),
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
