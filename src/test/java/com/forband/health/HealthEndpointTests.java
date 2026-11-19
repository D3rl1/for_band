package com.forband.health;

import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `GET /api/health`（docs/12 §12.6「运维端点」）：**只回服务存活状态，不含任何业务数据**。
 * <p>
 * 该端点同时被 `refresh.sh` / `restart.sh` 轮询，所以"不带 token 也能回 200"也是契约的一部分；
 * 反例是**响应里出现业务字段**（曾泄漏 `sectionCount` = 声部数，属于给未登录者查库计数）。
 */
class HealthEndpointTests extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        cleanTables();
        seedAdmin();
        seedSection("二胡");
        seedSection("琵琶");
    }

    @Test
    void health_reports_only_liveness_without_business_fields() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.db").value("UP"))
            // 反例：任何业务数据都不许出现在这个未登录可读的端点里
            .andExpect(jsonPath("$.data.sectionCount").doesNotExist())
            .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.aMapWithSize(2)));
    }
}
