package com.forband.section;

import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 声部读口子的**形状守卫**（`docs/03` §3.4）。
 * <p>
 * `GET /api/sections` 原先直接返回 `Section` 实体，等于把表结构钉进接口契约；
 * 2026-10-06 收成 `SectionDtos.SectionView`（`section/SectionService`）。
 * 这条用例把"只回 id / name / orderNo 三个键"钉死——**给 `section` 表加列不会悄悄漏进响应**，
 * 一旦有人把 Entity 又端出来（或 DTO 加了字段），这里会红。
 */
class SectionShapeTests extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        cleanTables();
        seedAdmin();
    }

    @Test
    void list_returns_views_ordered_by_order_no_and_has_exactly_three_keys() throws Exception {
        // 刻意让 order_no 与 id 的顺序相反：回出来必须是按 order_no（1 → 2），不是插入序
        jdbc.update("INSERT INTO section (name, order_no) VALUES ('乙声部', 2)");
        jdbc.update("INSERT INTO section (name, order_no) VALUES ('甲声部', 1)");

        api(adminToken(), "GET", "/api/sections", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("甲声部"))
                .andExpect(jsonPath("$.data[0].orderNo").value(1))
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[1].name").value("乙声部"))
                .andExpect(jsonPath("$.data[1].orderNo").value(2))
                // ⚠️ 形状守卫：正好 3 个键，多一个就说明 Entity 又漏出来了
                .andExpect(jsonPath("$.data[0].length()").value(3));
    }

    /** 声部由造数/脚本事先灌好，应用只读；一个都没有时回空数组（不是 404 / 500）。 */
    @Test
    void empty_returns_empty_array() throws Exception {
        api(adminToken(), "GET", "/api/sections", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
