package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.member.dto.ProfileVisitDtos.ProfileVisitView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主页访客记录（`docs/03` §3.3 的两个端点）。`@RequireRole` 无参 = **登录即可**（含荣誉团员）。
 * <p>
 * ⚠️ **两个端点都只碰自己**：`hostId` 一律取 token 里的 `memberId`，**没有也不许有**任何入参能让调用方
 * 指定别人的 `hostId`——"管理员 / 团长也看不到别人的访客记录"就是靠这一点保证的（`docs/02` §2.3）。
 * <p>
 * 单独一个控制器而不是塞进 `MeProfileController`：那个的 `@RequestMapping` 是 `/api/me/profile`，
 * 挂 `/api/me/profile-visits` 进去要写绝对路径（同 `BirthdayController` 的理由）。
 */
@RestController
@RequestMapping("/api/me/profile-visits")
@RequireRole
public class ProfileVisitController {

    private final ProfileVisitService profileVisitService;

    public ProfileVisitController(ProfileVisitService profileVisitService) {
        this.profileVisitService = profileVisitService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProfileVisitView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser me) {
        return ApiResponse.ok(profileVisitService.list(me.memberId(), page, size));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(CurrentUser me) {
        profileVisitService.clear(me.memberId());
        return ApiResponse.ok();
    }
}
