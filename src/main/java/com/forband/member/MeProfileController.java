package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.member.dto.MemberProfileView;
import com.forband.member.dto.MemberProfileView.ProfileRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人页（docs/03 §3.3）：`GET/PUT /api/me/profile`。**本人**——**绝不收 `memberId`**，
 * 参数里连这个口子都不开，从签名上就杜绝越权（`SECURITY.md` A2 的样板思路）。
 * 所有登录角色都能 **GET**（含荣誉团员——他们也有头像与简介）；
 * 但 **PUT 唯独荣誉团员不行**（`docs/02` §2.3 只读），闸门在 `MeProfileService.update()`。
 */
@RestController
@RequestMapping("/api/me/profile")
@RequireRole
public class MeProfileController {

    private final MeProfileService meProfileService;
    private final MemberService memberService;

    public MeProfileController(MeProfileService meProfileService, MemberService memberService) {
        this.meProfileService = meProfileService;
        this.memberService = memberService;
    }

    @GetMapping
    public ApiResponse<MemberProfileView> get(CurrentUser user) {
        return ApiResponse.ok(memberService.profileOf(user.memberId()));
    }

    @PutMapping
    public ApiResponse<MemberProfileView> update(@RequestBody @Valid ProfileRequest request,
                                                 CurrentUser user) {
        meProfileService.update(user, new MeProfileService.ProfileUpdate(
                request.avatar(), request.avatarFrame(), request.intro(), request.contact(),
                request.birthday(), Boolean.TRUE.equals(request.clearBirthday()),
                Boolean.TRUE.equals(request.clearAvatarFrame())));
        return ApiResponse.ok(memberService.profileOf(user.memberId()));
    }
}
