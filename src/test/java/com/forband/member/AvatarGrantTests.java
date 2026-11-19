package com.forband.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 纪念头像定向发放（docs/02 §2.4、docs/13 §13.3㉛、#266）：
 * 限定头像只有拿到发放资格的人能选；资格永久有效，不随离团 / 停用 / 角色变化失效；
 * 误发撤回只标记不删行，佩戴中撤回要回退成姓名首字。
 * <p>
 * ⚠️ 本类用**测试专用清单**（{@link TestCatalog}）注入两张限定头像——
 * 生产清单里还没有真实纪念头像，不能为了测试往产品清单里塞假条目（#260 的既有约定）。
 */
@Import(AvatarGrantTests.TestCatalog.class)
class AvatarGrantTests extends IntegrationTestBase {

    private static final String LIMITED = "test-limited-1";
    private static final String LIMITED_2 = "test-limited-2";
    private static final String PUBLIC = "fluent-cat";

    /**
     * 只替换清单本身：一张公共、两张限定，其余目录路径沿用产品清单的命名规则。
     * 用 {@code @Primary} 让 MeProfileService 等既有注入点拿到它。
     */
    @TestConfiguration
    static class TestCatalog {
        @Bean
        @Primary
        AvatarCatalog avatarCatalog() {
            String json = """
                    {"avatars":[
                      {"id":"fluent-cat","name":"猫","file":"fluent-cat.svg"},
                      {"id":"test-limited-1","name":"校庆纪念","file":"fluent-cat.svg","scope":"grant"},
                      {"id":"test-limited-2","name":"毕业纪念","file":"fluent-cat.svg","scope":"grant"}
                    ]}
                    """;
            return new AvatarCatalog(new ObjectMapper(),
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private CurrentUser admin;
    private String adminToken;
    private long memberAId;
    private String memberAToken;
    private long memberBId;
    private String memberBToken;
    private String conductorToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        adminToken = adminToken();
        long section = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", section, "MEMBER");
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());
        MemberCreateResponse c = createMember(admin, "指挥丙", "conductorC", section, "CONDUCTOR");
        conductorToken = loginToken("conductorC", c.initialPassword());
    }

    private void grantTo(long... memberIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : memberIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[" + ids + "]}")
                .andExpect(status().isOk());
    }

