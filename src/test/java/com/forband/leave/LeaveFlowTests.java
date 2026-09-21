package com.forband.leave;

import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.PageResult;
import com.forband.event.dto.EventCreateRequest;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.leave.dto.LeaveDtos.RequestView;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

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
        RequestView request = leaveService.create(futureEventId, new CreateRequest(null), memberA);
        leaveService.decide(request.approvals().get(0).id(),
                new DecideRequest("REJECTED", "不给假"), secLeader);
        assertEquals("REJECTED", ownRequest().status());
        assertEquals(0, countAttendance());
        assertThrows(BusinessException.class, () -> leaveService.decide(
                request.approvals().get(1).id(), new DecideRequest("APPROVED", null), tuanzhang));
    }

    @Test
    void duplicate_apply_40004_then_reapply_allowed_after_rejection() {
        RequestView first = leaveService.create(futureEventId, new CreateRequest(null), memberA);
        // 同人同场已有 PENDING → 40004（docs/05 并发与幂等）
        assertThrows(BusinessException.class,
                () -> leaveService.create(futureEventId, new CreateRequest(null), memberA));
        // 驳回第一单 → 重新申请放行
        leaveService.decide(first.approvals().get(0).id(),
                new DecideRequest("REJECTED", null), secLeader);
        assertTrue(leaveService.create(futureEventId, new CreateRequest(null), memberA).id() > 0);
    }

    @Test
    void withdraw_only_once_and_only_before_start() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest(null), memberA);
        leaveService.cancel(request.id(), memberA);
        assertEquals("CANCELED", ownRequest().status());
        // 已撤回再撤 → 40006
        assertThrows(BusinessException.class, () -> leaveService.cancel(request.id(), memberA));

        // 开始后撤回 → 40005（docs/05：否则可用来逃避缺勤）
        RequestView second = leaveService.create(futureEventId, new CreateRequest(null), memberA);
        jdbc.update("UPDATE event SET date = CURDATE(), start_time = '00:00:01' WHERE id = ?",
                second.eventId());
        assertThrows(BusinessException.class, () -> leaveService.cancel(second.id(), memberA));
    }

    @Test
    void apply_to_started_event_40005() {
        long startedEventId = event(startsInMinutes(-30), 60);
        assertThrows(BusinessException.class,
                () -> leaveService.create(startedEventId, new CreateRequest(null), memberA));
    }

    @Test
    void section_leader_cannot_self_approve_leader_signs_instead() {
        RequestView request = leaveService.create(futureEventId, new CreateRequest(null), secLeader);
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
        RequestView request = leaveService.create(futureEventId, new CreateRequest(null), memberA);

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

    private RequestView ownRequest() {
        return leaveService.list("mine", null, futureEventId, 1, 20, memberA).list().get(0);
    }

    private LocalDateTime startsInMinutes(long minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private long event(LocalDateTime start, long durationMinutes) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                start.plusMinutes(durationMinutes).toLocalTime(), "排练厅", null, null), admin).id();
    }

    private int countAttendance() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class);
    }
}
