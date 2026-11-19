package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.member.dto.AvatarGrantDtos.GrantInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 「个人栏」的数据口子（docs/05 §5.6）：只取本人，不收 memberId。 */
@RestController
@RequestMapping("/api/me")
@RequireRole
public class MeController {

    private final MeService meService;
    private final AvatarGrantService avatarGrantService;

    public MeController(MeService meService, AvatarGrantService avatarGrantService) {
        this.meService = meService;
        this.avatarGrantService = avatarGrantService;
    }

    @GetMapping("/recent-activity")
    public ApiResponse<List<MeService.RecentActivityItem>> recentActivity(
            @RequestParam(defaultValue = "5") int limit, CurrentUser user) {
        return ApiResponse.ok(meService.recentActivity(user, limit));
    }

    /**
     * 本人当前有效的限定头像资格（#266）：前端拿它过滤头像网格——
     * 未获赠项**不出现**在可选列表里（不是"显示但锁住"），已撤回的也不算。
     * 每条带 `batch`，供选中区显示「纪念限定 · 批次名」。
     */
    @GetMapping("/avatar-grants")
    public ApiResponse<List<GrantInfo>> avatarGrants(CurrentUser user) {
        return ApiResponse.ok(avatarGrantService.activeGrants(user.memberId()));
    }
}
