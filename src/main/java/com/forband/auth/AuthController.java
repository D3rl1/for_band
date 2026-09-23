package com.forband.auth;

import com.forband.auth.dto.ChangePasswordRequest;
import com.forband.auth.dto.LoginRequest;
import com.forband.common.ApiResponse;
import com.forband.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequireRole
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResponse> login(@RequestBody @Valid LoginRequest request,
                                                        HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, ClientIp.of(httpRequest),
                httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(bearer(request));
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<AuthService.MeResponse> me(CurrentUser user) {
        return ApiResponse.ok(authService.me(user));
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(CurrentUser user,
                                            @RequestBody @Valid ChangePasswordRequest request) {
        authService.changePassword(user, request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    // ---------- 会话管理（docs/01 §1.3；荣誉团员的"登录设备"照常可用） ----------

    @GetMapping("/sessions")
    public ApiResponse<java.util.List<AuthService.SessionView>> sessions(CurrentUser user,
                                                                         HttpServletRequest request) {
        return ApiResponse.ok(authService.sessions(user, bearer(request)));
    }

    @PostMapping("/sessions/{id}/revoke")
    public ApiResponse<Void> revokeSession(@PathVariable long id, CurrentUser user) {
        authService.revokeSession(user, id);
        return ApiResponse.ok();
    }

    @PostMapping("/sessions/revoke-others")
    public ApiResponse<Void> revokeOthers(CurrentUser user, HttpServletRequest request) {
        authService.revokeOthers(user, bearer(request));
        return ApiResponse.ok();
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header == null ? null : header.substring("Bearer ".length()).trim();
    }
}
