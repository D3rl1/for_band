package com.forband.issue;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问题讨论公开栏（docs/15 §15.3–§15.4）。动权限 + 表结构，按 AGENTS.md 硬规矩必须配反例测试。
 */
class IssueTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long sectionId;
    private long memberAId;
    private String memberAToken;
    private String memberBToken;
    private String leaderToken;
    private String honoraryToken;
    private String adminBearer;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        adminBearer = adminToken();
        sectionId = seedSection("吹管声部");

        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", sectionId, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());

        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", sectionId, "MEMBER");
        memberBToken = loginToken("memberB", b.initialPassword());

        MemberCreateResponse l = createMember(admin, "团长", "leader", sectionId, "LEADER");
        leaderToken = loginToken("leader", l.initialPassword());

        // 荣誉团员：授予规则是"仅 GRADUATED 可授予"，所以要先改成毕业再授荣誉
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", sectionId, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null, null, null), admin);
        honoraryToken = loginToken("honorary", h.initialPassword());
    }

    @Test
    void leader_cannot_change_status_403_30001() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");

        api(leaderToken, "PUT", "/api/issues/" + id + "/status", "{\"status\":\"FIXED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        assertEquals("PENDING", issueStatus(id));
        assertEquals(0, systemCommentCount(id));
    }

    @Test
    void admin_change_status_writes_status_fields_and_system_comment() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");

        api(adminBearer, "PUT", "/api/issues/" + id + "/status",
                "{\"status\":\"PROCESSING\",\"reason\":\"正在排查\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals("PROCESSING", issueStatus(id));
        assertEquals(admin.memberId(), jdbc.queryForObject(
                "SELECT status_changed_by FROM issue WHERE id = ?", Long.class, id));
        assertNotNull(jdbc.queryForObject(
                "SELECT status_changed_at FROM issue WHERE id = ?", Object.class, id));

        assertEquals("正在排查", jdbc.queryForObject(
                "SELECT content FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                String.class, id));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT old_status FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                String.class, id));
        assertEquals("PROCESSING", jdbc.queryForObject(
                "SELECT new_status FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                String.class, id));

        // 系统行不是团员回帖，列表的 commentCount 不应被它撑大
        api(adminBearer, "GET", "/api/issues", null)
                .andExpect(jsonPath("$.data.list[0].commentCount").value(0));

        // 不给理由时，系统行写默认文案
        api(adminBearer, "PUT", "/api/issues/" + id + "/status", "{\"status\":\"FIXED\"}")
                .andExpect(status().isOk());
        assertEquals("状态改为「已修复」", jdbc.queryForObject(
                "SELECT content FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM' ORDER BY id DESC LIMIT 1",
                String.class, id));
    }

    @Test
    void same_status_is_success_noop_and_does_not_touch_updated_at() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");
        LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
        setIssueUpdatedAt(id, old);

        api(adminBearer, "PUT", "/api/issues/" + id + "/status", "{\"status\":\"PENDING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(old, issueUpdatedAt(id));
        assertEquals(0, systemCommentCount(id));
    }

    @Test
    void rejected_requires_reason_and_reason_is_written_to_system_comment() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");

        api(adminBearer, "PUT", "/api/issues/" + id + "/status", "{\"status\":\"REJECTED\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals("PENDING", issueStatus(id));
        assertEquals(0, systemCommentCount(id));

        long longReasonId = createIssue(memberAToken, "第二条", "第二条正文");
        api(adminBearer, "PUT", "/api/issues/" + longReasonId + "/status",
                "{\"status\":\"REJECTED\",\"reason\":\"" + "x".repeat(256) + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals("PENDING", issueStatus(longReasonId));

        api(adminBearer, "PUT", "/api/issues/" + id + "/status",
                "{\"status\":\"REJECTED\",\"reason\":\"与现有设计一致，不处理\"}")
                .andExpect(status().isOk());
        assertEquals("与现有设计一致，不处理", jdbc.queryForObject(
                "SELECT content FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                String.class, id));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT new_status FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                String.class, id));
    }

    @Test
    void invalid_status_422_10001() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");

        api(adminBearer, "PUT", "/api/issues/" + id + "/status", "{\"status\":\"DONE\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals("PENDING", issueStatus(id));
    }

    @Test
    void honorary_can_read_but_cannot_write() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");

        api(honoraryToken, "GET", "/api/issues", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1));
        api(honoraryToken, "GET", "/api/issues/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) id))
                .andExpect(jsonPath("$.data.canEdit").value(false));
        api(honoraryToken, "GET", "/api/issues/activity", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestUpdatedAt").value(notNullValue()));

        api(honoraryToken, "POST", "/api/issues", issueBody("荣誉团员发帖", "不该成功", "设备"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(honoraryToken, "POST", "/api/issues/" + id + "/comments", "{\"content\":\"回帖\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(honoraryToken, "POST", "/api/issues/" + id + "/reaction", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(honoraryToken, "PUT", "/api/issues/" + id,
                issueBody("改标题", "改正文", "改设备"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM issue", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_comment WHERE kind = 'USER'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM issue_reaction", Integer.class));
    }

    @Test
    void editing_others_or_system_comment_is_forbidden() throws Exception {
        long issueA = createIssue(memberAToken, "A", "A 正文");
        long issueB = createIssue(memberBToken, "B", "B 正文");

        api(memberBToken, "PUT", "/api/issues/" + issueA, issueBody("B 改 A", "不该成功", "设备"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));

        api(memberAToken, "POST", "/api/issues/" + issueA + "/comments", "{\"content\":\"作者回帖\"}")
                .andExpect(status().isOk());
        long userCommentId = jdbc.queryForObject(
                "SELECT MAX(id) FROM issue_comment WHERE issue_id = ?", Long.class, issueA);

        api(memberBToken, "PUT", "/api/issues/" + issueA + "/comments/" + userCommentId,
                "{\"content\":\"B 改 A 的回帖\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));

        api(adminBearer, "PUT", "/api/issues/" + issueA + "/status", "{\"status\":\"FIXED\"}")
                .andExpect(status().isOk());
        long systemCommentId = jdbc.queryForObject(
                "SELECT MAX(id) FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                Long.class, issueA);

        api(memberAToken, "PUT", "/api/issues/" + issueA + "/comments/" + systemCommentId,
                "{\"content\":\"改系统行\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        // 回帖不属于这个 issue → 404，不能靠换 issueId 改到别处的回帖
        api(memberAToken, "PUT", "/api/issues/" + issueB + "/comments/" + userCommentId,
                "{\"content\":\"跨帖改\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void reaction_is_idempotent_and_author_is_noop() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");
        String url = "/api/issues/" + id + "/reaction";

        api(memberBToken, "POST", url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactionCount").value(1))
                .andExpect(jsonPath("$.data.reactedByMe").value(true));
        api(memberBToken, "POST", url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactionCount").value(1));

        api(memberBToken, "DELETE", url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactionCount").value(0))
                .andExpect(jsonPath("$.data.reactedByMe").value(false));
        api(memberBToken, "DELETE", url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactionCount").value(0));

        // 作者点自己：成功空操作、不落行
        api(memberAToken, "POST", url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactionCount").value(0))
                .andExpect(jsonPath("$.data.reactedByMe").value(false));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM issue_reaction", Integer.class));
    }

    @Test
    void list_filters_mine_status_and_clamps_size() throws Exception {
        long issueA = createIssue(memberAToken, "A", "A 正文");
        long issueB = createIssue(memberBToken, "B", "B 正文");
        // 两条帖在同一秒内创建（DATETIME 无小数秒），不让 B 靠 id 反超；排序断言只关心 A 被顶上来
        setIssueUpdatedAt(issueB, LocalDateTime.of(2020, 1, 1, 0, 0));

        api(memberAToken, "GET", "/api/issues?mine=1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) issueA));

        api(adminBearer, "PUT", "/api/issues/" + issueA + "/status", "{\"status\":\"FIXED\"}")
                .andExpect(status().isOk());
        api(adminBearer, "GET", "/api/issues?status=FIXED", null)
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) issueA));

        api(memberAToken, "POST", "/api/issues/" + issueA + "/comments", "{\"content\":\"顶一下\"}")
                .andExpect(status().isOk());
        api(adminBearer, "GET", "/api/issues", null)
                .andExpect(jsonPath("$.data.list[0].id").value((int) issueA))
                .andExpect(jsonPath("$.data.list[1].id").value((int) issueB));

        api(adminBearer, "GET", "/api/issues?size=1000", null)
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void updated_at_moves_only_on_comment_and_status() throws Exception {
        long id = createIssue(memberAToken, "原标题", "原正文");
        LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);

        // 作者编辑：写 edited_at，但不顶帖
        setIssueUpdatedAt(id, old);
        api(memberAToken, "PUT", "/api/issues/" + id, issueBody("新标题", "新正文", "新设备"))
                .andExpect(status().isOk());
        assertEquals(old, issueUpdatedAt(id));
        assertNotNull(jdbc.queryForObject(
                "SELECT edited_at FROM issue WHERE id = ?", Object.class, id));

        // 回帖：推进 updated_at
        api(memberAToken, "POST", "/api/issues/" + id + "/comments", "{\"content\":\"作者回帖\"}")
                .andExpect(status().isOk());
        assertTrue(issueUpdatedAt(id).isAfter(old));
        long commentId = jdbc.queryForObject(
                "SELECT MAX(id) FROM issue_comment WHERE issue_id = ?", Long.class, id);

        // +1：不推进
        setIssueUpdatedAt(id, old);
        api(memberBToken, "POST", "/api/issues/" + id + "/reaction", null)
                .andExpect(status().isOk());
        assertEquals(old, issueUpdatedAt(id));

        // 编辑回帖：不推进
        setIssueUpdatedAt(id, old);
        api(memberAToken, "PUT", "/api/issues/" + id + "/comments/" + commentId,
                "{\"content\":\"改个错字\"}")
                .andExpect(status().isOk());
        assertEquals(old, issueUpdatedAt(id));
    }

    @Test
    void create_collects_ua_and_validates_lengths() throws Exception {
        String ua = "Mozilla/5.0 (Linux; Android 14) Samsung SM-S9210 Chrome/128.0";
        mockMvc.perform(post("/api/issues")
                        .header("Authorization", "Bearer " + memberAToken)
                        .header("User-Agent", ua)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"签到没反应\",\"content\":\"点了没反应\","
                                + "\"deviceInfo\":\"三星 S24 / Android 14\","
                                + "\"pagePath\":\"/event-detail.html?id=12\","
                                + "\"userAgent\":\"bogus-from-client\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        long id = jdbc.queryForObject("SELECT MAX(id) FROM issue", Long.class);
        assertEquals(ua, jdbc.queryForObject("SELECT user_agent FROM issue WHERE id = ?",
                String.class, id));
        assertEquals("三星 S24 / Android 14", jdbc.queryForObject(
                "SELECT device_info FROM issue WHERE id = ?", String.class, id));
        assertEquals("/event-detail.html?id=12", jdbc.queryForObject(
                "SELECT page_path FROM issue WHERE id = ?", String.class, id));

        api(memberAToken, "POST", "/api/issues",
                "{\"title\":\"标题\",\"content\":\"正文\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/issues", issueBody("题".repeat(51), "正文", "设备"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/issues", issueBody("标题", "文".repeat(2001), "设备"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/issues", issueBody("标题", "正文", "设".repeat(101)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        // 边界值应当成功
        api(memberAToken, "POST", "/api/issues",
                issueBody("题".repeat(50), "文".repeat(2000), "设".repeat(100)))
                .andExpect(status().isOk());

        long boundaryId = jdbc.queryForObject("SELECT MAX(id) FROM issue", Long.class);
        api(memberAToken, "POST", "/api/issues/" + boundaryId + "/comments",
                "{\"content\":\"" + "回".repeat(1001) + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/issues/" + boundaryId + "/comments",
                "{\"content\":\"" + "回".repeat(1000) + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void activity_is_null_when_empty_then_reports_latest() throws Exception {
        api(adminBearer, "GET", "/api/issues/activity", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestUpdatedAt").value(nullValue()));

        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");
        api(adminBearer, "GET", "/api/issues/activity", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestUpdatedAt").value(notNullValue()));

        LocalDateTime old = LocalDateTime.of(2020, 1, 1, 0, 0);
        setIssueUpdatedAt(id, old);
        api(memberAToken, "POST", "/api/issues/" + id + "/comments", "{\"content\":\"新回帖\"}")
                .andExpect(status().isOk());
        assertTrue(issueUpdatedAt(id).isAfter(old));
    }

    @Test
    void missing_issue_is_404() throws Exception {
        api(memberAToken, "GET", "/api/issues/999999", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(memberAToken, "POST", "/api/issues/999999/comments", "{\"content\":\"x\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(memberAToken, "POST", "/api/issues/999999/reaction", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void detail_exposes_can_edit_and_comment_authorship() throws Exception {
        long id = createIssue(memberAToken, "签到没反应", "点了签到按钮没有反应");
        api(memberAToken, "POST", "/api/issues/" + id + "/comments", "{\"content\":\"我的回帖\"}")
                .andExpect(status().isOk());

        api(memberAToken, "GET", "/api/issues/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorId").value((int) memberAId))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.comments[0].kind").value("USER"))
                .andExpect(jsonPath("$.data.comments[0].canEdit").value(true));

        api(memberBToken, "GET", "/api/issues/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canEdit").value(false))
                .andExpect(jsonPath("$.data.comments[0].canEdit").value(false));
    }

    // ---------- helpers ----------

    private long createIssue(String token, String title, String content) throws Exception {
        api(token, "POST", "/api/issues", issueBody(title, content, "小米 14 / Android 15"))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM issue", Long.class);
    }

    private String issueBody(String title, String content, String deviceInfo) {
        return "{\"title\":\"" + title + "\",\"content\":\"" + content
                + "\",\"deviceInfo\":\"" + deviceInfo + "\"}";
    }

    private String issueStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM issue WHERE id = ?", String.class, id);
    }

    private int systemCommentCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_comment WHERE issue_id = ? AND kind = 'SYSTEM'",
                Integer.class, id);
    }

    private LocalDateTime issueUpdatedAt(long id) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT updated_at FROM issue WHERE id = ?", Timestamp.class, id);
        assertNotNull(ts);
        return ts.toLocalDateTime();
    }

    private void setIssueUpdatedAt(long id, LocalDateTime value) {
        jdbc.update("UPDATE issue SET updated_at = ? WHERE id = ?", Timestamp.valueOf(value), id);
    }
}
