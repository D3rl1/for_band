package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端「编辑团员」的写侧口径（`docs/03` §3.4 + `docs/01` §1.3），两个条目：
 * <ul>
 *   <li><b>#80</b> 系统账号（`is_system = 1`）的**写侧闸门**——任何写操作一律 404（不承认它存在）；
 *       没有它就能把管理员降成团员，而 `reset-admin-password.sh` 只改密码不改角色 ⇒ 系统再无管理员、救不回来。</li>
 *   <li><b>#79</b> 三条边界：① 声部长必须有声部；② 选「无声部」必须真能清空；
 *       ③ 荣誉团员的角色只能撤销为团员。外加角色白名单（**不含 ADMIN**）。</li>
 * </ul>
 * 动了权限 / 口径 ⇒ 反例为主（`AGENTS.md` 硬规矩其一）。
 */
class AdminMemberEditTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private long memberBId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        memberAId = createMember(admin, "团员甲", "memberA", section1, "MEMBER").id();
        createMember(admin, "团员乙", "memberB", section1, "MEMBER");
        memberBId = memberIdOf("memberB");
    }

    // ---------- #80 系统账号写侧闸门 ----------

    @Test
    void system_account_writes_are_404_and_nothing_changes() throws Exception {
        String token = adminToken();   // 先登录 ⇒ 存在一条该账号的 token，用来验"没有被踢下线"
        String beforeHash = jdbc.queryForObject(
                "SELECT password_hash FROM member WHERE id = ?", String.class, admin.memberId());
        int tokensBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_token WHERE member_id = ?", Integer.class, admin.memberId());
        assertEquals(1, tokensBefore, "前置：登录过就应有一条 token");

        api(token, "PUT", "/api/admin/members/" + admin.memberId(), "{\"role\":\"MEMBER\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(token, "POST", "/api/admin/members/" + admin.memberId() + "/reset-password", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(token, "POST", "/api/admin/members/" + admin.memberId() + "/revoke-sessions", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));

        // 反例的要点：**一行都没被改**（尤其 token 没被吊销——它要是被清了，400 那条就白验了）
        assertEquals("ADMIN", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, admin.memberId()));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM member WHERE id = ?", String.class, admin.memberId()));
        assertEquals(beforeHash, jdbc.queryForObject(
                "SELECT password_hash FROM member WHERE id = ?", String.class, admin.memberId()));
        assertEquals(tokensBefore, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_token WHERE member_id = ?", Integer.class, admin.memberId()));
    }

    // ---------- #79 边界①：声部长必须有声部 ----------

    @Test
    void section_leader_without_section_is_rejected_10001() throws Exception {
        // 乙带声部建的；先把他清空，再尝试"无声部当声部长"
        api(adminToken(), "PUT", "/api/admin/members/" + memberBId, "{\"clearSection\":true}")
                .andExpect(status().isOk());

        api(adminToken(), "PUT", "/api/admin/members/" + memberBId, "{\"role\":\"SECTION_LEADER\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals("MEMBER", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, memberBId));
    }

    /** 既有规则不许改坏：同一声部只能有一个在团声部长。 */
    @Test
    void second_section_leader_in_same_section_is_rejected_40007() throws Exception {
        createMember(admin, "声部长", "leader1", section1, "SECTION_LEADER");
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"role\":\"SECTION_LEADER\",\"sectionId\":" + section1 + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(40007));
    }

    // ---------- #79 边界②：选「无声部」必须真能清空 ----------

    @Test
    void clearing_section_really_clears_it() throws Exception {
        assertEquals(section1, jdbc.queryForObject(
                "SELECT section_id FROM member WHERE id = ?", Long.class, memberAId));

        api(adminToken(), "PUT", "/api/admin/members/" + memberAId, "{\"clearSection\":true}")
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject(
                "SELECT section_id FROM member WHERE id = ?", Long.class, memberAId),
                "选了「无声部」就必须真的清空，不能静默失效");

        // 反向：不带 clearSection 时，缺省不改变声部（部分更新的语义不能被这条打破）
        long section2 = seedSection("弹拨声部");
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"sectionId\":" + section2 + "}").andExpect(status().isOk());
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId, "{\"instrument\":\"笙\"}")
                .andExpect(status().isOk());
        assertEquals(section2, jdbc.queryForObject(
                "SELECT section_id FROM member WHERE id = ?", Long.class, memberAId));
    }

    // ---------- #79 边界③：荣誉团员只能撤销为团员 ----------

    @Test
    void honorary_can_only_be_revoked_to_member() throws Exception {
        long honoraryId = grantHonorary(memberAId);

        // 直接改成团长 / 指挥 / 声部长 → 拒绝（会绕过 honorary_until 的有效期校验）
        for (String role : new String[]{"LEADER", "CONDUCTOR", "SECTION_LEADER"}) {
            api(adminToken(), "PUT", "/api/admin/members/" + honoraryId,
                    "{\"role\":\"" + role + "\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(10001));
        }
        assertEquals("HONORARY", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, honoraryId));

        // 续期（角色不变、只改有效期）仍是合法的——别把授予入口一起拦死
        api(adminToken(), "PUT", "/api/admin/members/" + honoraryId,
                "{\"role\":\"HONORARY\",\"honoraryUntil\":\"" + LocalDate.now().plusYears(3) + "\"}")
                .andExpect(status().isOk());

        // 撤销为团员 → 200，且 honorary_until 被显式清空（docs/13 不变式 13：非荣誉团员本列恒 NULL）
        api(adminToken(), "PUT", "/api/admin/members/" + honoraryId, "{\"role\":\"MEMBER\"}")
                .andExpect(status().isOk());
        assertEquals("MEMBER", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, honoraryId));
        assertEquals("GRADUATED", jdbc.queryForObject(
                "SELECT status FROM member WHERE id = ?", String.class, honoraryId));
        assertNull(jdbc.queryForObject(
                "SELECT honorary_until FROM member WHERE id = ?", Object.class, honoraryId));
    }

    // ---------- 角色白名单：不含 ADMIN ----------

    @Test
    void role_admin_is_rejected_10001() throws Exception {
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId, "{\"role\":\"ADMIN\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals("MEMBER", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, memberAId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE role = 'ADMIN'", Integer.class),
                "系统里应当只有播种出来的那一个管理员");
    }

    /** 善意路径不能被边界①③与白名单误伤：换届就是"把甲改成团长"。 */
    @Test
    void ordinary_role_change_still_works_and_writes_history() throws Exception {
        api(adminToken(), "PUT", "/api/admin/members/" + memberAId,
                "{\"role\":\"LEADER\",\"status\":\"ACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("LEADER"));

        assertEquals("LEADER", jdbc.queryForObject(
                "SELECT role FROM member WHERE id = ?", String.class, memberAId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_role_history WHERE member_id = ? AND role = 'LEADER'",
                Integer.class, memberAId));
    }

    // ---------- 造数 ----------

    /** 走接口把某人变成荣誉团员（状态先置 GRADUATED，再授予角色 + 有效期）。 */
    private long grantHonorary(long memberId) throws Exception {
        jdbc.update("UPDATE member SET status = 'GRADUATED' WHERE id = ?", memberId);
        api(adminToken(), "PUT", "/api/admin/members/" + memberId,
                "{\"role\":\"HONORARY\",\"honoraryUntil\":\"" + LocalDate.now().plusYears(2) + "\"}")
                .andExpect(status().isOk());
        return memberId;
    }
}
