package com.forband.section.dto;

/**
 * 声部接口的对外形状（`docs/03` §3.4）。
 * ⚠️ **不外泄 Entity**：以前 `SectionController` 直接返回 `Section` 实体，等于把表结构
 * （列名、`order_no` 这类实现细节）钉进了接口契约——改表就会牵动前端。收成这一层后，
 * 对外形状与存储解耦。
 */
public final class SectionDtos {

    private SectionDtos() {
    }

    public record SectionView(Long id, String name, Integer orderNo) {
    }
}
