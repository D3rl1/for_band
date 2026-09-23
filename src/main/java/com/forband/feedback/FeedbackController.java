package com.forband.feedback;

import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.feedback.dto.FeedbackDtos.CreateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提交反馈（docs/15 §15.3）：**所有登录角色，唯一例外是荣誉团员**（docs/02 §2.3 的防腐条款）。
 * "排除某个角色" `@RequireRole` 表达不了（它只能列举允许的角色），所以走服务层判定。
 */
@RestController
@RequestMapping("/api/feedback")
@RequireRole
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final PermissionService permissionService;

    public FeedbackController(FeedbackService feedbackService, PermissionService permissionService) {
        this.feedbackService = feedbackService;
        this.permissionService = permissionService;
    }

    @PostMapping
    public ApiResponse<Void> create(@RequestBody @Valid CreateRequest request,
                                    HttpServletRequest httpRequest, CurrentUser submitter) {
        permissionService.checkNotHonorary(submitter);
        // UA **只从请求头取**，不信客户端传（docs/15 §15.3）
        feedbackService.create(request, httpRequest.getHeader("User-Agent"), submitter);
        return ApiResponse.ok();
    }
}
