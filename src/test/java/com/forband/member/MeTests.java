package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人栏的「最近流水」（docs/05 §5.6）：签到 + 请假两路合并、时间倒序、limit 夹紧、**只回本人**。
 * 这条接口此前零覆盖，而 `§20` 刚把它取 top-N 的写法从拼 SQL 换成分页插件——顺手把它钉住。
 */
class MeTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private String memberAToken;
    private long memberBId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        memberBId = createMember(admin, "团员乙", "memberB", section1, "MEMBER").id();
    }

    @Test
    void merges_both_sources_newest_first_and_honours_limit() throws Exception {
        long e1 = event("两小时前的排练", LocalDateTime.now().plusHours(2));
        long e2 = event("一小时前请假的排练", LocalDateTime.now().plusHours(3));
        long e3 = event("三小时前的排练", LocalDateTime.now().plusHours(4));

        LocalDateTime now = LocalDateTime.now();
        insertAttendance(e1, memberAId, now.minusHours(2));
        insertLeave(e2, memberAId, now.minusHours(1));   // 最新的一条是请假
        insertAttendance(e3, memberAId, now.minusHours(3));

        // limit=2 → 取最新的两条，且**跨来源**按时间倒序（请假那条在前）
        String body = api(memberAToken, "GET", "/api/me/recent-activity?limit=2", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // ⚠️ 必须显式给 UTF-8：响应头不带 charset，无参的 getContentAsString() 会按
                // ISO-8859-1 解，中文全变乱码（只断言 ASCII 字段的用例撞不到这个坑）
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("请假 · 待审批"), "最新的请假要排在最前");
        assertTrue(body.indexOf("请假 · 待审批") < body.indexOf("出勤"), "倒序：请假比出勤新");

        // 签名条数 = 流水条数；每行都带得出 eventId 与标题（前端"看全部/跳详情"要用）
        assertTrue(body.contains("\"eventId\":" + e2));
        assertTrue(body.contains("一小时前请假的排练"));
    }

    @Test
    void limit_zero_defaults_and_oversize_is_clamped() throws Exception {
        long e1 = event("排练", LocalDateTime.now().plusHours(2));
        insertAttendance(e1, memberAId, LocalDateTime.now().minusHours(1));

        // limit=0 → 走默认值；limit=99 → 被夹到上限。两条都不能报错
        api(memberAToken, "GET", "/api/me/recent-activity?limit=0", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        api(memberAToken, "GET", "/api/me/recent-activity?limit=99", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void partial_is_listed_with_its_own_label_and_absent_is_excluded() throws Exception {
        long partialEvent = event("甲场", LocalDateTime.now().plusHours(2));
        long absentEvent = event("乙场", LocalDateTime.now().plusHours(3));
        long presentEvent = event("丙场", LocalDateTime.now().plusHours(4));

        LocalDateTime now = LocalDateTime.now();
        insertAttendance(partialEvent, memberAId, now.minusMinutes(30), "PARTIAL");
        insertAttendance(absentEvent, memberAId, now.minusMinutes(20), "ABSENT");
        insertAttendance(presentEvent, memberAId, now.minusMinutes(10), "PRESENT");

        String body = api(memberAToken, "GET", "/api/me/recent-activity?limit=10", null)
                .andExpect(status().isOk())
                // PARTIAL 是"到场了但提前走"——进流水、带自己的文案；ABSENT 没有到场事实，不进流水
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].eventId").value((int) presentEvent))
                .andExpect(jsonPath("$.data[0].note").value("出勤"))
                .andExpect(jsonPath("$.data[1].eventId").value((int) partialEvent))
                .andExpect(jsonPath("$.data[1].note").value("遗憾离场"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("\"eventId\":" + absentEvent), "ABSENT 不该出现在最近流水里");
    }

    @Test
    void only_returns_own_rows() throws Exception {
        long mine = event("我的排练", LocalDateTime.now().plusHours(2));
        long others = event("别人的排练", LocalDateTime.now().plusHours(3));
        insertAttendance(mine, memberAId, LocalDateTime.now().minusHours(1));
        insertAttendance(others, memberBId, LocalDateTime.now().minusMinutes(30));

        String body = api(memberAToken, "GET", "/api/me/recent-activity?limit=10", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 接口不收 memberId 参数、只认登录态——别人的那条不能漏出来（SECURITY.md A4）
        assertTrue(body.contains("\"eventId\":" + mine));
        assertFalse(body.contains("\"eventId\":" + others), "不该看到别人的流水");
    }

    // ---------- 造数 ----------

    private long event(String title, LocalDateTime start) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", title, start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, 30), "排练厅", null, null), admin).id();
    }

    /** 直接落库并**显式给时间**：同一秒内造的行排序不稳定（DATETIME 没有小数秒）。 */
    private void insertAttendance(long eventId, long memberId, LocalDateTime markedAt) {
        insertAttendance(eventId, memberId, markedAt, "PRESENT");
    }

    private void insertAttendance(long eventId, long memberId, LocalDateTime markedAt, String status) {
        jdbc.update("INSERT INTO attendance (event_id, member_id, status, marked_at, marked_by)"
                + " VALUES (?, ?, ?, ?, ?)", eventId, memberId, status,
                Timestamp.valueOf(markedAt), memberId);
    }

    private void insertLeave(long eventId, long memberId, LocalDateTime appliedAt) {
        jdbc.update("INSERT INTO leave_request (event_id, member_id, reason, status, applied_at)"
                + " VALUES (?, ?, '有事', 'PENDING', ?)", eventId, memberId, Timestamp.valueOf(appliedAt));
    }
}
