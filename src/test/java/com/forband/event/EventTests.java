package com.forband.event;

import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.event.dto.EventBatchRequest;
import com.forband.event.dto.EventCreateRequest;
import com.forband.event.dto.EventView;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/04 排练与声部课：批量生成、可见性、取消。 */
class EventTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long section2;
    private long memberAId;
    private String memberAToken;
    private long secLeader2Id;
    private String secLeader2Token;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        section2 = seedSection("弹拨声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse sl = createMember(admin, "声部长乙", "secleaderB", section2, "SECTION_LEADER");
        secLeader2Id = sl.id();
        secLeader2Token = loginToken("secleaderB", sl.initialPassword());
    }

    @Test
    void batch_generation_shares_batch_id_and_counts_weekdays() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        // 从明天起 14 天内、每逢"明天的星期几"生成 → 恰好 2 场
        List<EventView> created = eventService.batchCreate(new EventBatchRequest(
                "REHEARSAL", "每周排练", tomorrow, tomorrow.plusDays(13),
                List.of(tomorrow.getDayOfWeek().getValue()),
                java.time.LocalTime.of(19, 0), java.time.LocalTime.of(21, 0),
                "排练厅", null, null), admin);
        assertEquals(2, created.size());
        assertEquals(created.get(0).batchId(), created.get(1).batchId());
        assertNotEquals(created.get(0).id(), created.get(1).id());
    }

    @Test
    void member_cannot_see_other_sections_section_class() throws Exception {
        long ownClassId = eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "本声部声部课", LocalDate.now().plusDays(1),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, section1, null), admin).id();
        long otherClassId = eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "别声部声部课", LocalDate.now().plusDays(2),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, section2, null), admin).id();

        // 列表在 SQL 层过滤：团员只看非声部课 + 本声部声部课（docs/04）
        String body = api(memberAToken, "GET", "/api/events?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode list = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("list");
        java.util.Set<Long> ids = new java.util.HashSet<>();
        list.forEach(n -> ids.add(n.path("id").asLong()));
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains(ownClassId));
        org.junit.jupiter.api.Assertions.assertFalse(ids.contains(otherClassId));

        // 详情横向越权 → 404（不是 403，避免暴露存在性）
        api(memberAToken, "GET", "/api/events/" + otherClassId, null)
                .andExpect(status().isNotFound());
    }

    // TODO(你写): 声部长可见性测试（docs/04 §4.4，交接单 #220）
    // 现场已就绪：secleaderB 是声部长、属于 section2；section1 是别的声部。
    // 目标：列表里有「本声部声部课 + 全团排练」，没有「别声部声部课」；
    //       详情：本声部 200，别声部 404。
    // 可照抄上面 member_cannot_see_other_sections_section_class() 的写法。
    @Test
    void section_leader_cannot_see_other_sections_section_class() throws Exception {
        long ownClassId = eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "声部长本声部声部课", LocalDate.now().plusDays(1),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, section2, null), admin).id();
        long otherClassId = eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "别的声部声部课", LocalDate.now().plusDays(2),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, section1, null), admin).id();
        long rehearsalId = eventService.create(new EventCreateRequest(
                "REHEARSAL", "全团排练", LocalDate.now().plusDays(3),
                java.time.LocalTime.of(19, 0), java.time.LocalTime.of(21, 0),
                "排练厅", null, null), admin).id();

        // 列表在 SQL 层过滤（docs/04 §4.4）
        String body = api(secLeader2Token, "GET", "/api/events?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode list = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("list");
        java.util.Set<Long> ids = new java.util.HashSet<>();
        list.forEach(n -> ids.add(n.path("id").asLong()));

        // TODO(你写): 四个断言
        // 1) ids 含 ownClassId
        // 2) ids 含 rehearsalId
        // 3) ids 不含 otherClassId
        // 4) GET 别声部详情 -> 404；GET 本声部详情 -> 200
    }

    @Test
    void canceled_event_excluded_from_check_in_and_stats() throws Exception {
        long eventId = eventService.create(new EventCreateRequest(
                "REHEARSAL", "要取消的排练", LocalDate.now().plusDays(1),
                java.time.LocalTime.of(19, 0), java.time.LocalTime.of(21, 0),
                "排练厅", null, null), admin).id();

        api(adminToken(), "POST", "/api/events/" + eventId + "/cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // 取消的活动不可签到（40001），统计整场排除
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void section_class_requires_matching_section_on_create() {
        // 声部课 section_id 必填（不变式 5）
        assertThrows(BusinessException.class, () -> eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "无声部课", LocalDate.now().plusDays(1),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, null, null), admin));
        // 声部长只能建本声部的声部课（30003）
        assertThrows(BusinessException.class, () -> eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "越声部课", LocalDate.now().plusDays(1),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0),
                null, section1, null), currentUserOf(secLeader2Id)));
    }

    /**
     * docs/13 不变式 12 / docs/04「时间校验」：`start &lt; end`，且单场不跨天
     * （两个字段都是 `time`、无日期，所以"跨天"就是 `start &gt; end`）。
     * <p>
     * 起因（评审 A2）：原本**只有批量生成**拦了这条，**单场创建与修改能直接打接口绕过去**。
     */
    @Test
    void event_time_range_validated_on_create_and_update() {
        java.time.LocalTime t19 = java.time.LocalTime.of(19, 0);
        java.time.LocalTime t21 = java.time.LocalTime.of(21, 0);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 单场创建：开始晚于结束（= 跨天）
        assertThrows(BusinessException.class, () -> eventService.create(new EventCreateRequest(
                "REHEARSAL", "倒着排的排练", tomorrow, t21, t19, null, null, null), admin));
        // 单场创建：开始等于结束
        assertThrows(BusinessException.class, () -> eventService.create(new EventCreateRequest(
                "REHEARSAL", "零长排练", tomorrow, t19, t19, null, null, null), admin));

        // 单场修改：同一条规则也要拦
        long ok = eventService.create(new EventCreateRequest(
                "REHEARSAL", "正常排练", tomorrow, t19, t21, null, null, null), admin).id();
        assertThrows(BusinessException.class, () -> eventService.update(ok,
                new com.forband.event.dto.EventUpdateRequest(
                        "改成倒着的", tomorrow, t21, t19, null, null, null), admin));
    }
}
