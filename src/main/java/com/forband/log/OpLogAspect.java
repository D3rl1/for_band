package com.forband.log;

import com.forband.auth.CurrentUser;
import com.forband.auth.CurrentUserHolder;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * operation_log 只增不改不删（docs/11 不变式 10）。留痕失败绝不影响业务本身。
 * targetId：取第一个 Long 参数（一般是路径变量 id）。
 */
@Aspect
@Component
public class OpLogAspect {

    private final OperationLogWriter writer;

    public OpLogAspect(OperationLogWriter writer) {
        this.writer = writer;
    }

    @AfterReturning("@annotation(opLog)")
    public void record(JoinPoint joinPoint, OpLog opLog) {
        CurrentUser operator = CurrentUserHolder.get();
        if (operator == null) {
            return;
        }
        Long targetId = resolveTargetId(joinPoint, opLog);
        Object before = OperationLogRecorder.drainBefore();
        // 服务层给了准确的"改后"就用它；没给才退回第一个入参（对 update(id, body) 会记成 id）
        Object after = OperationLogRecorder.drainAfter();
        if (after == null) {
            after = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        }
        writer.write(operator, opLog.action(),
                opLog.targetType().isEmpty() ? null : opLog.targetType(),
                targetId, before, after);
    }

    /** targetId：默认取第一个 Long 参数；注解显式给了 {@code targetIdArg} 则取该下标（须是 Long）。 */
    private static Long resolveTargetId(JoinPoint joinPoint, OpLog opLog) {
        Object[] args = joinPoint.getArgs();
        int index = opLog.targetIdArg();
        if (index >= 0) {
            return index < args.length && args[index] instanceof Long l ? l : null;
        }
        for (Object arg : args) {
            if (arg instanceof Long l) {
                return l;
            }
        }
        return null;
    }
}
