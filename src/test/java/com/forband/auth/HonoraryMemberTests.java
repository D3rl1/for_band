package com.forband.auth;

import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 荣誉团员（docs/02 §2.3 + docs/03 §3.4 + docs/01 登录资格）：
 * 授予规则、honorary_until fail-closed 登录门槛、只读权限面。
 * 动角色 + 表结构，按 AGENTS.md 硬规矩必须配测试。
 */
class HonoraryMemberTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long zhangId;        // 被授予荣誉团员的毕业成员
    private String zhangInitial; // 初始密码（先取：首登会清空该列）
    private long memberAId;
    private String memberAToken;
    private long section1;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse zhang = createMember(admin, "张三", "zhang3", section1, "MEMBER");
        zhangId = zhang.id();
        zhangInitial = zhang.initialPassword();
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        setMemberStatus(admin, zhangId, "GRADUATED");
    }

    @Test
    void graduated_granted_honorary_can_login_and_me_shows_role() throws Exception {
        grantHonorary(zhangId, LocalDate.now().plusYears(2));

        api(null, "POST", "/api/auth/login",
                "{\"account\":\"zhang3\",\"password\":\"" + zhangInitial + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.member.role").value("HONORARY"));

        String token = loginToken("zhang3", zhangInitial);
        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("HONORARY"));
    }

    @Test
    void grant_requires_graduated_status_and_honorary_until_10001() throws Exception {
        // 未填有效期：fail-closed，授予口直接拒绝
        api(adminToken(), "PUT", "/api/admin/members/" + zhangId,
                "{\"role\":\"HONORARY\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        // 非 GRADUATED（memberA 是 ACTIVE）：不可授予
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"role\":\"HONORARY\",\"honoraryUntil\":\"2028-09-23\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        // 建号入口也不允许直接建荣誉团员
        api(adminToken(), "POST", "/api/admin/members",
                "{\"name\":\"李四\",\"account\":\"lisi4\",\"role\":\"HONORARY\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void expired_honorary_login_20003_and_tokens_revoked() throws Exception {
        grantHonorary(zhangId, LocalDate.now().plusDays(1));
        String token = loginToken("zhang3", zhangInitial); // 有效期内可登录

        // 管理员把有效期改到过去 → 中途失效
        grantHonorary(zhangId, LocalDate.now().minusDays(1));

        api(null, "POST", "/api/auth/login",
                "{\"account\":\"zhang3\",\"password\":\"" + zhangInitial + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(20003));
        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void honorary_read_only_surface() throws Exception {
        grantHonorary(zhangId, LocalDate.now().plusYears(2));
        String token = loginToken("zhang3", zhangInitial);
        long eventId = event(startsInMinutes(60));

        // 请假 ❌ —— ⚠️ 必须带上合法 reason，否则会先被 @NotBlank 拦成 422/10001，
        // 这条就测不到"权限拒绝"了（理由必填见 docs/05 §5.5）
        api(token, "POST", "/api/events/" + eventId + "/leave-requests", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 签到 ❌（不在应到名单：仅 ACTIVE）
        api(token, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        // 单场考勤只见本人（应到名单里没有荣誉团员 → 空行，不能看到团员甲的行）
        api(token, "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        // 本人出勤汇总 ✅；他人汇总 ❌
        api(token, "GET", "/api/members/" + zhangId + "/attendance-summary", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        api(token, "GET", "/api/members/" + memberAId + "/attendance-summary", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
        // 全团聚合统计 ❌（控制器白名单不含 HONORARY）
        api(token, "GET", "/api/attendance/stats", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 管理端 ❌
        api(token, "PUT", "/api/admin/members/" + memberAId, "{\"note\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 我的请假列表仍可看（自己的历史 ✅）
        api(token, "GET", "/api/leave-requests?scope=mine", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 名册可见但手机号不可见（仅管理员/团长/指挥）
        api(token, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    @Test
    void honorary_status_cannot_leave_graduated() throws Exception {
        grantHonorary(zhangId, LocalDate.now().plusYears(2));
        // 不变式 13（双向）：改成 ACTIVE 会让 canLogin 第一步就放行 → honorary_until 当场失效，
        // 且重新进应到名单能签到。必须拒绝。
        api(adminToken(), "PUT", "/api/admin/members/" + zhangId, "{\"status\":\"ACTIVE\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken(), "PUT", "/api/admin/members/" + zhangId, "{\"status\":\"PAUSED\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        assertEquals("HONORARY", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, zhangId));
        assertEquals("GRADUATED", jdbc.queryForObject(
                "SELECT status FROM member WHERE id = ?", String.class, zhangId));
    }

    @Test
    void honorary_sees_only_whole_band_events_and_own_section_class() throws Exception {
        grantHonorary(zhangId, LocalDate.now().plusYears(2));
        String token = loginToken("zhang3", zhangInitial);
        long section2 = seedSection("打击声部");
        long rehearsalId = event("REHEARSAL", null, startsInMinutes(60));
        long ownClassId = event("SECTION_CLASS", section1, startsInMinutes(90));
        long otherClassId = event("SECTION_CLASS", section2, startsInMinutes(120));

        String body = api(token, "GET", "/api/events?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"id\":" + rehearsalId));
        assertTrue(body.contains("\"id\":" + ownClassId));
        assertFalse(body.contains("\"id\":" + otherClassId), "荣誉团员不该看到别声部的声部课");

        api(token, "GET", "/api/events/" + otherClassId, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(token, "GET", "/api/events/" + ownClassId, null)
                .andExpect(status().isOk());
    }

    // ---------- 造数 ----------

    private void grantHonorary(long memberId, LocalDate until) {
        memberService.update(memberId,
                new com.forband.member.dto.MemberUpdateRequest(
                        null, null, "HONORARY", null, null, null, null, null, until, null,
                        null, null, null, null, null, null),
                admin);
    }

    private long event(LocalDateTime start) {
        return event("REHEARSAL", null, start);
    }

    private long event(String kind, Long sectionId, LocalDateTime start) {
        return eventService.create(new EventCreateRequest(
                kind, "活动", start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, 30), "排练厅", sectionId, null), admin).id();
    }

    private LocalDateTime startsInMinutes(long minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }
}
