package com.forband.issue;

import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.issue.dto.IssueDtos.ActivityView;
import com.forband.issue.dto.IssueDtos.CommentRequest;
import com.forband.issue.dto.IssueDtos.CreateRequest;
import com.forband.issue.dto.IssueDtos.IssueDetail;
import com.forband.issue.dto.IssueDtos.IssueListItem;
import com.forband.issue.dto.IssueDtos.ReactionView;
import com.forband.issue.dto.IssueDtos.StatusRequest;
import com.forband.issue.dto.IssueDtos.UpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问题讨论公开栏（docs/15 §15.3.1）。
 *
 * <p>类级空 {@code @RequireRole} = 任何登录角色（含荣誉团员，只读）；
 * 写操作在方法里再用 {@link PermissionService#checkNotHonorary} 排除荣誉团员。
 * 改状态是唯一的方法级 {@code @RequireRole(Role.ADMIN)}——团长也不行。
 */
@RestController
@RequestMapping("/api/issues")
@RequireRole
public class IssueController {

    private final IssueService issueService;
    private final PermissionService permissionService;

    public IssueController(IssueService issueService, PermissionService permissionService) {
        this.issueService = issueService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResponse<PageResult<IssueListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser me) {
        return ApiResponse.ok(issueService.list(status, mine, page, size, me));
    }

    @GetMapping("/activity")
    public ApiResponse<ActivityView> activity() {
        return ApiResponse.ok(issueService.activity());
    }

    @GetMapping("/{id}")
    public ApiResponse<IssueDetail> detail(@PathVariable long id, CurrentUser me) {
        return ApiResponse.ok(issueService.detail(id, me));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CreateRequest request,
                                    HttpServletRequest httpRequest, CurrentUser me) {
        permissionService.checkNotHonorary(me);
        // UA 只从请求头取，不信客户端传（docs/15 §15.3.1）
        return ApiResponse.ok(issueService.create(request, httpRequest.getHeader("User-Agent"), me));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody UpdateRequest request,
                                    CurrentUser me) {
        permissionService.checkNotHonorary(me);
        issueService.update(id, request, me);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<Long> comment(@PathVariable long id, @RequestBody CommentRequest request,
                                     CurrentUser me) {
        permissionService.checkNotHonorary(me);
        return ApiResponse.ok(issueService.comment(id, request, me));
    }

    @PutMapping("/{id}/comments/{commentId}")
    public ApiResponse<Void> updateComment(@PathVariable long id, @PathVariable long commentId,
                                           @RequestBody CommentRequest request, CurrentUser me) {
        permissionService.checkNotHonorary(me);
        issueService.updateComment(id, commentId, request, me);
        return ApiResponse.ok();
    }

    /** 仅管理员；同状态成功空操作；REJECTED 必填理由。 */
    @PutMapping("/{id}/status")
    @RequireRole(Role.ADMIN)
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusRequest request,
                                          CurrentUser me) {
        issueService.changeStatus(id, request, me);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reaction")
    public ApiResponse<ReactionView> react(@PathVariable long id, CurrentUser me) {
        permissionService.checkNotHonorary(me);
        return ApiResponse.ok(issueService.react(id, me));
    }

    @DeleteMapping("/{id}/reaction")
    public ApiResponse<ReactionView> unreact(@PathVariable long id, CurrentUser me) {
        permissionService.checkNotHonorary(me);
        return ApiResponse.ok(issueService.unreact(id, me));
    }
}
