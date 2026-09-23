package com.forband.auth;

import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/01 认证流程 + docs/10 错误码。 */
class AuthFlowTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long zhangId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        zhangId = createMember(admin, "张三", "zhang3", seedSection("吹管声部"), "MEMBER").id();
    }

    @Test
    void login_success_and_wrong_password_20001() throws Exception {
        String token = loginToken("zhang3", initialPasswordOf(zhangId));
        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.account").value("zhang3"));

        api(null, "POST", "/api/auth/login",
                "{\"account\":\"zhang3\",\"password\":\"wrong-pw\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20001));
    }

    @Test
    void logout_revokes_token_20002() throws Exception {
        String token = loginToken("zhang3", initialPasswordOf(zhangId));
        api(token, "POST", "/api/auth/logout", null).andExpect(status().isOk());
        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void change_password_revokes_all_tokens() throws Exception {
        String initial = initialPasswordOf(zhangId); // 先取：首登会清空该列
        String token1 = loginToken("zhang3", initial);
        String token2 = loginToken("zhang3", initial);
        api(token1, "POST", "/api/auth/password",
                "{\"oldPassword\":\"" + initial + "\",\"newPassword\":\"brand-new-pw\"}")
                .andExpect(status().isOk());

        // docs/01：改密码后该用户全部 token 立即失效
        api(token1, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        api(token2, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        loginToken("zhang3", "brand-new-pw"); // 新密码可登录
    }

    @Test
    void first_login_clears_initial_password() throws Exception {
        String initial = initialPasswordOf(zhangId);
        loginToken("zhang3", initial);
        String stored = jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, zhangId);
        org.junit.jupiter.api.Assertions.assertNull(stored);
    }

    @Test
    void disabled_account_20003_and_tokens_revoked() throws Exception {
        String initial = initialPasswordOf(zhangId); // 先取：首登会清空该列
        String token = loginToken("zhang3", initial);
        String adminToken = adminToken();
        setMemberStatus(admin, zhangId, "PAUSED");

        // 非 ACTIVE：密码本身仍匹配（hash 未变），但登录被拒 → 403/20003 而非 401
        api(null, "POST", "/api/auth/login",
                "{\"account\":\"zhang3\",\"password\":\"" + initial + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(20003));
        api(token, "GET", "/api/auth/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    // ---------- 入团日期：能填能改（docs/03 §3.4 上线迁移）+ 留痕两个都给（docs/11 §11.2） ----------

    @Test
    void joined_at_can_be_filled_on_create_and_changed_later_with_full_trail() throws Exception {
        // 顺便钉住一个既有坑：**无声部的人**（指挥 / 老师）改任何字段都不能 500
        api(adminToken(), "POST", "/api/admin/members",
                "{\"name\":\"老团员\",\"account\":\"old1\",\"role\":\"MEMBER\",\"sectionId\":null,"
                        + "\"joinedAt\":\"2020-09-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        long id = memberIdOf("old1");
        // 老团员按**真实入团日期**录，不是建号日
        assertEquals("2020-09-01", jdbc.queryForObject(
                "SELECT joined_at FROM member WHERE id = ?", String.class, id));

        api(adminToken(), "PUT", "/api/admin/members/" + id, "{\"joinedAt\":\"2019-03-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinedAt").value("2019-03-01"));

        // 留痕：改前、改后**都要有** joinedAt——只写一半不算留痕
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT `before`, `after` FROM operation_log WHERE action = 'ACCOUNT_UPDATE'");
        assertTrue(row.get("before").toString().contains("2020-09-01"), "改前值缺 joinedAt");
        assertTrue(row.get("after").toString().contains("2019-03-01"), "改后值缺 joinedAt");
    }

    // ---------- 并发建号：唯一键兜底成 409，不能冒成 500 ----------

    @Test
    void concurrent_create_same_account_conflicts_409_never_500() throws Exception {
        String adminToken = adminToken();
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/admin/members")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"并发\",\"account\":\"race1\",\"role\":\"MEMBER\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : futures) {
                statuses.add(f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
        }

        // 恰好一个成功、另一个 409（无论它是被前置查重拦下还是被唯一键兜住），都不是 500
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "应当只有一个建成：" + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "另一个应当是 409：" + statuses);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE account = 'race1'", Integer.class));
    }

    private String initialPasswordOf(long memberId) {
        return jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, memberId);
    }
}
