package com.forband.auth;

/** 当前请求的登录身份，由 AuthInterceptor 解析 token 后放入 ThreadLocal。 */
public record CurrentUser(long memberId, String name, Role role, Long sectionId, boolean system) {
}
