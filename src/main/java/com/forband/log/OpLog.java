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

    /**
     * 取第几个入参当 `target_id`。默认 {@code -1} = 保持原启发式「第一个 Long 参数」；
     * 显式给了下标就取该下标（须是 Long），否则为 null。改动此值会改变审计行，谨慎。
     */
    int targetIdArg() default -1;
}
