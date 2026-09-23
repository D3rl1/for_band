package com.forband.feedback;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.feedback.dto.FeedbackDtos.FeedbackView;
import com.forband.feedback.dto.FeedbackDtos.HandleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈管理端（docs/15 §15.3）：**仅管理员**。⚠️ 刻意**不加 `@OpLog`**——
 * `docs/11` §11.3 明写「处理反馈」不进操作日志（表自带 `handled_by`/`handled_at` 就是留痕）。
 */
@RestController
@RequestMapping("/api/admin/feedback")
@RequireRole(Role.ADMIN)
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** `?status=` 可筛 OPEN / HANDLED；不传则只看 **OPEN**（已处理的不堆在默认视图）。 */
    @GetMapping
    public ApiResponse<PageResult<FeedbackView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(feedbackService.page(status, page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> handle(@PathVariable long id,
                                    @RequestBody @Valid HandleRequest request,
                                    CurrentUser operator) {
        feedbackService.handle(id, request, operator);
        return ApiResponse.ok();
    }
}
