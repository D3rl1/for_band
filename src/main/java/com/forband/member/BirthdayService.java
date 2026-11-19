package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.member.dto.BirthdayDtos.BirthdayView;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 「今天的寿星」（`docs/14` §14.6.4「生日通告」+ `docs/03` §3.3）。
 * <p>
 * 口径（规格写死的三条）：
 * <ul>
 *   <li>寿星 = ACTIVE / PAUSED 且排除 is_system 管理员；已离团的人不通告；</li>
 *   <li>**2 月 29 日生日的人在平年按 2-28 通告**（否则他们四年才有一次）；</li>
 *   <li>没有寿星 → 返回**空列表**（前端据此整个环节跳过，不弹、不占位、不出空层）。</li>
 * </ul>
 * ⚠️ 这里**不回 `birthday`**（见 {@link BirthdayView}）。
 */
@Service
public class BirthdayService {

    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;

    public BirthdayService(MemberMapper memberMapper, SectionMapper sectionMapper) {
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
    }

    /** 按**服务器当天**算。日期前后端都用"本地日期"，别用 UTC（否则跨日会错一天）。 */
    public List<BirthdayView> today() {
        LocalDate today = LocalDate.now();
        // 生日资格独立于应到资格：休团保留通告，不能复用考勤筛选。
        List<Member> hit = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                        .eq(Member::getIsSystem, false)
                        .in(Member::getStatus, MemberStatus.ACTIVE, MemberStatus.PAUSED)
                        .isNotNull(Member::getBirthday)
                        .orderByAsc(Member::getId))
                .stream()
                .filter(m -> isBirthdayToday(m.getBirthday(), today))
                .toList();

        Map<Long, String> sectionNames = sectionNamesOf(hit);
        return hit.stream()
                .map(m -> new BirthdayView(m.getId(), m.getName(), sectionNames.get(m.getSectionId()),
                        m.getInstrument(), m.getRole()))
                .toList();
    }

    /**
     * 生日是不是"今天"。
     * <p>
     * ⚠️ **抽成纯函数是为了能稳定地单测**：「2-29 出生的人在平年按 2-28 通告」这条
     * **只在特定运行日才验证得到**，靠集成测试永远测不稳（跑测试那天不能换）——
     * 见 `BirthdayRuleTests`。
     * <p>
     * 规则：月份相同，且（日相同 —— 或者 生日是 2-29、而今天是**平年**的 2-28）。
     * 闰年判定交给 `LocalDate.isLeapYear()`，不自己算。
     */
    static boolean isBirthdayToday(LocalDate birthday, LocalDate today) {
        if (birthday == null || today == null || birthday.getMonthValue() != today.getMonthValue()) {
            return false;
        }
        if (birthday.getDayOfMonth() == today.getDayOfMonth()) {
            return true;
        }
        return birthday.getMonthValue() == 2
                && birthday.getDayOfMonth() == 29
                && today.getDayOfMonth() == 28
                && !today.isLeapYear();
    }

    /** 批量取声部名（照 `MemberService.sectionNamesOf` 的写法，别用单条版——那会 N+1）。 */
    private Map<Long, String> sectionNamesOf(List<Member> members) {
        List<Long> distinct = members.stream()
                .map(Member::getSectionId).filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Section::getName, (a, b) -> a));
    }
}
