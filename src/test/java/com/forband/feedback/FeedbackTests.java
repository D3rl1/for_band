package com.forband.feedback;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史反馈归档（docs/15 §15.3.2）：旧的提交 / 处理写入口随 #227 退役，GET 保留为只读三页签。
 */
class FeedbackTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long sectionId;
    private long memberAId;
    private String memberAToken;
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
    }

    @Test
    void archive_is_admin_only() throws Exception {
        api(memberAToken, "GET", "/api/admin/feedback", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(adminBearer, "GET", "/api/admin/feedback", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void default_is_open_and_supports_three_status_filters() throws Exception {
        long openId = insertFeedback("还没处理", FeedbackStatus.OPEN);
        long handledId = insertFeedback("已经处理", FeedbackStatus.HANDLED);
        long migratedId = insertFeedback("迁进公开栏", FeedbackStatus.MIGRATED);
        long issueId = insertMigratedIssue(migratedId);

        api(adminBearer, "GET", "/api/admin/feedback", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) openId))
                .andExpect(jsonPath("$.data.list[0].status").value("OPEN"));

        api(adminBearer, "GET", "/api/admin/feedback?status=HANDLED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) handledId))
                .andExpect(jsonPath("$.data.list[0].migratedIssueId").value(nullValue()));

        api(adminBearer, "GET", "/api/admin/feedback?status=MIGRATED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) migratedId))
                .andExpect(jsonPath("$.data.list[0].migratedIssueId").value((int) issueId));
    }

    @Test
    void invalid_status_422_10001() throws Exception {
        api(adminBearer, "GET", "/api/admin/feedback?status=FOO", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void retired_write_endpoints_are_gone() throws Exception {
        long feedbackId = insertFeedback("旧的待处理", FeedbackStatus.OPEN);

        api(memberAToken, "POST", "/api/feedback", "{\"content\":\"新的反馈\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        api(adminBearer, "PUT", "/api/admin/feedback/" + feedbackId,
                "{\"status\":\"HANDLED\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));

        // 只读归档仍在
        api(adminBearer, "GET", "/api/admin/feedback", null)
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private long insertFeedback(String content, String status) {
        jdbc.update("INSERT INTO feedback (member_id, content, status, created_at) "
                + "VALUES (?, ?, ?, NOW())", memberAId, content, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM feedback", Long.class);
    }

    private long insertMigratedIssue(long feedbackId) {
        jdbc.update("INSERT INTO issue "
                        + "(member_id, title, content, status, source_feedback_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                memberAId, "历史反馈 #" + feedbackId, "迁入的正文", "PENDING", feedbackId);
        return jdbc.queryForObject("SELECT MAX(id) FROM issue", Long.class);
    }
}
