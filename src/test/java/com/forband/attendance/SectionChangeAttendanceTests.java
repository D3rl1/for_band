package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/13 不变式 7（2026-09-22 扩展）：**声部课的应到名单要按 {@code event.date}
 * 在 {@code member_role_history} 回溯"当时属于哪个声部"**，不能拿当前的 {@code section_id} 比。
 * <p>
 * 起因（评审 A1）：团员换过声部后，若按当前声部算历史声部课，
 * **旧声部少算一人、新声部多算一人——两个声部的出勤率都错**。
 */
class SectionChangeAttendanceTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long ruan;
    private long pipa;
    private long memberId;

    /** 时间线：30 天前入团（阮）→ 7 天前换到琵琶。用 jdbc 直接摆，因为
     *  {@code MemberService.update} 写历史的 {@code from_date} 固定是"今天"。 */
    private static final LocalDate JOINED = LocalDate.now().minusDays(30);
    private static final LocalDate SWITCHED = LocalDate.now().minusDays(7);

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        ruan = seedSection("阮声部");
        pipa = seedSection("琵琶声部");
        MemberCreateResponse m = createMember(admin, "李明", "liming", ruan, "MEMBER");
        memberId = m.id();

        // 当前声部已是琵琶（真实情况就该这样）；能不能算进"阮声部的历史那场"，全靠回溯
        jdbc.update("UPDATE member SET joined_at = ?, section_id = ? WHERE id = ?", JOINED, pipa, memberId);
        jdbc.update("INSERT INTO member_role_history "
                        + "(member_id, role, section_id, from_date, to_date, created_by, created_at) "
                        + "VALUES (?, 'MEMBER', ?, ?, ?, ?, NOW())",
                memberId, ruan, JOINED, SWITCHED, memberId);
        jdbc.update("INSERT INTO member_role_history "
                        + "(member_id, role, section_id, from_date, to_date, created_by, created_at) "
                        + "VALUES (?, 'MEMBER', ?, ?, NULL, ?, NOW())",
                memberId, pipa, SWITCHED, memberId);
    }

    private long sectionClassOn(LocalDate date, long sectionId) {
        return eventService.create(new EventCreateRequest(
                "SECTION_CLASS", "声部课", date,
                LocalTime.of(19, 0), LocalTime.of(20, 30), "琴房 208", sectionId, null),
                admin).id();
    }

    private String expectedNamesOf(long eventId) throws Exception {
        // 显式按 UTF-8 读：MockMvc 默认可能按 ISO-8859-1 解，中文会变乱码、断言假失败
        return api(adminToken(), "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 换声部前那场声部课_应到仍按当时的声部算() throws Exception {
        LocalDate beforeSwitch = SWITCHED.minusDays(7);   // 他那时还在阮声部

        String ruanClass = expectedNamesOf(sectionClassOn(beforeSwitch, ruan));
        assertTrue(ruanClass.contains("李明"),
                "换声部之前那场阮声部声部课，应到里应该有他（按当时的声部回溯）：" + ruanClass);

        String pipaClass = expectedNamesOf(sectionClassOn(beforeSwitch, pipa));
        assertFalse(pipaClass.contains("李明"),
                "那时他还不在琵琶声部，琵琶声部的课不该算他应到：" + pipaClass);
    }

    @Test
    void 换声部之后那场声部课_按新声部算() throws Exception {
        LocalDate afterSwitch = SWITCHED.plusDays(2);     // 已经换到琵琶

        String pipaClass = expectedNamesOf(sectionClassOn(afterSwitch, pipa));
        assertTrue(pipaClass.contains("李明"), "换过之后，琵琶声部的课应该算他：" + pipaClass);

        String ruanClass = expectedNamesOf(sectionClassOn(afterSwitch, ruan));
        assertFalse(ruanClass.contains("李明"), "他已经不在阮声部，阮声部的课不该算他：" + ruanClass);
    }
}
