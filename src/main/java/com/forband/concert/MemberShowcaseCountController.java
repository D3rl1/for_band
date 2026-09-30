package com.forband.concert;

import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.concert.dto.ShowcaseDtos.MemberShowcaseCount;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人页那一个数：**参演过多少场专场**（`docs/14` §14.6.4「个人页」第 6 条，`交接单 #117`）。
 * <p>
 * ⚠️ **单独一个控制器**（而不是把方法塞进 `member` 包的 `MemberController`）：这个查询住在 `concert` 包，
 * 而 `concert` **已经依赖** `member`（取参演人姓名）⇒ 反过来再依赖一次就成环了。
 * 路径仍挂在 `/api/members/{id}/...` 上 —— 它描述的是"某个团员的属性"，URL 结构照旧。
 * <p>
 * ⚠️ **登录即可、任何人都能看**（团员主页是给别人看的）。**0 也照常回 0**。
 */
@RestController
@RequestMapping("/api/members")
@RequireRole
public class MemberShowcaseCountController {

    private final ShowcaseService showcaseService;

    public MemberShowcaseCountController(ShowcaseService showcaseService) {
        this.showcaseService = showcaseService;
    }

    @GetMapping("/{id}/showcase-count")
    public ApiResponse<MemberShowcaseCount> count(@PathVariable long id) {
        return ApiResponse.ok(new MemberShowcaseCount(showcaseService.memberShowcaseCount(id)));
    }
}
