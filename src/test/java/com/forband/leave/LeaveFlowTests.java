package com.forband.leave;

import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.event.dto.EventCreateRequest;
import com.forband.leave.dto.LeaveDtos.ApprovalView;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.leave.dto.LeaveDtos.RequestView;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/05 §5.5 请假流程：双人审批、边界情况、错误码 40004/40005/40006。 */
class LeaveFlowTests extends IntegrationTestBase {

    @Autowired
    private LeaveService leaveService;

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private CurrentUser memberA;
    private CurrentUser secLeader;
    private CurrentUser tuanzhang;
    private long futureEventId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberA = currentUserOf(memberAId);
        MemberCreateResponse sl = createMember(admin, "声部长", "secleader", section1, "SECTION_LEADER");
        secLeader = currentUserOf(sl.id());
        MemberCreateResponse tz = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        tuanzhang = currentUserOf(tz.id());
        futureEventId = event(startsInMinutes(60), 60);
    }

    @Test
    void dual_approval_writes_leave_attendance_on_final_approval() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        assertEquals(2, request.approvals().size()); // 排练 2 条待签

        // 第一方批：整单仍 PENDING，LEAVE 不落库
        leaveService.decide(request.approvals().get(0).id(),
                new DecideRequest("APPROVED", null), secLeader);
        assertEquals("PENDING", ownRequest().status());
        assertEquals(0, countAttendance());

        // 全部批 → attendance(LEAVE, marked_by=最终审批人=团长)
        leaveService.decide(request.approvals().get(1).id(),
                new DecideRequest("APPROVED", "同意"), tuanzhang);
        assertEquals("APPROVED", ownRequest().status());
        assertEquals("LEAVE", jdbc.queryForObject(
                "SELECT status FROM attendance WHERE event_id = ? AND member_id = ?",
                String.class, futureEventId, memberAId));
        assertEquals(tuanzhang.memberId(), jdbc.queryForObject(
                "SELECT marked_by FROM attendance WHERE event_id = ? AND member_id = ?",
                Long.class, futureEventId, memberAId));
    }

    @Test
    void any_rejection_rejects_whole_request_and_further_decide_40006() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        leaveService.decide(request.approvals().get(0).id(),
                new DecideRequest("REJECTED", "不给假"), secLeader);
        assertEquals("REJECTED", ownRequest().status());
        assertEquals(0, countAttendance());
        assertThrows(BusinessException.class, () -> leaveService.decide(
                request.approvals().get(1).id(), new DecideRequest("APPROVED", null), tuanzhang));
    }

    @Test
    void duplicate_apply_40004_then_reapply_allowed_after_rejection() {
        RequestView first = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        // 同人同场已有 PENDING → 40004（docs/05 并发与幂等）
        assertThrows(BusinessException.class,
                () -> leaveService.create(futureEventId, new CreateRequest("有事"), memberA));
        // 驳回第一单 → 重新申请放行
        leaveService.decide(first.approvals().get(0).id(),
                new DecideRequest("REJECTED", null), secLeader);
        assertTrue(leaveService.create(futureEventId, new CreateRequest("有事"), memberA).id() > 0);
    }

    @Test
    void withdraw_only_once_and_only_before_start() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        leaveService.cancel(request.id(), memberA);
        assertEquals("CANCELED", ownRequest().status());
        // 已撤回再撤 → 40006
        assertThrows(BusinessException.class, () -> leaveService.cancel(request.id(), memberA));

        // 开始后撤回 → 40005（docs/05：否则可用来逃避缺勤）
        RequestView second = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        jdbc.update("UPDATE event SET date = CURDATE(), start_time = '00:00:01' WHERE id = ?",
                second.eventId());
        assertThrows(BusinessException.class, () -> leaveService.cancel(second.id(), memberA));
    }

    @Test
    void apply_to_started_event_40005() {
        long startedEventId = event(startsInMinutes(-30), 60);
        assertThrows(BusinessException.class,
                () -> leaveService.create(startedEventId, new CreateRequest("有事"), memberA));
    }

    @Test
    void section_leader_cannot_self_approve_leader_signs_instead() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), secLeader);
        // 声部长审自己的 SECTION_LEADER 行 → 30001
        assertThrows(BusinessException.class, () -> leaveService.decide(
                request.approvals().get(0).id(), new DecideRequest("APPROVED", null), secLeader));
        // 团长直接签那一方（docs/05 边界情况），两条都过后整单通过
        leaveService.decide(request.approvals().get(0).id(),
                new DecideRequest("APPROVED", null), tuanzhang);
        leaveService.decide(request.approvals().get(1).id(),
                new DecideRequest("APPROVED", null), tuanzhang);
        assertEquals("APPROVED", leaveService
                .list("mine", null, futureEventId, 1, 20, secLeader).list().get(0).status());
    }

    @Test
    void needsMe_scope_filters_and_overdue_flag() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);

        // 团长的 needsMe 含该单（LEADER 行待他签）
        PageResult<RequestView> forLeader = leaveService
                .list("needsMe", "PENDING", null, 1, 20, tuanzhang);
        assertTrue(forLeader.list().stream().anyMatch(v -> v.id() == request.id()));
        // 声部长的 needsMe 不含（其 SECTION_LEADER 行虽 PENDING 但申请人非本声部——memberA 是本声部！
        // 声部长可签 SECTION_LEADER 行，故含）——改为验证指挥被强制只看自己
        PageResult<RequestView> forMemberA = leaveService
                .list("needsMe", "PENDING", null, 1, 20, memberA);
        assertTrue(forMemberA.list().stream().allMatch(v -> v.memberId() == memberAId));

        // 逾期：活动整体挪到昨天 → overdue 标记；LEAVE 永远没写入 → 缺勤靠推导（D9）
        jdbc.update("UPDATE event SET date = CURDATE() - INTERVAL 1 DAY, " +
                "start_time = '10:00:00', end_time = '12:00:00' WHERE id = ?", futureEventId);
        assertTrue(ownRequest().overdue());
        assertEquals(0, countAttendance());
    }

    @Test
    void leave_api_error_codes_via_mockmvc() throws Exception {
        String memberToken = loginToken("memberA", jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, memberAId));
        api(memberToken, "POST", "/api/events/" + futureEventId + "/leave-requests",
                "{\"reason\":\"x\"}").andExpect(status().isOk());
        // 重复申请 → 409 + 40004
        api(memberToken, "POST", "/api/events/" + futureEventId + "/leave-requests",
                "{\"reason\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40004));
        // 撤回接口非本人 → 30002
        String otherToken = loginToken("secleader", jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE account = 'secleader'", String.class));
        long requestId = ownRequest().id();
        api(otherToken, "POST", "/api/leave-requests/" + requestId + "/cancel", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
    }

    // ---------- 批准覆盖已有签到：留痕 + 当场提示，绝不许静默（docs/05 §5.5） ----------

    @Test
    void approval_over_existing_checkin_is_trailed_and_flagged() throws Exception {
        // 先代签（此刻还没有请假申请，所以不会撞上"有请假不代签"的闸门）
        api(adminToken(), "POST", "/api/events/" + futureEventId + "/attendance",
                "{\"memberId\":" + memberAId + "}").andExpect(status().isOk());

        RequestView request = leaveService.create(futureEventId, new CreateRequest("临时有事"), memberA);
        leaveService.decide(request.approvals().get(0).id(), new DecideRequest("APPROVED", null), secLeader);
        RequestView finalView = leaveService.decide(request.approvals().get(1).id(),
                new DecideRequest("APPROVED", null), tuanzhang);

        // ② 当场提示：出参要能区分出"这次覆盖了一条已有签到"（字段名由实现方定，docs/05 §5.6）
        assertTrue(finalView.attendanceOverridden());
        // ① 留痕：谁批的 + 改前 PRESENT → 改后 LEAVE，两个都得写全
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT operator_id, `before`, `after` FROM operation_log"
                        + " WHERE action = 'ATTENDANCE_OVERRIDE_BY_LEAVE'");
        assertEquals(tuanzhang.memberId(), ((Number) log.get("operator_id")).longValue(), "要记下是谁批的");
        assertTrue(log.get("before").toString().contains("PRESENT"), "改前值缺失");
        assertTrue(log.get("after").toString().contains("LEAVE"), "改后值缺失");
        assertEquals("LEAVE", jdbc.queryForObject(
                "SELECT status FROM attendance WHERE event_id = ? AND member_id = ?",
                String.class, futureEventId, memberAId));
    }

    @Test
    void approval_without_prior_checkin_is_not_flagged() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        leaveService.decide(request.approvals().get(0).id(), new DecideRequest("APPROVED", null), secLeader);
        RequestView finalView = leaveService.decide(request.approvals().get(1).id(),
                new DecideRequest("APPROVED", null), tuanzhang);

        // 没覆盖任何人，就不能无条件标 true（防"永远为 true"的假实现）
        assertFalse(finalView.attendanceOverridden());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ATTENDANCE_OVERRIDE_BY_LEAVE'",
                Integer.class));
    }

    // ---------- 可见范围：默认拒绝 + 白名单（docs/05 §5.6） ----------

    @Test
    void non_whitelist_roles_only_see_their_own_requests() {
        leaveService.create(futureEventId, new CreateRequest("有事"), memberA);
        CurrentUser honorary = honoraryOf(section1);

        // 荣誉团员：scope 传什么都不该看到别人的请假单（自己没提过 → 空）
        for (String scope : List.of("all", "needsMe", "乱值")) {
            PageResult<RequestView> r = leaveService.list(scope, null, null, 1, 20, honorary);
            assertTrue(r.list().isEmpty(), "scope=" + scope + " 不该看到别人的请假单");
        }
        // 团员传 all：只能看到自己那条
        PageResult<RequestView> asMember = leaveService.list("all", null, null, 1, 20, memberA);
        assertFalse(asMember.list().isEmpty());
        assertTrue(asMember.list().stream().allMatch(v -> v.memberId() == memberAId));
        // 白名单内的团长照旧看得到（改口径不能把管理侧改坏）
        PageResult<RequestView> asLeader = leaveService.list("all", null, null, 1, 20, tuanzhang);
        assertTrue(asLeader.list().stream().anyMatch(v -> v.memberId() == memberAId));
    }

    @Test
    void conductor_and_system_admin_cannot_apply_leave_30001() throws Exception {
        MemberCreateResponse c = createMember(admin, "指挥", "conductor", section1, "CONDUCTOR");
        String conductorToken = loginToken("conductor", c.initialPassword());
        // 不参与考勤的人也不参与请假（docs/13 不变式 14）
        api(conductorToken, "POST", "/api/events/" + futureEventId + "/leave-requests", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(adminToken(), "POST", "/api/events/" + futureEventId + "/leave-requests", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    // ---------- 理由必填（docs/05 §5.5，2026-09-25 改口径） ----------

    @Test
    void missing_or_blank_reason_is_rejected_10001() throws Exception {
        String memberToken = loginToken("memberA", jdbc.queryForObject(
                "SELECT initial_password FROM member WHERE id = ?", String.class, memberAId));

        // 缺 reason → 422 + 10001（不给它落库成 NULL 的机会）
        api(memberToken, "POST", "/api/events/" + futureEventId + "/leave-requests", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        // 纯空白也不算"填了"（@NotBlank 的语义就是这样）
        api(memberToken, "POST", "/api/events/" + futureEventId + "/leave-requests",
                "{\"reason\":\"   \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_request", Integer.class), "被拒的申请不该落库");

        // 填了理由照旧能提交
        api(memberToken, "POST", "/api/events/" + futureEventId + "/leave-requests",
                "{\"reason\":\"回家\"}").andExpect(status().isOk());
        assertEquals("回家", jdbc.queryForObject("SELECT reason FROM leave_request", String.class));
    }

    // ---------- 团长请假：换另一位在团团长签，只有一位时管理员兜底（docs/05 §5.5） ----------

    @Test
    void leader_leave_can_be_approved_by_another_leader() {
        CurrentUser leaderB = secondLeader();
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), tuanzhang);
        long leaderRowId = approvalOf(request, "LEADER").id();

        // 改前这里是一刀切拒绝（只有管理员能签）
        leaveService.decide(leaderRowId, new DecideRequest("APPROVED", null), leaderB);

        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT decision FROM leave_approval WHERE id = ?", String.class, leaderRowId));
    }

    @Test
    void leader_cannot_approve_own_leave_and_sole_leader_falls_back_to_admin() {
        // 夹具里只有 tuanzhang 一位在团团长 → 另一个能签的只能是管理员
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), tuanzhang);
        long leaderRowId = approvalOf(request, "LEADER").id();

        BusinessException self = assertThrows(BusinessException.class, () -> leaveService.decide(
                leaderRowId, new DecideRequest("APPROVED", null), tuanzhang));
        assertEquals(ErrorCode.NO_PERMISSION, self.getErrorCode(), "自己不能签自己的");

        leaveService.decide(leaderRowId, new DecideRequest("APPROVED", null), admin);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT decision FROM leave_approval WHERE id = ?", String.class, leaderRowId));
    }

    @Test
    void member_cannot_approve_leaders_leave_30001() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest("有事"), tuanzhang);
        long leaderRowId = approvalOf(request, "LEADER").id();

        BusinessException denied = assertThrows(BusinessException.class, () -> leaveService.decide(
                leaderRowId, new DecideRequest("APPROVED", null), memberA));
        assertEquals(ErrorCode.NO_PERMISSION, denied.getErrorCode());
    }

    /** 造第二位在团团长（LEADER / ACTIVE）。 */
    private CurrentUser secondLeader() {
        MemberCreateResponse b = createMember(admin, "团长乙", "tuanzhang2", null, "LEADER");
        return currentUserOf(b.id());
    }

    private ApprovalView approvalOf(RequestView request, String requiredRole) {
        return request.approvals().stream()
                .filter(a -> requiredRole.equals(a.requiredRole()))
                .findFirst().orElseThrow(() -> new AssertionError("没有 " + requiredRole + " 待签行"));
    }

    private CurrentUser honoraryOf(long sectionId) {
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", sectionId, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null, LocalDate.now().plusYears(2), null,
                null, null, null, null, null), admin);
        return currentUserOf(h.id());
    }

    private RequestView ownRequest() {
        return leaveService.list("mine", null, futureEventId, 1, 20, memberA).list().get(0);
    }

    private LocalDateTime startsInMinutes(long minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private long event(LocalDateTime start, long durationMinutes) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, durationMinutes), "排练厅", null, null), admin).id();
    }

    private int countAttendance() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class);
    }
}
