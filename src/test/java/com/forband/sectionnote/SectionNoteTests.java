package com.forband.sectionnote;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上任声部长的留言（`docs/03` §3.5 + `docs/13` §13.3㉕，`交接单 #111`）。
 * <p>
 * ⭐ 四级权限**各钉一条**，而且**反例与正例一样重要**：
 * 读（**含已卸任**）、写（只限此刻在任）、改（只限作者）、**删（谁都不能）**。
 * ⚠️ 其中最容易写错的是「读含已卸任」——只写在任就会被这里的第一条抓到。
 */
class SectionNoteTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long sectionId;
    private long sectionId2;
    private long leaderId;
    private String leaderToken;
    private String memberToken;
    private String otherLeaderToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        sectionId = seedSection("胡琴声部");
        sectionId2 = seedSection("琵琶声部");
        // 现任意声部长（建号路径会同时写一条 to_date 为空的历史）
        MemberCreateResponse leader = createMember(admin, "部长甲", "leaderA", sectionId, "SECTION_LEADER");
        leaderId = leader.id();
        leaderToken = loginToken("leaderA", leader.initialPassword());
        MemberCreateResponse member = createMember(admin, "团员乙", "memberB", sectionId, "MEMBER");
        memberToken = loginToken("memberB", member.initialPassword());
        // **另一个声部**的声部长（用来验跨声部越权）
        MemberCreateResponse other = createMember(admin, "部长丙", "leaderC", sectionId2, "SECTION_LEADER");
        otherLeaderToken = loginToken("leaderC", other.initialPassword());
    }

    // ---------- 帮手 ----------

    private static String body(String text) {
        return "{\"body\":\"" + text + "\"}";
    }

    private long noteCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM section_note WHERE section_id = ?", Long.class, sectionId);
    }

    private long latestNoteId() {
        return jdbc.queryForObject("SELECT MAX(id) FROM section_note", Long.class);
    }

    /** 让某人**卸任**：历史那条开着的 `SECTION_LEADER` 收口（`to_date` 有值）——这正是"历任"的形态。 */
    private void stepDown(long memberId) {
        jdbc.update("UPDATE member_role_history SET to_date = CURDATE() "
                + "WHERE member_id = ? AND role = 'SECTION_LEADER' AND to_date IS NULL", memberId);
        jdbc.update("UPDATE member SET role = 'MEMBER' WHERE id = ?", memberId);
    }

    // ---------- 测试 ----------

    @Test
    void current_leader_can_post_author_can_edit_but_nobody_can_delete() throws Exception {
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("下一任加油：先对音准"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorName").value("部长甲"))
                .andExpect(jsonPath("$.data.mine").value(true));
        long noteId = latestNoteId();
        assertEquals(1, noteCount());

        // 作者本人能改
        api(leaderToken, "PUT", "/api/sections/" + sectionId + "/notes/" + noteId, body("改过一句"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("改过一句"))
                .andExpect(jsonPath("$.data.mine").value(true));

        // 非作者不能改：同声部的普通团员（30002）
        api(memberToken, "PUT", "/api/sections/" + sectionId + "/notes/" + noteId, body("我来改改"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
        // 非作者不能改：**别的声部的声部长**（也是 30002 —— 他不是这条的作者）
        api(otherLeaderToken, "PUT", "/api/sections/" + sectionId + "/notes/" + noteId, body("我来改改"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
        // 拿**别的声部**的路径去改这条 ⇒ 10002（这条不在那个声部的资源空间里）
        api(leaderToken, "PUT", "/api/sections/" + sectionId2 + "/notes/" + noteId, body("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));

        // ⭐ **删谁都不能**：这个能力**根本不存在**（这条路径上没有 DELETE 处理器）。
        //    ⚠️ 判据**故意只钉"没成功 + 数据没少"**，不写死具体状态码：实测它现在回的是 **500/10003**
        //    （框架的 `HttpRequestMethodNotSupportedException` 被全局兜底 `@ExceptionHandler(Exception)`
        //    吞成了 500）—— "405 该回什么码"是 `交接单` §一 **#6** 还没拍板的旧账，
        //    这边不改口径；等 #6 定了把这里换成那个码即可。
        int deleteStatus = api(leaderToken, "DELETE",
                "/api/sections/" + sectionId + "/notes/" + noteId, null)
                .andReturn().getResponse().getStatus();
        assertTrue(deleteStatus >= 400, "DELETE 不该成功（实测 " + deleteStatus + "，见交接单 #6）");
        assertEquals(1, noteCount(), "留言不许被删掉");
    }

    @Test
    void ordinary_member_can_neither_read_nor_write_30001() throws Exception {
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("甲写的"))
                .andExpect(status().isOk());

        // ⭐ **读也是权限**（不是"登录即可"）：普通团员看不到
        api(memberToken, "GET", "/api/sections/" + sectionId + "/notes", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 写：普通团员不行
        api(memberToken, "POST", "/api/sections/" + sectionId + "/notes", body("我也想留一条"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        assertEquals(1, noteCount());
    }

    @Test
    void former_section_leader_still_can_read_but_not_write() throws Exception {
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("给下一任的短笺"))
                .andExpect(status().isOk());
        stepDown(leaderId);

        // ⭐ 本功能的核心口径：**"读"含已卸任**（当过这个声部长的人之间的话）
        api(leaderToken, "GET", "/api/sections/" + sectionId + "/notes", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].body").value("给下一任的短笺"))
                .andExpect(jsonPath("$.data.list[0].mine").value(true));

        // 但**写**只限"此刻在任"
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("再留一条"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 改自己那条**不受卸任影响**（作者身份是"写的那一刻"的）
        long noteId = latestNoteId();
        api(leaderToken, "PUT", "/api/sections/" + sectionId + "/notes/" + noteId, body("卸任后仍可改"))
                .andExpect(status().isOk());
    }

    @Test
    void leader_of_another_section_cannot_write_here_30001() throws Exception {
        api(otherLeaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("跨声部越权"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 连读也不行（他没在**这个**声部当过部长）
        api(otherLeaderToken, "GET", "/api/sections/" + sectionId + "/notes", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(0, noteCount());
    }

    @Test
    void pending_gives_the_new_leader_the_latest_note_written_by_someone_else() throws Exception {
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("第一任的话"))
                .andExpect(status().isOk());
        stepDown(leaderId);
        MemberCreateResponse next = createMember(admin, "部长丁", "leaderD", sectionId, "SECTION_LEADER");
        String nextToken = loginToken("leaderD", next.initialPassword());

        // 我刚上任 ⇒ 该读到上一任那条
        api(nextToken, "GET", "/api/me/section-note-pending", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("第一任的话"))
                .andExpect(jsonPath("$.data.authorName").value("部长甲"))
                .andExpect(jsonPath("$.data.mine").value(false));

        // 我自己写的那条**不算**"待读"（判据里带 `author_id ≠ 我`）⇒ 仍旧给上一任那条
        api(nextToken, "POST", "/api/sections/" + sectionId + "/notes", body("我自己的")).andExpect(status().isOk());
        api(nextToken, "GET", "/api/me/section-note-pending", null)
                .andExpect(jsonPath("$.data.body").value("第一任的话"));

        // 不是声部长 ⇒ 一条都不给（`data` 为 null，不是报错）
        api(memberToken, "GET", "/api/me/section-note-pending", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void blank_body_is_rejected_10001_and_nothing_is_written() throws Exception {
        api(leaderToken, "POST", "/api/sections/" + sectionId + "/notes", body("   "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(leaderToken, "PUT", "/api/sections/" + sectionId + "/notes/1", body(""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals(0, noteCount());
    }
}
