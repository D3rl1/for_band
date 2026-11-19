package com.forband.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ---------- 代签不受签到窗口限制（补录历史场次走的就是这条路，docs/05 §5.3） ----------

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

    // ---------- 有生效中请假的人不能代签（docs/05 §5.3 + docs/10 40008） ----------

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

    // ---------- 四态（DECISIONS D33 / 交接单 #218） ----------

    @Test
    void change_status_accepts_four_states_and_rejects_unknown() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        long recordId = recordIdOf(eventId, memberAId);

        for (String state : List.of("PARTIAL", "LEAVE", "ABSENT", "PRESENT")) {
            api(adminToken(), "PUT", "/api/attendance/" + recordId, "{\"status\":\"" + state + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value(state));
            assertEquals(state, statusOf(recordId), "落库要与响应一致：" + state);
        }

        // 非法值 / 缺字段都走 10001 校验错误，且**不能改掉已有状态**
        for (String body : List.of("{\"status\":\"HALF\"}", "{}")) {
            api(adminToken(), "PUT", "/api/attendance/" + recordId, body)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(10001));
        }
        assertEquals("PRESENT", statusOf(recordId));
    }

    @Test
    void manual_reclassification_persists_and_never_creates_a_row() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        long recordId = recordIdOf(eventId, memberAId);

        api(adminToken(), "PUT", "/api/attendance/" + recordId, "{\"status\":\"PARTIAL\"}")
                .andExpect(status().isOk());
        assertEquals("PARTIAL", statusOf(recordId));
        api(adminToken(), "PUT", "/api/attendance/" + recordId, "{\"status\":\"ABSENT\"}")
                .andExpect(status().isOk());
        assertEquals("ABSENT", statusOf(recordId));

        // ABSENT 只允许改判**已有行**：不存在的 attendanceId 一律 404，绝不借机补一行
        api(adminToken(), "PUT", "/api/attendance/999999", "{\"status\":\"ABSENT\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10002));
        assertEquals(1, countAttendance(eventId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE id = 999999", Integer.class));
    }

    @Test
    void check_in_after_manual_status_is_40002_and_keeps_status() throws Exception {
        long eventId = event(startsInMinutes(10), 60);
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        long recordId = recordIdOf(eventId, memberAId);

        for (String state : List.of("ABSENT", "PARTIAL")) {
            api(adminToken(), "PUT", "/api/attendance/" + recordId, "{\"status\":\"" + state + "\"}")
                    .andExpect(status().isOk());
            // 已有任意记录（含改判出来的 ABSENT / PARTIAL）都算"签过"，不覆盖
            api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40002));
            assertEquals(state, statusOf(recordId));
        }
        assertEquals(1, countAttendance(eventId));
    }

    @Test
    void four_states_conserve_across_event_sessions_summary_and_stats() throws Exception {
        // 先补齐应到人数：团员甲 + 声部长 + 丙 + 丁 = 4（指挥不算应到）
        long memberCId = createMember(admin, "团员丙", "memberC", section1, "MEMBER").id();
        long memberDId = createMember(admin, "团员丁", "memberD", section1, "MEMBER").id();
        long eventId = event(startsInMinutes(10), 60);

        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());                      // 甲 PRESENT
        insertAttendance(eventId, leaderId, "PARTIAL");           // 声部长 遗憾离场
        insertAttendance(eventId, memberCId, "LEAVE");            // 丙 请假
        // 丁不写任何行 → 应为缺勤

        // ① 活动详情：守恒式 expected = present + leave + absent，且 PARTIAL 并进 present
        String eventBody = api(adminToken(), "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expected").value(4))
                .andExpect(jsonPath("$.data.present").value(2))
                .andExpect(jsonPath("$.data.leave").value(1))
                .andExpect(jsonPath("$.data.absent").value(1))
                .andExpect(jsonPath("$.data.rate").value(0.5))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 没有第五个统计数：data 的字段集合就是这 11 个（含明细 rows）
        JsonNode data = new ObjectMapper().readTree(eventBody).path("data");
        Set<String> keys = new HashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("eventId", "eventTitle", "eventDate", "eventKind", "eventStatus", "rows",
                "expected", "present", "leave", "absent", "rate"), keys);
        assertEquals(data.path("expected").asInt(),
                data.path("present").asInt() + data.path("leave").asInt() + data.path("absent").asInt());

        // ② 历次场次（管理视图）同一口径
        api(adminToken(), "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) eventId))
                .andExpect(jsonPath("$.data.list[0].expected").value(4))
                .andExpect(jsonPath("$.data.list[0].present").value(2))
                .andExpect(jsonPath("$.data.list[0].leave").value(1))
                .andExpect(jsonPath("$.data.list[0].absent").value(1))
                .andExpect(jsonPath("$.data.list[0].rate").value(0.5));

        // ③ 个人汇总：PARTIAL 计出勤；无记录者由守恒式成为缺勤
        api(adminToken(), "GET", "/api/members/" + memberAId + "/attendance-summary", null)
                .andExpect(jsonPath("$.data.rehearsal.expected").value(1))
                .andExpect(jsonPath("$.data.rehearsal.present").value(1))
                .andExpect(jsonPath("$.data.rehearsal.leave").value(0))
                .andExpect(jsonPath("$.data.rehearsal.absent").value(0))
                .andExpect(jsonPath("$.data.rehearsal.rate").value(1.0));
        api(adminToken(), "GET", "/api/members/" + leaderId + "/attendance-summary", null)
                .andExpect(jsonPath("$.data.rehearsal.present").value(1))
                .andExpect(jsonPath("$.data.rehearsal.absent").value(0));
        api(adminToken(), "GET", "/api/members/" + memberDId + "/attendance-summary", null)
                .andExpect(jsonPath("$.data.rehearsal.expected").value(1))
                .andExpect(jsonPath("$.data.rehearsal.present").value(0))
                .andExpect(jsonPath("$.data.rehearsal.leave").value(0))
                .andExpect(jsonPath("$.data.rehearsal.absent").value(1))
                .andExpect(jsonPath("$.data.rehearsal.rate").value(0.0));

        // ④ 全员聚合：同样 4/2/1/1
        api(adminToken(), "GET", "/api/attendance/stats", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kinds[0].kind").value("REHEARSAL"))
                .andExpect(jsonPath("$.data.kinds[0].expected").value(4))
                .andExpect(jsonPath("$.data.kinds[0].present").value(2))
                .andExpect(jsonPath("$.data.kinds[0].leave").value(1))
                .andExpect(jsonPath("$.data.kinds[0].absent").value(1))
                .andExpect(jsonPath("$.data.kinds[0].rate").value(0.5))
                .andExpect(jsonPath("$.data.kinds[1].expected").value(0));
    }

    @Test
    void csv_uses_four_chinese_labels_and_unknown_status_is_not_present() throws Exception {
        long memberCId = createMember(admin, "团员丙", "memberC", section1, "MEMBER").id();
        createMember(admin, "团员丁", "memberD", section1, "MEMBER");
        long eventId = event(startsInMinutes(10), 60);

        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance", "{}")
                .andExpect(status().isOk());
        insertAttendance(eventId, leaderId, "PARTIAL");
        insertAttendance(eventId, memberCId, "LEAVE");
        // 丁无记录 → 缺勤

        String csv = api(adminToken(), "GET", "/api/attendance/export?kind=REHEARSAL", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, countOf(csv, "出勤"), "PRESENT 只应出现一次");
        assertEquals(1, countOf(csv, "遗憾离场"));
        assertEquals(1, countOf(csv, "请假"));
        assertEquals(1, countOf(csv, "缺勤"), "无记录者要显示缺勤");

        // 未知状态输出原串，**绝不能兜底成「出勤」**（旧实现就是 else → 出勤）
        jdbc.update("UPDATE attendance SET status = 'MYSTERY' WHERE event_id = ? AND member_id = ?",
                eventId, memberAId);
        String second = api(adminToken(), "GET", "/api/attendance/export?kind=REHEARSAL", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, countOf(second, "出勤"));
        assertEquals(1, countOf(second, "MYSTERY"));
    }

    // ---------- 造数 ----------

    private long event(LocalDateTime start, long durationMinutes) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, durationMinutes), "排练厅", null, null), admin).id();
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

    private long recordIdOf(long eventId, long memberId) {
        return jdbc.queryForObject(
                "SELECT id FROM attendance WHERE event_id = ? AND member_id = ?",
                Long.class, eventId, memberId);
    }

    private String statusOf(long attendanceId) {
        return jdbc.queryForObject(
                "SELECT status FROM attendance WHERE id = ?", String.class, attendanceId);
    }

    /** 直接造一条考勤行（绕开签到窗口，用来铺四态）；marked_by 必填，借本人占位。 */
    private void insertAttendance(long eventId, long memberId, String status) {
        jdbc.update("INSERT INTO attendance (event_id, member_id, status, marked_at, marked_by)"
                + " VALUES (?, ?, ?, NOW(), ?)", eventId, memberId, status, memberId);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
