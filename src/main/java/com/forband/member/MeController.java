package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
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

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping("/recent-activity")
    public ApiResponse<List<MeService.RecentActivityItem>> recentActivity(
            @RequestParam(defaultValue = "5") int limit, CurrentUser user) {
        return ApiResponse.ok(meService.recentActivity(user, limit));
    }
}
