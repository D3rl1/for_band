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
        Long targetId = null;
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof Long l) {
                targetId = l;
                break;
            }
        }
        Object before = OpLogRecorder.drain();
        Object after = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        writer.write(operator, opLog.action(),
                opLog.targetType().isEmpty() ? null : opLog.targetType(),
                targetId, before, after);
    }
}
