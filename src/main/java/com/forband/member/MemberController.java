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

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public ApiResponse<PageResult<MemberView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser viewer) {
        return ApiResponse.ok(memberService.list(status, sectionId, page, size, viewer));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberView> get(@PathVariable long id, CurrentUser viewer) {
        return ApiResponse.ok(memberService.get(id, viewer));
    }
}
