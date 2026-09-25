package com.forband.member;

import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.member.dto.BirthdayDtos.BirthdayView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 今天的寿星（`docs/03` §3.3 登记、`docs/14` §14.6.4「生日通告」用）。
 * **登录即可**——`@RequireRole` 无参 = 任何已登录角色（含荣誉团员），照 `SectionController` 那个最简样板。
 * <p>
 * 单独一个控制器而不是塞进 `MemberController`：那个的 `@RequestMapping` 是 `/api/members`，
 * 挂 `/api/birthdays` 进去要写绝对路径，破坏"一个资源根一个控制器"的惯例（`MeController` 已是同包多控制器的先例）。
 */
@RestController
@RequestMapping("/api/birthdays")
@RequireRole
public class BirthdayController {

    private final BirthdayService birthdayService;

    public BirthdayController(BirthdayService birthdayService) {
        this.birthdayService = birthdayService;
    }

    @GetMapping("/today")
    public ApiResponse<List<BirthdayView>> today() {
        return ApiResponse.ok(birthdayService.today());
    }
}
