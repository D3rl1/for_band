package com.forband.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务留痕标记（docs/11）。操作人、IP、before 快照由切面自动取；
 * after = 请求参数的 JSON。绝不把含密码/令牌的对象放进参数序列化范围。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpLog {

    String action();

    String targetType() default "";
}
