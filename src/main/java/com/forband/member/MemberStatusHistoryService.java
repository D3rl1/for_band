package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 状态变更随 MemberService 的事务写入；活动日回溯不依赖 JVM 默认时区。 */
@Service
public class MemberStatusHistoryService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MemberStatusHistoryMapper mapper;

    public MemberStatusHistoryService(MemberStatusHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public static LocalDate effectiveDate(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public void record(long memberId, String before, String after, LocalDate date, long operatorId) {
        MemberStatusHistory history = new MemberStatusHistory();
        history.setMemberId(memberId);
        history.setBeforeStatus(before);
        history.setAfterStatus(after);
        history.setEffectiveDate(date);
        history.setChangedAt(LocalDateTime.now(ZONE));
        history.setOperatorId(operatorId);
        mapper.insert(history);
    }

    public String statusAt(Member member, LocalDate date) {
        MemberStatusHistory row = mapper.selectOne(new LambdaQueryWrapper<MemberStatusHistory>()
                .eq(MemberStatusHistory::getMemberId, member.getId())
                .le(MemberStatusHistory::getEffectiveDate, date)
                .orderByDesc(MemberStatusHistory::getEffectiveDate, MemberStatusHistory::getId)
                .last("LIMIT 1"));
        if (row != null) return row.getAfterStatus();
        MemberStatusHistory first = mapper.selectOne(new LambdaQueryWrapper<MemberStatusHistory>()
                .eq(MemberStatusHistory::getMemberId, member.getId())
                .orderByAsc(MemberStatusHistory::getEffectiveDate, MemberStatusHistory::getId)
                .last("LIMIT 1"));
        // 旧区间未知：冻结切换前旧口径，不能让当前休团把全部旧历史清零。
        // 此回退不是已核实的历史日期，部署回执必须列明旧区间待核实。
        return first == null ? member.getStatus() : first.getBeforeStatus();
    }
}
