package com.forband.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 声部查询的唯一入口：原先 {@code sectionNameOf} 在 member / attendance / leave / auth 四个 service
 * 各写一遍，这里收成一处。
 */
@Component
public class SectionLookup {

    private final SectionMapper sectionMapper;

    public SectionLookup(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    /** 单条：无 id 或查不到都返回 null。 */
    public String sectionNameOf(Long sectionId) {
        if (sectionId == null) {
            return null;
        }
        Section section = sectionMapper.selectById(sectionId);
        return section == null ? null : section.getName();
    }

    public Map<Long, String> sectionNamesOf(List<Long> sectionIds) {
        List<Long> distinct = sectionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Section::getName, (a, b) -> a));
    }

    /**
     * 单条版。⚠️ **不能用 `List.of(声部 id)`**：团长 / 指挥 / 未分配声部的团员 `section_id` 是 null，
     * 而 `List.of` 不收 null、会当场 NPE（看这些人的主页 = 500）。
     */
    public Map<Long, String> sectionNamesOfOne(Long sectionId) {
        return sectionId == null ? Map.of() : sectionNamesOf(List.of(sectionId));
    }

    public void requireSection(Long sectionId) {
        if (sectionMapper.selectById(sectionId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "声部不存在");
        }
    }

    /** 声部 id → 按 `order_no` 升序的名次（0 起）。名册排序用。 */
    public Map<Long, Integer> orderIndexMap() {
        Map<Long, Integer> order = new HashMap<>();
        int n = 0;
        for (Section s : sectionMapper.selectList(new QueryWrapper<Section>().orderByAsc("order_no"))) {
            order.put(s.getId(), n++);
        }
        return order;
    }
}
