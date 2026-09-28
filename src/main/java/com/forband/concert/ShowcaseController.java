package com.forband.concert;

import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.concert.dto.ShowcaseDtos.TimelineNode;
import com.forband.concert.dto.ShowcaseDtos.YearPhoto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我们的专场」专页的**展示侧**（`docs/17` §17.3）：登录即可，**含荣誉团员**。
 * ⚠️ 这不是给荣誉团员新开的口子——它就是 `02` §2.3「看演出档案（…照片…）」那一格的同一个权限，
 * 专页只是换一种呈现方式（`docs/17` §17.4「可见性」）。
 */
@RestController
@RequestMapping("/api/showcase")
@RequireRole
public class ShowcaseController {

    private final ShowcaseService showcaseService;

    public ShowcaseController(ShowcaseService showcaseService) {
        this.showcaseService = showcaseService;
    }

    /** 时间线：按年份聚合（一个节点 = 一年；该年的图 = 该年所有专场的策展图串起来）。 */
    @GetMapping("/timeline")
    public ApiResponse<List<TimelineNode>> timeline() {
        return ApiResponse.ok(showcaseService.timeline());
    }

    /** 某一年的全部策展图（按「演出日期 → `sort_no`」排）。 */
    @GetMapping("/years/{year}")
    public ApiResponse<List<YearPhoto>> yearPhotos(@PathVariable int year) {
        return ApiResponse.ok(showcaseService.yearPhotos(year));
    }
}
