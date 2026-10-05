package com.forband.member;

import com.forband.auth.Role;
import com.forband.common.SectionLookup;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;

/**
 * 名册次序（`docs/03` §3.4，排序口径由用户 2026-09-30 拍板）。
 * <p>
 * ⚠️ **次序不在 SQL 里排**：`member` 表上没有 `section.order_no`、现实现也没有 JOIN `section`——
 * 规格的"实现注意"给了这条路：「JOIN **或先查 `section` 做映射**」。所以调用方
 * **取全集 → Java 里排 → 自己切片**。
 * <p>
 * 次序：**在团 → 非在团（整块沉底）→ 荣誉**，组内再 指挥 → 团长 → 声部 `order_no`
 * → 声部长 → 团员 → id。⚠️ 声部那一档按 **`section.order_no`**（不是 id，两者顺序不保证一致）。
 */
@Component
public class RosterOrderer {

    private final SectionLookup sectionLookup;

    public RosterOrderer(SectionLookup sectionLookup) {
        this.sectionLookup = sectionLookup;
    }

    public Comparator<Member> order() {
        Map<Long, Integer> sectionOrder = sectionLookup.orderIndexMap();
        return Comparator
                .comparingInt(RosterOrderer::group)
                .thenComparingInt(RosterOrderer::role)
                .thenComparingInt((Member m) -> sectionRank(m, sectionOrder))
                .thenComparingInt((Member m) -> Role.SECTION_LEADER.name().equals(m.getRole()) ? 0 : 1)
                .thenComparingLong(Member::getId);
    }

    /** 0 = 在团且非荣誉；1 = 非在团（休团 / 毕业等）且非荣誉 —— **整块**沉到最后，不混在声部里；
     *  2 = 荣誉团员 —— 前端在它前面插一节「历届成员」。 */
    private static int group(Member m) {
        if (Role.HONORARY.name().equals(m.getRole())) {
            return 2;
        }
        return MemberStatus.ACTIVE.equals(m.getStatus()) ? 0 : 1;
    }

    private static int role(Member m) {
        if (Role.CONDUCTOR.name().equals(m.getRole())) {
            return 0;
        }
        return Role.LEADER.name().equals(m.getRole()) ? 1 : 2;
    }

    /** 没有声部的（指挥 / 团长 / 未分配）排在最后——它们本来就靠 `role` 站到了前面。 */
    private static int sectionRank(Member m, Map<Long, Integer> sectionOrder) {
        Integer rank = m.getSectionId() == null ? null : sectionOrder.get(m.getSectionId());
        return rank == null ? Integer.MAX_VALUE : rank;
    }
}
