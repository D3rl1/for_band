package com.forband.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private long honoraryId;

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
    void catalog_entries_all_have_files_on_disk() throws Exception {
        // 清单文件与素材一起放在 vendor/avatars/，**服务端与前端读同一份**
        JsonNode avatars = new ObjectMapper().readTree(new ClassPathResource(
                "static/vendor/avatars/manifest.json").getInputStream()).path("avatars");

        Set<String> ids = new HashSet<>();
        for (JsonNode item : avatars) {
            String id = item.path("id").asText();
            String file = item.path("file").asText();
            assertFalse(id.isBlank(), "每条都要有 id");
            // ⭐ 每条都必须**显式写 file**（代码里不拼后缀——本目录同时有 .svg 与 .png），
            // 且它**真的躺在磁盘上**。这是"坏图不许静默"唯一可自动化的代理：
            // 运行期清单写错时前端会看得见坏图（那正是 `#37` 要的效果），这里让它连测试都过不去。
            assertFalse(file.isBlank(), id + " 缺 file 字段");
            assertTrue(new ClassPathResource("static/vendor/avatars/" + file).exists(),
                    id + " 点名的素材不在库里：" + file);
            assertTrue(ids.add(id), "id 重复：" + id);
        }
        // 不写死总条数（会随入库变化）——只钉住两个端点：上游那批不许删、自制那张必须在
        assertTrue(ids.contains("fluent-frog"), "12 个上游 id 不许删（2026-09-25 这次是共存）");
        assertTrue(ids.contains("band-chubby"), "第一张自制头像 band-chubby 要入库");
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
    void birthday_is_full_for_self_and_month_day_for_others() throws Exception {
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"birthday\":\"1999-03-05\",\"avatar\":\"fluent-frog\",\"contact\":\"wx\"}")
                .andExpect(status().isOk());

        // 本人（与管理员）看**全值**
        api(memberAToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"));
        api(adminToken(), "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"));
        // 别人**只到「月-日」、不带年份**（docs/03 §3.4 + docs/13 §13.3②）。
        // ⚠️ 2026-09-25 之前这里是"一律 null"（实现比规格更保守，见 `交接单` `#8`）——
        // 现在按规格落地：别人的主页上要显示生日，只是不显示年份。
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthday").value("03-05"));
        // 名册列表里也一样：月-日可以出现，**带年份的完整值不许出现**
        String list = api(memberBToken, "GET", "/api/members?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(list.contains("\"03-05\""), "别人该看到「月-日」");
        assertFalse(list.contains("1999-03-05"), "名册列表不能泄漏他人的完整生日（带年份）");

        // 而 avatar / intro / contact 是登录即可见的
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.avatar").exists())
                .andExpect(jsonPath("$.data.contact").value("wx"));
    }

    // ---------- 荣誉团员只读（docs/02 §2.3；2026-09-24 拍板维持 ❌，2026-09-25 落地） ----------

    @Test
    void honorary_cannot_update_profile_403_30001_and_db_untouched() throws Exception {
        String token = seedHonorary();
        // 管理员先写死四个字段，作为"一个字都不许变"的基线
        api(adminToken(), "PUT", "/api/admin/members/" + honoraryId,
                "{\"avatar\":\"fluent-panda\",\"intro\":\"管理员填的\","
                        + "\"contact\":\"138\",\"birthday\":\"2000-01-02\"}")
                .andExpect(status().isOk());

        // 反例①：荣誉团员改资料 → 403 + 30001
        api(token, "PUT", "/api/me/profile",
                "{\"avatar\":\"fluent-frog\",\"intro\":\"我要改\","
                        + "\"contact\":\"999\",\"birthday\":\"1990-01-01\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 清空生日走的是同一个 update()，同样拦
        api(token, "PUT", "/api/me/profile", "{\"clearBirthday\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        // 四列逐列比对：库里确实没变（别只信响应）
        assertEquals("fluent-panda", jdbc.queryForObject(
                "SELECT avatar FROM member WHERE id = ?", String.class, honoraryId));
        assertEquals("管理员填的", jdbc.queryForObject(
                "SELECT intro FROM member WHERE id = ?", String.class, honoraryId));
        assertEquals("138", jdbc.queryForObject(
                "SELECT contact FROM member WHERE id = ?", String.class, honoraryId));
        assertEquals("2000-01-02", jdbc.queryForObject(
                "SELECT DATE_FORMAT(birthday, '%Y-%m-%d') FROM member WHERE id = ?",
                String.class, honoraryId));
    }

    @Test
    void honorary_can_still_read_own_profile() throws Exception {
        // 只读权别被误伤（荣誉团员的定位是"只读的过去"）
        api(seedHonorary(), "GET", "/api/me/profile", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("荣誉团员"));
    }

    /** 造一个**能登录**的荣誉团员（角色 HONORARY + 有效期未过 ⇒ canLogin 放行），返回其 token。 */
    private String seedHonorary() throws Exception {
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", section1, "MEMBER");
        honoraryId = h.id();
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null, LocalDate.now().plusYears(2), null,
                null, null, null, null), admin);
        return loginToken("honorary", h.initialPassword());
    }

}
