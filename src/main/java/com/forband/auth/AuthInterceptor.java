package com.forband.auth;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证 + 粗粒度角色拦截（DECISIONS D5/D6）。只挂在 /api/** 上。
 * 默认拒绝：无 @RequireRole 注解的 API 一律 30001/403。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final MemberMapper memberMapper;
    private final PermissionService permissionService;

    public AuthInterceptor(TokenService tokenService, MemberMapper memberMapper,
                           PermissionService permissionService) {
        this.tokenService = tokenService;
        this.memberMapper = memberMapper;
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String rawToken = extractBearer(request);
        UserToken token = tokenService.resolveToken(rawToken);
        if (token == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        long memberId = token.getMemberId();
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (!permissionService.canLogin(member)) {
            tokenService.revokeAllFor(memberId);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        // 鉴权通过后写最后活跃（docs/01 §1.4：NULL 立即写，此后 >1 小时才写一次）
        tokenService.touchLastSeen(token);
        CurrentUser user = new CurrentUser(member.getId(), member.getName(),
                Role.valueOf(member.getRole()), member.getSectionId(),
                Boolean.TRUE.equals(member.getIsSystem()));
        CurrentUserHolder.set(user);

        RequireRole required = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
        if (!permissionService.hasAnyRole(user, required.value())) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
        return true;
    }

    // ThreadLocal 的清理交给 AccessLogFilter 的 finally（拦截器 afterCompletion 先于它执行）；
    // 非 /api 请求不会设置该 ThreadLocal，故无跨请求泄漏。

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return header.substring("Bearer ".length()).trim();
    }
}
