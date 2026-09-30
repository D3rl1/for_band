package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 名册的排序与默认过滤（`docs/03` §3.4，`交接单 #109`）。
 * <p>
 * ⭐ 钉住六件事：**顺序逐项符合**（指挥 → 团长 → 声部 `order_no` → 声部长 → 团员 → 非在团 → 荣誉）、
 * **默认没有退团**、显式 `status=LEFT` 查得到、`includeLeft=true` 查得到、
 * **分页第 2 页不重不漏**、**系统管理员不出现**。
 * <p>
 * ⚠️ 断言一律打 **`account`（ASCII）**：`getContentAsString()` 默认按 ISO-8859-1 解码，中文姓名必乱码
 * （`debug_log` 2026-09-23 那条）。
 */
class MemberListOrderTests extends IntegrationTestBase {

    private CurrentUser admin;
    private String token;

    /** 期望的次序（`account`，全是 ASCII）。**默认视图**（不含退团）。 */
    private static final List<String> EXPECTED = List.of(
            "conductor", "tuanzhang",                 // 指挥 → 团长（都没有声部）
            "leadB", "memberB",                       // 声部 B（order_no = 1）
            "leadA", "memberA",                       // 声部 A（order_no = 2，**id 更小**）
            "paused",                                 // 非在团整块沉底
            "honorary");                              // 荣誉团员另起一节（前端在它前面插「历届成员」）

    /** 带上 `includeLeft=true` 的次序：退团的人落在"非在团"那一块里（同组按 id）。 */
    private static final List<String> EXPECTED_WITH_LEFT = List.of(
            "conductor", "tuanzhang", "leadB", "memberB", "leadA", "memberA",
            "paused", "left", "honorary");

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        // ⚠️ 两个声部的 **`order_no` 故意与 id 相反**：A 先建（id 小）但 `order_no` 更大
        //    ⇒ 只有按 `order_no` 排才会得到 leadB 在 leadA 前面（规格点名的那一条）。
        long sectionA = seedSection("胡琴声部");
        long sectionB = seedSection("琵琶声部");
        jdbc.update("UPDATE section SET order_no = 2 WHERE id = ?", sectionA);
        jdbc.update("UPDATE section SET order_no = 1 WHERE id = ?", sectionB);

        createMember(admin, "指挥", "conductor", null, "CONDUCTOR");
        createMember(admin, "团长", "tuanzhang", null, "LEADER");
        createMember(admin, "乙部长", "leadB", sectionB, "SECTION_LEADER");
        createMember(admin, "乙团员", "memberB", sectionB, "MEMBER");
        createMember(admin, "甲部长", "leadA", sectionA, "SECTION_LEADER");
        createMember(admin, "甲团员", "memberA", sectionA, "MEMBER");
        long paused = createMember(admin, "休团的", "paused", sectionA, "MEMBER").id();
        setMemberStatus(admin, paused, "PAUSED");
        // ⚠️ 荣誉团员**建号路径造不出来**（`MemberService.create` 明写"只能授予已毕业成员、走「改角色」入口"）
        //    ⇒ 这里直接落库：本用例验的是**名册的排序**，不是"授予荣誉"那条流程。
        long honorary = createMember(admin, "荣誉的", "honorary", sectionA, "MEMBER").id();
        jdbc.update("UPDATE member SET role = 'HONORARY', status = 'GRADUATED' WHERE id = ?", honorary);
        long left = createMember(admin, "退团的", "left", sectionA, "MEMBER").id();
        setMemberStatus(admin, left, "LEFT");

        token = adminToken();
    }

    private List<String> accounts(String query) throws Exception {
        String body = api(token, "GET", "/api/members" + query, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.list[*].account");
    }

    @Test
    void roster_order_is_conductor_leader_section_order_then_off_roster_then_honorary() throws Exception {
        assertEquals(EXPECTED, accounts("?size=100"), "默认视图的次序不对");
        assertEquals(EXPECTED_WITH_LEFT, accounts("?size=100&includeLeft=true"), "带上退团后的次序不对");
    }

    @Test
    void left_members_are_hidden_by_default_but_reachable_both_ways() throws Exception {
        // ⭐ 默认视图**不含退团**（用户原话「退团的人肯定不在名单上了」）
        assertFalse(accounts("?size=100").contains("left"), "默认视图不该出现退团的人");
        // ⭐ 但"查询能力"保留：显式选「退团」筛选时查得到
        assertEquals(List.of("left"), accounts("?size=100&status=LEFT"));
        // ⭐ 另两个调用方（管理端名册 / 演出参演名单）用 `includeLeft=true` 把行为要回来
        assertTrue(accounts("?size=100&includeLeft=true").contains("left"));
    }

    @Test
    void system_admin_never_appears() throws Exception {
        assertFalse(accounts("?size=100&includeLeft=true").contains("admin"));
    }

    @Test
    void paging_does_not_duplicate_or_skip() throws Exception {
        // ⚠️ 分页必须建立在**同一个排序**上：这里是"全集排完再切页"，第 2 页既不重也不漏。
        List<String> joined = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            joined.addAll(accounts("?page=" + page + "&size=3&includeLeft=true"));
        }
        assertEquals(EXPECTED_WITH_LEFT, joined);
    }

    @Test
    void section_filter_still_works_with_the_new_order() throws Exception {
        List<String> onlyA = accounts("?size=100&sectionId=" + jdbc.queryForObject(
                "SELECT id FROM section WHERE name = '胡琴声部'", Long.class));
        assertEquals(List.of("leadA", "memberA", "paused", "honorary"), onlyA);
    }
}
