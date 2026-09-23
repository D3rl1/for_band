package com.forband.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    /**
     * 提交反馈（docs/15 §15.4）：正文必填 1~1000。**不收 userAgent**——UA 由服务端从请求头取，
     * 不信客户端传；`pagePath` 是前端自动带的当前页。
     */
    public record CreateRequest(@NotBlank(message = "反馈正文不能为空") @Size(max = 1000) String content,
                                @Size(max = 255) String pagePath) {
    }

    /** 管理端标记已处理（docs/15 §15.4）：只标状态与备注。 */
    public record HandleRequest(String status, @Size(max = 255) String adminNote) {
    }

    /** 管理端列表视图。device 由服务端把 UA 粗解析出来（与"登录设备"同一套规则）。 */
    public record FeedbackView(long id, long memberId, String memberName, String sectionName,
                               String content, String pagePath, String device, String status,
                               Long handledBy, String handledByName, LocalDateTime handledAt,
                               String adminNote, LocalDateTime createdAt) {
    }
}
