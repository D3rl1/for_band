package com.forband.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.common.PageResult;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final MemberMapper memberMapper;

    public OperationLogService(OperationLogMapper operationLogMapper, MemberMapper memberMapper) {
        this.operationLogMapper = operationLogMapper;
        this.memberMapper = memberMapper;
    }

    public PageResult<LogView> page(Long operatorId, String action, String targetType,
                                    Long targetId, LocalDate from, LocalDate to,
                                    int page, int size) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .eq(operatorId != null, OperationLog::getOperatorId, operatorId)
                .eq(action != null && !action.isBlank(), OperationLog::getAction, action)
                .eq(targetType != null && !targetType.isBlank(), OperationLog::getTargetType, targetType)
                .eq(targetId != null, OperationLog::getTargetId, targetId)
                .ge(from != null, OperationLog::getCreatedAt, from == null ? null : from.atStartOfDay())
                .lt(to != null, OperationLog::getCreatedAt, to == null ? null : to.plusDays(1).atStartOfDay())
                .orderByDesc(OperationLog::getId);
        Page<OperationLog> result = operationLogMapper.selectPage(Page.of(page, size), wrapper);
        List<Long> operatorIds = result.getRecords().stream()
                .map(OperationLog::getOperatorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = operatorIds.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(operatorIds).stream()
                        .collect(Collectors.toMap(Member::getId, Member::getName));
        List<LogView> views = result.getRecords().stream()
                .map(row -> new LogView(row.getId(), row.getOperatorId(),
                        names.get(row.getOperatorId()), row.getAction(), row.getTargetType(),
                        row.getTargetId(), row.getBefore(), row.getAfter(), row.getIp(),
                        row.getCreatedAt()))
                .toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }
}
