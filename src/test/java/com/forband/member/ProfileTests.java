package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人页（docs/03 §3.3/§3.4）：`GET/PUT /api/me/profile` **只动自己**、**不收 memberId**；
 * 可写字段只有四个，姓名/角色等改不动；`avatar` 走**预置清单白名单**（"零安全面"的关键）；
 * 生日**只对本人 + 管理员可见**。
 */
class ProfileTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private String memberAToken;
    private long memberBId;
    private String memberBToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", section1, "MEMBER");
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());
    }

    @Test
    void own_profile_can_be_read_and_written() throws Exception {
        api(memberAToken, "GET", "/api/me/profile", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("团员甲"))
                .andExpect(jsonPath("$.data.avatar").doesNotExist());

        api(memberAToken, "PUT", "/api/me/profile",
                "{\"avatar\":\"fluent-frog\",\"intro\":\"吹笙的\",\"contact\":\"微信 x\",\"birthday\":\"1999-03-05\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value("fluent-frog"))
                .andExpect(jsonPath("$.data.intro").value("吹笙的"))
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"));

        // 入库了才算数（别只信响应）
        assertTrue(jdbc.queryForObject("SELECT avatar FROM member WHERE id = ?",
                String.class, memberAId).equals("fluent-frog"));

        // 生日清空走显式开关（null 已经表示"这次不改"）
        api(memberAToken, "PUT", "/api/me/profile", "{\"clearBirthday\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthday").doesNotExist());
    }

    @Test
    void avatar_outside_catalog_422_10001() throws Exception {
        // ⚠️ 白名单是"零安全面"的关键：avatar 存的是**受控标识符**，不是 URL
        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"../../etc/passwd\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "PUT", "/api/me/profile",
                "{\"avatar\":\"https://example.com/x.svg\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void catalog_has_the_recorded_avatars() throws Exception {
        // 清单文件与素材一起放在 vendor/fluent-emoji/，**服务端与前端读同一份**
        String manifest = new String(new ClassPathResource(
                "static/vendor/fluent-emoji/manifest.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("fluent-frog"), "清单里要有 fluent-frog");
        assertTrue(new ClassPathResource(
                        "static/vendor/fluent-emoji/fluent-frog.svg").exists(),
                "清单点名的素材必须真的在库里");
    }

    @Test
    void cannot_change_name_or_role_through_own_profile() throws Exception {
        // 账号级字段收归管理员（docs/02 T8 的边界：T8 管账号级动作，不挡"本人写自己的资料字段"）
        api(memberAToken, "PUT", "/api/me/profile",
                "{\"name\":\"我改名了\",\"role\":\"ADMIN\",\"sectionId\":null,\"instrument\":\"锣\"}")
                .andExpect(status().isOk());   // 不报错，但**当没传**
        assertTrue(jdbc.queryForObject("SELECT name FROM member WHERE id = ?",
                String.class, memberAId).equals("团员甲"));
        assertTrue(jdbc.queryForObject("SELECT role FROM member WHERE id = ?",
                String.class, memberAId).equals("MEMBER"));
    }

    @Test
    void profile_carries_joined_at_for_the_identity_line() throws Exception {
        // 个人页头部的标签行要用「XXXX 年入团」，且与个人栏浮层**同源**（docs/14 §14.6.4）——
        // 所以 profile 接口必须把它带出来（之前漏了，前端根本拿不到）
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId, "{\"joinedAt\":\"2020-09-01\"}")
                .andExpect(status().isOk());
        api(memberAToken, "GET", "/api/me/profile", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinedAt").value("2020-09-01"));
    }

    @Test
    void auth_me_carries_avatar() throws Exception {
        api(memberAToken, "PUT", "/api/me/profile", "{\"avatar\":\"fluent-cat\"}")
                .andExpect(status().isOk());
        api(memberAToken, "GET", "/api/auth/me", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value("fluent-cat"));
    }

    @Test
    void admin_can_also_edit_those_four_fields() throws Exception {
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"avatar\":\"fluent-panda\",\"intro\":\"管理员填的\",\"contact\":\"138\",\"birthday\":\"2000-01-02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value("fluent-panda"))
                .andExpect(jsonPath("$.data.birthday").value("2000-01-02"));
        // 管理员这条路径也要过白名单
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId, "{\"avatar\":\"nope\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void birthday_is_hidden_from_others() throws Exception {
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"birthday\":\"1999-03-05\",\"avatar\":\"fluent-frog\",\"contact\":\"wx\"}")
                .andExpect(status().isOk());

        // 本人看得到
        api(memberAToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"));
        // 别人看不到（连"月-日"都没有——本期没有对外界面显示生日，宁可什么都不给）
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthday").doesNotExist());
        // 名册列表里也一样
        String list = api(memberBToken, "GET", "/api/members?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(list.contains("1999-03-05"), "名册列表不能泄漏他人生日");

        // 而 avatar / intro / contact 是登录即可见的
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.avatar").exists());
    }

}
