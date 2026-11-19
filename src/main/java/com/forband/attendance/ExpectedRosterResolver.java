package com.forband.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.Role;
import com.forband.auth.PermissionService;
import com.forband.event.Event;
import com.forband.event.EventKind;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberRoleHistory;
import com.forband.member.MemberRoleHistoryMapper;
import com.forband.member.MemberStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 应到名单与声部回溯（`docs/13` 不变式 3/7/14）。
 * <p>
 * 不变式 3/7/14：应到 = 活动日回溯为在团、非系统账号、非指挥、入退团日期覆盖活动当天。
 * 指挥不参与考勤统计（不进应到名单 ⇒ 不能签到、不进分母），但查看权照旧（`docs/02` §2.3）。
 */
@Component
public class ExpectedRosterResolver {

    /** 「应到却没签」——⚠️ **通常不是 `attendance` 表里的值**：没记录时由这里跟"应到名单"推导。
     *  表里也允许存在 ABSENT（管理员人工改判已有记录，DECISIONS D33），此时直接采用行里的值。 */
    static final String ABSENT = "ABSENT";

    private final MemberMapper memberMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final PermissionService permissionService;

    public ExpectedRosterResolver(MemberMapper memberMapper, MemberRoleHistoryMapper historyMapper,
                                  PermissionService permissionService) {
        this.memberMapper = memberMapper;
        this.historyMapper = historyMapper;
        this.permissionService = permissionService;
    }

    /**
     * 应到名单。
     * <p>
     * ⚠️ **声部匹配不能在 SQL 里按 `member.section_id` 预筛**——那只有当前值，
     * 会把换过声部的人直接筛掉，{@link #isExpected} 里的回溯根本没机会跑。
     * 所以这里只按"当前在团或休团 / 非系统 / 入退团日期"粗筛，状态按活动日回溯，
     * 声部判定全部交给 {@link #sectionAt}。
     * <p>
     * 代价：声部课要取在团与休团成员再过滤。40 人量级可忽略（D4）。
     */
    public List<Member> expectedMembers(Event event) {
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .eq(Member::getIsSystem, false)
                .in(Member::getStatus, MemberStatus.ACTIVE, MemberStatus.PAUSED)
                .ne(Member::getRole, Role.CONDUCTOR.name())
                .and(w -> w.isNull(Member::getJoinedAt).or().le(Member::getJoinedAt, event.getDate()))
                .and(w -> w.isNull(Member::getLeftAt).or().ge(Member::getLeftAt, event.getDate()))
                .orderByAsc(Member::getId);
        return memberMapper.selectList(wrapper).stream()
                .filter(m -> isExpected(event, m))
                .toList();
    }

    public boolean isExpected(Event event, Member member) {
        if (!permissionService.isAttendeeOn(member, event.getDate())) {
            return false;
        }
        if (EventKind.isSectionClass(event.getKind())
                && !Objects.equals(sectionAt(event.getDate(), member), event.getSectionId())) {
            return false;
        }
        LocalDate date = event.getDate();
        if (member.getJoinedAt() != null && member.getJoinedAt().isAfter(date)) {
            return false;
        }
        return member.getLeftAt() == null || !member.getLeftAt().isBefore(date);
    }

    /** 非应到不计；应到者采用既有四态，无记录则缺勤。原始记录不删除。 */
    public String mineAt(Event event, Member me, String recordStatus) {
        if (me == null || !isExpected(event, me)) {
            return null;
        }
        if (recordStatus != null) {
            return recordStatus;
        }
        return ABSENT;
    }

    /**
     * 某人在**某一天**属于哪个声部（docs/13 不变式 7）。
     * <p>
     * 不能直接用 {@code member.sectionId} —— 那只有**当前值**。团员可以换声部，
     * 若按当前声部算历史声部课，**旧声部少算一人、新声部多算一人，两边都错**。
     * 所以按 {@code event.date} 在 {@code member_role_history} 里回溯当时那一段
     * （不变式 8 要求变更角色时必须写历史，所以这里查得到）。
     * <p>
     * 查不到历史记录时退回当前声部：说明这人从没换过声部，"当时"就等于"现在"。
     * <p>
     * 注：这里是逐条查（40 人 × 几十场量级完全无压力，见 D4）。真到几千人再谈批量预取。
     */
    private Long sectionAt(LocalDate date, Member member) {
        MemberRoleHistory h = historyMapper.selectOne(
                new LambdaQueryWrapper<MemberRoleHistory>()
                        .eq(MemberRoleHistory::getMemberId, member.getId())
                        .le(MemberRoleHistory::getFromDate, date)
                        .and(w -> w.isNull(MemberRoleHistory::getToDate)
                                .or().ge(MemberRoleHistory::getToDate, date))
                        .orderByDesc(MemberRoleHistory::getFromDate)
                        .last("LIMIT 1"));
        return h == null ? member.getSectionId() : h.getSectionId();
    }
}
