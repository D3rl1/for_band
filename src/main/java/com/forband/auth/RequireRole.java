package com.forband.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色要求。默认拒绝：/api/** 下的接口方法（含类级）没有此注解一律 30001/403。
 * value 为空数组 = 任何已登录角色。声部维度的横向校验（如"本声部"）不走此注解，
 * 由服务层调用 PermissionService 判定。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role[] value() default {};
}
