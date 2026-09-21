package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.log.OpLog;
import com.forband.member.dto.MemberCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.member.dto.MemberView;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账号操作全部收归管理员（暂定项 T8，docs/00）。 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(Role.ADMIN)
public class AdminMemberController {

    private final MemberService memberService;

    public AdminMemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping("/members")
    @OpLog(action = "ACCOUNT_CREATE", targetType = "member")
    public ApiResponse<MemberCreateResponse> create(@RequestBody @Valid MemberCreateRequest request,
                                                    CurrentUser operator) {
        return ApiResponse.ok(memberService.create(request, operator));
    }

    @PutMapping("/members/{id}")
    @OpLog(action = "ACCOUNT_UPDATE", targetType = "member")
    public ApiResponse<MemberView> update(@PathVariable long id,
                                          @RequestBody @Valid MemberUpdateRequest request,
                                          CurrentUser operator) {
        return ApiResponse.ok(memberService.update(id, request, operator));
    }

    @PostMapping("/members/{id}/reset-password")
    @OpLog(action = "ACCOUNT_RESET_PASSWORD", targetType = "member")
    public ApiResponse<String> resetPassword(@PathVariable long id) {
        return ApiResponse.ok(memberService.resetPassword(id));
    }

    @GetMapping("/accounts/export")
    @OpLog(action = "ACCOUNT_EXPORT")
    public ResponseEntity<String> exportAccounts() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=accounts.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(memberService.exportAccountsCsv());
    }
}
