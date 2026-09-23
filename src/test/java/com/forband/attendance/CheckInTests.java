package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.leave.LeaveService;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.leave.dto.LeaveDtos.RequestView;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/05 §5.3 签到规则：窗口、幂等（D9）、代签留痕。 */
class CheckInTests extends IntegrationTestBase {

    @Autowired
    private LeaveService leaveService;

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private String memberAToken;
    private long leaderId;
    private String leaderToken;
    private long conductorId;
    private String conductorToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse leader = createMember(admin, "声部长", "secleader", section1, "SECTION_LEADER");
        leaderId = leader.id();
        leaderToken = loginToken("secleader", leader.initialPassword());
        MemberCreateResponse conductor = createMember(admin, "指挥", "conductor", section1, "CONDUCTOR");
        conductorId = conductor.id();
        conductorToken = loginToken("conductor", conductor.initialPassword());
    }

    @Test
    void check_in_inside_window_success() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PRESENT"));

        assertEquals(1, countAttendance(eventId));
        assertEquals(memberAId, markedByOf(eventId, memberAId)); // 本人签：marked_by = member_id
    }

    @Test
    void check_in_outside_window_40003() throws Exception {
        // 入团日期回拨 3 天，否则过去的活动会先撞上 joined_at 应到边界（422）而非窗口判定
        jdbc.update("UPDATE member SET joined_at = CURDATE() - INTERVAL 3 DAY WHERE id = ?", memberAId);
        // 用 -300 而不是 -180：签到窗口关闭时间 = end + 2h，而 -180 时关闭时间**正好等于造活动那一刻**。
        // 只要整段执行落在同一个系统时钟刻度内（Windows 约 15ms），isAfter 就为 false → 偶发假失败。
        long eventId = event(startsInMinutes(-300), 60); // 已结束 4 小时，明确在窗口外
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void check_in_canceled_event_40001() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(adminToken(), "POST", "/api/events/" + eventId + "/cancel", null).andExpect(status().isOk());
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void duplicate_check_in_idempotent_40002_single_row() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        // docs/05：重复提交返回"已签到"，不能 500；唯一约束保证仍只有一行
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40002));
        assertEquals(1, countAttendance(eventId));
    }

    @Test
    void mark_for_other_records_operator() throws Exception {
        // 代签收窄后只有管理员 + 团长可代签（docs/02 §2.3）→ 用例自己造团长。
        // ⚠️ 不放进 setUp：一个 ACTIVE 的团长会改变其他用例的应到人数。
        long tuanzhangId = createMember(admin, "团长", "tuanzhang", null, "LEADER").id();
        String tuanzhangToken = loginToken("tuanzhang", jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, tuanzhangId));
        long eventId = event(startsInMinutes(10), 60);
        api(tuanzhangToken, "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedByName").value("团长"));

        assertEquals(tuanzhangId, markedByOf(eventId, memberAId)); // 代签：marked_by = 操作人
    }

    @Test
    void section_leader_cannot_mark_even_own_section_member_403() throws Exception {
        // 最锋利的那条回归：收窄前"声部长代签本声部"是 200，现在必须是 403
        long eventId = event(startsInMinutes(10), 60);
        api(leaderToken, "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(0, countAttendance(eventId));
    }

    // ---------- 指挥：不参与考勤统计，但查看权不减（docs/13 不变式 14 + docs/02 §2.3） ----------

    @Test
    void conductor_is_not_expected_cannot_check_in() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(conductorToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals(0, countAttendance(eventId));
    }

    @Test
    void conductor_absent_from_attendance_rows_others_present() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        String body = api(conductorToken, "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                // 分母不含指挥 → 只剩团员甲与声部长
                .andExpect(jsonPath("$.data.expected").value(2))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("\"memberId\":" + conductorId));
        assertTrue(body.contains("\"memberId\":" + memberAId));
    }

    @Test
    void conductor_personal_summary_has_zero_denominator() throws Exception {
        event(startsInMinutes(10), 60);
        // 分母为 0 → 前端据此显示「—」而不是 0%
        api(conductorToken, "GET", "/api/members/" + conductorId + "/attendance-summary", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rehearsal.expected").value(0))
                .andExpect(jsonPath("$.data.rehearsal.rate").value(0.0));
    }

    @Test
    void conductor_still_sees_whole_band_attendance_and_stats() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(conductorToken, "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        api(conductorToken, "GET", "/api/attendance/stats", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 代签不受签到窗口限制（补录历史场次走的就是这条路，docs/05 §5.4） ----------

    @Test
    void mark_for_other_works_outside_window_backfill() throws Exception {
        // 入团日期回拨，否则过去的活动会先撞上 joined_at 应到边界（422）而不是窗口判定
        jdbc.update("UPDATE member SET joined_at = CURDATE() - INTERVAL 3 DAY WHERE id = ?", memberAId);
        long eventId = event(startsInMinutes(-300), 60);   // 已结束 4 小时，明确在窗口外
        // 本人签同一场仍然 40003（既有用例守着），代签必须放行
        api(adminToken(), "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PRESENT"));
        assertEquals(1, countAttendance(eventId));
    }

    // ---------- 有生效中请假的人不能代签（docs/05 §5.4 + docs/10 40008） ----------

    @Test
    void mark_for_other_refused_when_leave_pending_40008() throws Exception {
        long eventId = event(startsInMinutes(60), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/leave-requests", "{\"reason\":\"有事\"}")
                .andExpect(status().isOk());

        api(adminToken(), "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(40008));
        assertEquals(0, countAttendance(eventId));
    }

    @Test
    void mark_for_other_refused_when_leave_approved_40008_not_40002() throws Exception {
        // 本用例自己造团长：**不放进 setUp**，否则会改变其他用例的应到人数
        long tuanzhangId = createMember(admin, "团长", "tuanzhang", null, "LEADER").id();
        CurrentUser tuanzhang = currentUserOf(tuanzhangId);
        long eventId = event(startsInMinutes(60), 60);

        // 真走一遍双人审批 → 落到"已批准 + attendance(LEAVE)"这个真实状态
        RequestView request = leaveService.create(eventId, new CreateRequest("有事"),
                currentUserOf(memberAId));
        leaveService.decide(request.approvals().get(0).id(), new DecideRequest("APPROVED", null),
                currentUserOf(leaderId));
        leaveService.decide(request.approvals().get(1).id(), new DecideRequest("APPROVED", null), tuanzhang);
        assertEquals("LEAVE", jdbc.queryForObject(
                "SELECT status FROM attendance WHERE event_id = ? AND member_id = ?",
                String.class, eventId, memberAId));

        // 以前这里会撞唯一键、报"已签到"(40002) —— 那个人明明是已批准请假，提示是误导的
        api(adminToken(), "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(40008));
    }

    @Test
    void attendance_rows_carry_leave_status() throws Exception {
        long eventId = event(startsInMinutes(60), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/leave-requests", "{\"reason\":\"有事\"}")
                .andExpect(status().isOk());

        // 出参必须区分得出"申请中"（只靠 attendance.status 是看不出来的）
        String body = api(adminToken(), "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"leaveStatus\":\"PENDING\""), "申请中的请假要能带出来");
        assertTrue(body.contains("\"leaveStatus\":null"), "没请假的人应当是 null");
    }

    // ---------- 改签到状态的留痕要写全"改前 → 改后"（docs/11 §11.2） ----------

    @Test
    void change_status_trail_records_before_and_after() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        long recordId = jdbc.queryForObject(
                "SELECT id FROM attendance WHERE event_id = ? AND member_id = ?",
                Long.class, eventId, memberAId);

        api(adminToken(), "PUT", "/api/attendance/" + recordId, "{\"status\":\"LEAVE\"}")
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT `before`, `after` FROM operation_log WHERE action = 'ATTENDANCE_SET_STATUS'");
        assertTrue(row.get("before").toString().contains("PRESENT"), "改前值不能是 null——写一半不算留痕");
        assertTrue(row.get("after").toString().contains("LEAVE"));
    }

    // ---------- 造数 ----------

    private long event(LocalDateTime start, long durationMinutes) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                start.plusMinutes(durationMinutes).toLocalTime(), "排练厅", null, null), admin).id();
    }

    private LocalDateTime startsInMinutes(long minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private int countAttendance(long eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE event_id = ?", Integer.class, eventId);
    }

    private long markedByOf(long eventId, long memberId) {
        return jdbc.queryForObject(
                "SELECT marked_by FROM attendance WHERE event_id = ? AND member_id = ?",
                Long.class, eventId, memberId);
    }
}
