package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.member.dto.MemberView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@RequireRole
public class MemberController {

    private final MemberService memberService;
    private final ProfileVisitService profileVisitService;

    public MemberController(MemberService memberService, ProfileVisitService profileVisitService) {
        this.memberService = memberService;
        this.profileVisitService = profileVisitService;
    }

    /** 名册（排序口径见 `docs/03` §3.4）。
     *  ⚠️ `includeLeft`：**默认 false** = 名册**不显示退团的人**（用户 2026-09-30 拍板）。
     *  显式传 `status=LEFT` 仍然查得到；管理端名册与演出参演名单要看到退团的人，**显式带 `includeLeft=true`**。 */
    @GetMapping
    public ApiResponse<PageResult<MemberView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(defaultValue = "false") boolean includeLeft,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser viewer) {
        return ApiResponse.ok(memberService.list(status, sectionId, includeLeft, page, size, viewer));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberView> get(@PathVariable long id, CurrentUser viewer) {
        MemberView view = memberService.get(id, viewer);
        // 看别人的主页会留下访客记录（docs/14 §14.6.4；本人看自己不记 —— 服务里也兜了一道）。
        // ⚠️ 放在控制器、不放进 MemberService.get()：那样将来别的调用方（比如管理端的编辑表单）
        // 也会顺手记一笔；"看主页"这件事只属于这个读口的这一条路径。
        // 放在 get() 之后：查不到 / is_system（404）的那次不算"看过"。
        profileVisitService.record(viewer.memberId(), id);
        return ApiResponse.ok(view);
    }
}
