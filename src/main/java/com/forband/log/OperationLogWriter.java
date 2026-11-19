package com.forband.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forband.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 留痕写入的公共入口：AOP 切面与业务层手动留痕（如代签）共用。只增不改不删。 */
@Component
public class OperationLogWriter {

    private static final Logger log = LoggerFactory.getLogger(OperationLogWriter.class);

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogWriter(OperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    public void write(CurrentUser operator, String action, String targetType, Long targetId,
                      Object before, Object after) {
        try {
            OperationLog row = new OperationLog();
            row.setOperatorId(operator.memberId());
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setBefore(toJson(before));
            row.setAfter(toJson(after));
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            row.setIp(attrs == null ? null : attrs.getRequest().getRemoteAddr());
            operationLogMapper.insert(row);
        } catch (Exception e) {
            log.error("写入操作留痕失败 action={}", action, e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("留痕序列化失败", e);
            return null;
        }
    }
}