    @Test
    void limited_avatar_requires_grant() throws Exception {
        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        grantTo(memberAId);

        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value(LIMITED));
        assertEquals(LIMITED, jdbc.queryForObject(
                "SELECT avatar FROM member WHERE id = ?", String.class, memberAId));
    }

    @Test
    void grant_is_admin_only() throws Exception {
        String body = "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[" + memberAId + "]}";
        api(memberAToken, "POST", "/api/admin/avatar-grants", body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(conductorToken, "POST", "/api/admin/avatar-grants", body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(memberAToken, "GET", "/api/admin/avatar-grants", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void grant_rejects_public_or_unknown_avatar() throws Exception {
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + PUBLIC + "\",\"memberIds\":[" + memberAId + "]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"no-such-avatar\",\"memberIds\":[" + memberAId + "]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void grant_rejects_unknown_member_and_empty_list() throws Exception {
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[999999]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void repeat_grant_does_not_create_second_row() throws Exception {
        grantTo(memberAId);
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[" + memberAId + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.granted.length()").value(0))
                .andExpect(jsonPath("$.data.skipped.length()").value(1));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM avatar_grant WHERE member_id = ? AND avatar_id = ?",
                Integer.class, memberAId, LIMITED));
    }

    @Test
    void revoke_requires_reason_and_existing_grant() throws Exception {
        grantTo(memberAId);
        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED + "?reason=", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberBId + "/" + LIMITED + "?reason=发错人了", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void revoke_clears_worn_avatar_and_blocks_reselection() throws Exception {
        grantTo(memberAId);
        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isOk());

        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED
                        + "?reason=发错人了", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarCleared").value(true));

        assertNull(jdbc.queryForObject(
                "SELECT avatar FROM member WHERE id = ?", String.class, memberAId));
        // 行还在，只是标记为已撤回——留痕，不删证据。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM avatar_grant WHERE member_id = ? AND avatar_id = ?"
                        + " AND revoked_at IS NOT NULL",
                Integer.class, memberAId, LIMITED));

        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void grant_can_be_reactivated_after_revoke() throws Exception {
        grantTo(memberAId);
        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED + "?reason=发错人了", null)
                .andExpect(status().isOk());

        grantTo(memberAId);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM avatar_grant WHERE member_id = ? AND avatar_id = ?",
                Integer.class, memberAId, LIMITED));
        assertNull(jdbc.queryForObject(
                "SELECT revoked_at FROM avatar_grant WHERE member_id = ? AND avatar_id = ?",
                Object.class, memberAId, LIMITED));
    }

    @Test
    void grant_survives_member_status_change() throws Exception {
        grantTo(memberAId);
        api(adminToken, "PUT", "/api/admin/members/" + memberAId, "{\"status\":\"LEFT\"}")
                .andExpect(status().isOk());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM avatar_grant WHERE member_id = ? AND avatar_id = ?"
                        + " AND revoked_at IS NULL",
                Integer.class, memberAId, LIMITED));
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isUnauthorized());
        // 永久资格：管理员代改时仍能给他设这张头像（校验的是目标成员资格，不是登录状态）。
        api(adminToken, "PUT", "/api/admin/members/" + memberAId,
                "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value(LIMITED));
    }

    @Test
    void admin_edit_validates_target_member_not_admin_own_grant() throws Exception {
        grantTo(memberAId);

        api(adminToken, "PUT", "/api/admin/members/" + memberBId,
                "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken, "PUT", "/api/admin/members/" + memberAId,
                "{\"avatar\":\"" + LIMITED + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void my_grants_lists_only_active_grants() throws Exception {
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        grantTo(memberAId, memberBId);
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].avatarId").value(LIMITED))
                // batch 为 null 时字段仍在（record 序列化含 null）——断言"没有值"，不是"字段不存在"
                .andExpect(jsonPath("$.data[0].batch").value(org.hamcrest.Matchers.nullValue()));

        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED + "?reason=发错人了", null)
                .andExpect(status().isOk());
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 乙的资格没被连坐
        api(memberBToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].avatarId").value(LIMITED));
    }

    @Test
    void my_grants_expose_batch_for_recipient() throws Exception {
        // docs/14 §14.6.4「获赠团员端」：本人资格带批次名，选中区才能显示「纪念限定 · 批次名」。
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[" + memberAId
                        + "],\"batch\":\"十周年\"}")
                .andExpect(status().isOk());
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].avatarId").value(LIMITED))
                .andExpect(jsonPath("$.data[0].batch").value("十周年"));
        // 管理端字段不外泄：发放人 / 理由不进团员端响应
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(jsonPath("$.data[0].grantedByName").doesNotExist())
                .andExpect(jsonPath("$.data[0].revokeReason").doesNotExist());
    }

    @Test
    void revoke_reason_longer_than_column_is_rejected() throws Exception {
        // 理由走查询参数，没有 @Size 兜底；超长直接撞 varchar(200) 会变成 500。
        grantTo(memberAId);
        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED
                        + "?reason=" + "a".repeat(201), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        // 资格仍在：失败的撤回不能把记录改坏
        api(memberAToken, "GET", "/api/me/avatar-grants", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].avatarId").value(LIMITED));
    }

    @Test
    void grant_list_keeps_revoked_rows_for_audit() throws Exception {
        api(adminToken, "POST", "/api/admin/avatar-grants",
                "{\"avatarId\":\"" + LIMITED + "\",\"memberIds\":[" + memberAId
                        + "],\"batch\":\"2026 校庆\",\"note\":\"演出纪念\"}")
                .andExpect(status().isOk());
        api(adminToken, "DELETE",
                "/api/admin/avatar-grants/" + memberAId + "/" + LIMITED
                        + "?reason=名单弄错了", null)
                .andExpect(status().isOk());

        api(adminToken, "GET", "/api/admin/avatar-grants?avatarId=" + LIMITED, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memberId").value(memberAId))
                .andExpect(jsonPath("$.data[0].memberName").value("团员甲"))
                .andExpect(jsonPath("$.data[0].avatarId").value(LIMITED))
                .andExpect(jsonPath("$.data[0].batch").value("2026 校庆"))
                .andExpect(jsonPath("$.data[0].revokedAt").exists())
                .andExpect(jsonPath("$.data[0].revokeReason").value("名单弄错了"));
    }
}
