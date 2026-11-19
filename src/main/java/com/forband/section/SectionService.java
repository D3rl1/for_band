package com.forband.section;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.forband.section.dto.SectionDtos.SectionView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 声部（`docs/03` §3.4）：**只读**，没有写口子——声部由 `DataSeeder` / `sql/schema.sql` 维护，
 * 应用不提供增删改（规格如此）。
 * <p>
 * ⚠️ 这是本域唯一的读口子，但**不是全站唯一的声部读口子**：`common/SectionLookup` 还提供
 * 「id → 名」的批量映射（供名册 / 考勤 / 请假组装视图）。两者分工见交接单「模块标准形」。
 */
@Service
public class SectionService {

    private final SectionMapper sectionMapper;

    public SectionService(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    /** 按 `order_no` 升序（前端筛选下拉、建号弹窗都按这个次序显示）。 */
    public List<SectionView> list() {
        return sectionMapper.selectList(new QueryWrapper<Section>().orderByAsc("order_no")).stream()
                .map(s -> new SectionView(s.getId(), s.getName(), s.getOrderNo()))
                .toList();
    }
}
