package com.forband.feedback;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 反馈（docs/15 §15.4）：谁能提、UA 由服务端取、管理端只看 OPEN、处理留痕。
 * 动权限 + 表结构，按 AGENTS.md 硬规矩必须配测试。
 */
class FeedbackTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private String memberAToken;
    private String honoraryToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());

        // 荣誉团员：授予规则是"仅 GRADUATED 可授予"，所以要先把状态改成毕业
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", section1, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null, null), admin);
        honoraryToken = loginToken("honorary", h.initialPassword());
    }

    @Test
    void member_can_submit_and_server_collects_device() throws Exception {
        // UA 是**从请求头**取的（MockMvc 默认不发这个头，所以要显式带上）；
        // 请求体里塞一个假的 userAgent 来验证"不信客户端传"——DTO 里压根没这个字段，会被忽略
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/128.0";
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + memberAToken)
                        .header("User-Agent", ua)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"签到按钮点了没反应\","
                                + "\"pagePath\":\"/event-detail.html?id=12\","
                                + "\"userAgent\":\"bogus-from-client\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(ua, jdbc.queryForObject(
                "SELECT user_agent FROM feedback WHERE member_id = ?", String.class, memberAId));
        assertEquals("/event-detail.html?id=12", jdbc.queryForObject(
                "SELECT page_path FROM feedback WHERE member_id = ?", String.class, memberAId));

        // 管理端看到的"设备"是服务端粗解析出来的（与「登录设备」同一套规则，docs/15 §15.2）
        api(adminToken(), "GET", "/api/admin/feedback", null)
                .andExpect(jsonPath("$.data.list[0].device").value("Firefox · Windows"))
                .andExpect(jsonPath("$.data.list[0].memberName").value("团员甲"));

        // 不设幂等：连点两次就是两条
        api(memberAToken, "POST", "/api/feedback", "{\"content\":\"又想到一条\"}")
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedback WHERE member_id = ?", Integer.class, memberAId));
    }

    @Test
    void honorary_member_cannot_submit_403_30001() throws Exception {
        // 防腐条款"新功能默认不给荣誉团员"（docs/02 §2.3）：他们只读
        api(honoraryToken, "POST", "/api/feedback", "{\"content\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feedback", Integer.class));
    }

    @Test
    void empty_or_too_long_content_422_10001() throws Exception {
        api(memberAToken, "POST", "/api/feedback", "{\"content\":\"\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/feedback", "{\"content\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feedback", Integer.class));
    }

    @Test
    void admin_endpoints_are_admin_only_403() throws Exception {
        api(memberAToken, "GET", "/api/admin/feedback", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(memberAToken, "PUT", "/api/admin/feedback/1", "{\"status\":\"HANDLED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void list_defaults_to_open_then_can_filter_handled() throws Exception {
        long firstId = submit();
        long secondId = submit();

        // 默认视图只有待处理的（已处理的不堆在这里，docs/15 §15.4）；id 倒序
        api(adminToken(), "GET", "/api/admin/feedback", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value((int) secondId))
                .andExpect(jsonPath("$.data.list[1].id").value((int) firstId));

        api(adminToken(), "PUT", "/api/admin/feedback/" + firstId,
                "{\"status\":\"HANDLED\",\"adminNote\":\"tidy\"}")
                .andExpect(status().isOk());
        api(adminToken(), "GET", "/api/admin/feedback", null)
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) secondId));
        api(adminToken(), "GET", "/api/admin/feedback?status=HANDLED", null)
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) firstId))
                .andExpect(jsonPath("$.data.list[0].adminNote").value("tidy"));

        // handled_by / handled_at 本身就是留痕（所以不进操作日志）
        assertEquals(admin.memberId(), jdbc.queryForObject(
                "SELECT handled_by FROM feedback WHERE id = ?", Long.class, firstId));
        assertNotNull(jdbc.queryForObject(
                "SELECT handled_at FROM feedback WHERE id = ?", Object.class, firstId));
    }

    private long submit() throws Exception {
        api(memberAToken, "POST", "/api/feedback", "{\"content\":\"todo-fix\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM feedback", Long.class);
    }
}
