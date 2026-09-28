package com.forband.auth;

import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话管理（docs/01 §1.3/§1.4 + docs/13 §13.3④ 三新列 + docs/10 §10.4 幂等）：
 * UA/IP 采集与打码、last_seen 节流、当前设备判定、踢单条/踢其他/管理端踢人。
 * 动了表结构，按 AGENTS.md 硬规矩必须配测试。
 */
class SessionTests extends IntegrationTestBase {

    private static final String EDGE_WIN_UA =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0";

    private CurrentUser admin;
    private long zhangId;
    private String zhangInitial;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        MemberCreateResponse zhang = createMember(admin, "张三", "zhang3", seedSection("吹管声部"), "MEMBER");
        zhangId = zhang.id();
        zhangInitial = zhang.initialPassword();
    }

    @Test
    void login_records_ua_ip_and_leaves_last_seen_null() throws Exception {
        String token = apiLogin("zhang3", zhangInitial, EDGE_WIN_UA, "203.0.113.7");
        org.junit.jupiter.api.Assertions.assertNotNull(token);

        var row = jdbc.queryForMap(
                "SELECT ip, user_agent, last_seen_at FROM user_token WHERE member_id = ?", zhangId);
        assertEquals("203.0.113.7", row.get("ip"));
        assertEquals(EDGE_WIN_UA, row.get("user_agent"));
        org.junit.jupiter.api.Assertions.assertNull(row.get("last_seen_at")); // 登录后第一个 API 请求才会填
    }

    @Test
    void sessions_list_masks_ip_labels_device_marks_current() throws Exception {
        String token = apiLogin("zhang3", zhangInitial, EDGE_WIN_UA, "192.168.1.5");
        api(token, "GET", "/api/auth/sessions", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].device").value("Edge · Windows"))
                .andExpect(jsonPath("$.data[0].ip").value("192.168.1.*")) // 展示打码，库里存完整
                .andExpect(jsonPath("$.data[0].current").value(true))
                // 这次 GET 本身就是"登录后第一个 API 请求"，touch 已把 last_seen_at 填上（docs/01 §1.4）
                .andExpect(jsonPath("$.data[0].lastSeenAt").value(org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void last_seen_write_is_throttled_to_hourly() throws Exception {
        String token = loginToken("zhang3", zhangInitial);
        api(token, "GET", "/api/auth/me", null).andExpect(status().isOk()); // NULL → 立即写

        jdbc.update("UPDATE user_token SET last_seen_at = NOW() - INTERVAL 30 MINUTE WHERE member_id = ?", zhangId);
        java.time.LocalDateTime before = jdbc.queryForObject(
                "SELECT last_seen_at FROM user_token WHERE member_id = ?", java.time.LocalDateTime.class, zhangId);
        api(token, "GET", "/api/auth/me", null).andExpect(status().isOk()); // 1 小时内不再写
        java.time.LocalDateTime after = jdbc.queryForObject(
                "SELECT last_seen_at FROM user_token WHERE member_id = ?", java.time.LocalDateTime.class, zhangId);
        assertEquals(before, after);

        jdbc.update("UPDATE user_token SET last_seen_at = NOW() - INTERVAL 2 HOUR WHERE member_id = ?", zhangId);
        api(token, "GET", "/api/auth/me", null).andExpect(status().isOk()); // 超 1 小时 → 写
        java.time.LocalDateTime renewed = jdbc.queryForObject(
                "SELECT last_seen_at FROM user_token WHERE member_id = ?", java.time.LocalDateTime.class, zhangId);
        org.junit.jupiter.api.Assertions.assertTrue(renewed.isAfter(before));
    }

    @Test
    void revoke_single_effective_idempotent() throws Exception {
        String tokenA = loginToken("zhang3", zhangInitial);
        String tokenB = loginToken("zhang3", zhangInitial);

        // 用 B 找到 A 那条会话（current=false 的行）；json-smart 的数值是 Integer，按 Number 取
        String list = api(tokenB, "GET", "/api/auth/sessions", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<java.util.Map<String, Object>> rows =
                com.jayway.jsonpath.JsonPath.read(list, "$.data");
        long otherId = rows.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("current")))
                .map(r -> ((Number) r.get("id")).longValue())
                .findFirst().orElseThrow();

        api(tokenB, "POST", "/api/auth/sessions/" + otherId + "/revoke", null)
                .andExpect(status().isOk());
        api(tokenA, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));

        // 幂等：已失效的 id、不存在的 id，一律成功不报错（docs/10 §10.4）
        api(tokenB, "POST", "/api/auth/sessions/" + otherId + "/revoke", null)
                .andExpect(status().isOk());
        api(tokenB, "POST", "/api/auth/sessions/99999/revoke", null)
                .andExpect(status().isOk());
    }

    @Test
    void revoke_other_members_session_403_30002() throws Exception {
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", null, "MEMBER");
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", null, "MEMBER");
        String tokenA = loginToken("memberA", a.initialPassword());
        String tokenB = loginToken("memberB", b.initialPassword());

        String list = api(tokenB, "GET", "/api/auth/sessions", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long bSessionId = ((Number) com.jayway.jsonpath.JsonPath.read(list, "$.data[0].id")).longValue();

        api(tokenA, "POST", "/api/auth/sessions/" + bSessionId + "/revoke", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
    }

    @Test
    void revoke_others_keeps_current() throws Exception {
        String tokenA = loginToken("zhang3", zhangInitial);
        String tokenB = loginToken("zhang3", zhangInitial);
        String tokenC = loginToken("zhang3", zhangInitial);

        api(tokenC, "POST", "/api/auth/sessions/revoke-others", null).andExpect(status().isOk());
        api(tokenC, "GET", "/api/auth/me", null).andExpect(status().isOk()); // 当前这条保留
        api(tokenA, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
        api(tokenB, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void admin_revoke_sessions_kicks_member_without_touching_status() throws Exception {
        String token = loginToken("zhang3", zhangInitial);
        api(adminToken(), "POST", "/api/admin/members/" + zhangId + "/revoke-sessions", null)
                .andExpect(status().isOk());

        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
        // 只踢登录态，不动账号状态：还能重新登录
        String status = jdbc.queryForObject(
                "SELECT status FROM member WHERE id = ?", String.class, zhangId);
        assertEquals("ACTIVE", status);
        loginToken("zhang3", zhangInitial);
    }

    @Test
    void honorary_member_can_use_sessions() throws Exception {
        setMemberStatus(admin, zhangId, "GRADUATED");
        memberService.update(zhangId,
                new com.forband.member.dto.MemberUpdateRequest(
                        null, null, "HONORARY", null, null, null, null, null, LocalDate.now().plusYears(2), null,
                        null, null, null, null, null),
                admin);
        String token = loginToken("zhang3", zhangInitial);
        api(token, "GET", "/api/auth/sessions", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 造数 ----------

    /** 带 UA 与来源 IP 的 API 登录（采集断言都要走这里，loginToken 助手不带头）。 */
    private String apiLogin(String account, String password, String ua, String remoteAddr) throws Exception {
        MockHttpServletRequestBuilder builder =
                request(HttpMethod.POST, "/api/auth/login")
                        .header("User-Agent", ua)
                        .with(r -> {
                            r.setRemoteAddr(remoteAddr);
                            return r;
                        })
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}");
        ResultActions actions = mockMvc.perform(builder);
        String body = actions.andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.token");
    }
}
