package com.forband.auth;

import com.forband.auth.dto.ChangePasswordRequest;
import com.forband.auth.dto.LoginRequest;
import com.forband.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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
        return ApiResponse.ok(authService.login(request, httpRequest.getRemoteAddr()));
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

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header == null ? null : header.substring("Bearer ".length()).trim();
    }
}
