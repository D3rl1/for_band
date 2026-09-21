package com.forband.auth;

import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private String initialPasswordOf(long memberId) {
        return jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, memberId);
    }
}
