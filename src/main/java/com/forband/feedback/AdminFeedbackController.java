package com.forband.feedback;

import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.feedback.dto.FeedbackDtos.FeedbackView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史反馈只读归档（docs/15 §15.3.2）：**仅管理员**。
 * 旧的 `PUT /api/admin/feedback/{id}` 已随 #227 退役；问题状态改在公开栏详情页。
 */
@RestController
@RequestMapping("/api/admin/feedback")
@RequireRole(Role.ADMIN)
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** `?status=` 可筛 OPEN / HANDLED / MIGRATED；不传则只看 **OPEN**。 */
    @GetMapping
    public ApiResponse<PageResult<FeedbackView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(feedbackService.page(status, page, size));
    }
}
