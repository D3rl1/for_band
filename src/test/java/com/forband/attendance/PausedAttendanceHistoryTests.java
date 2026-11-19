package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.event.Event;
import com.forband.event.EventMapper;
import com.forband.event.dto.EventCreateRequest;
import com.forband.leave.LeaveService;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.member.MemberMapper;
import com.forband.member.MemberStatusHistoryService;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** #249：真实测试库内的已知日期夹具，绝不作为旧业务数据的猜填迁移。 */
class PausedAttendanceHistoryTests extends IntegrationTestBase {
    @Autowired private MemberStatusHistoryService history;
    @Autowired private MemberMapper members;
    @Autowired private EventMapper events;
    @Autowired private ExpectedRosterResolver roster;
    @Autowired private AttendanceService attendance;
    @Autowired private LeaveService leave;
    @Autowired private PlatformTransactionManager transactions;
    private CurrentUser admin;
    private long memberId;
    private long section;
    private LocalDate today;
    private LocalDate joined;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section = seedSection("区间测试声部");
        memberId = createMember(admin, "区间团员", "history249", section, "MEMBER").id();
        today = LocalDate.now(MemberStatusHistoryService.ZONE);
        joined = today.minusDays(30);
        jdbc.update("UPDATE member SET joined_at = ? WHERE id = ?", joined, memberId);
        jdbc.update("DELETE FROM member_status_history WHERE member_id = ?", memberId);
        history.record(memberId, "ACTIVE", "ACTIVE", joined, admin.memberId());
    }

    @Test
    void shanghai_midnight_boundary_is_independent_of_the_host_timezone() {
        assertEquals(LocalDate.of(2026, 11, 5), MemberStatusHistoryService.effectiveDate(Instant.parse("2026-11-05T15:59:59Z")));
        assertEquals(LocalDate.of(2026, 11, 6), MemberStatusHistoryService.effectiveDate(Instant.parse("2026-11-05T16:00:00Z")));
    }

    @Test
    void multiple_pause_resume_intervals_keep_earlier_attendance_and_do_not_backfill_absence() {
        transition("ACTIVE", "PAUSED", today.minusDays(20));
        transition("PAUSED", "ACTIVE", today.minusDays(15));
        transition("ACTIVE", "PAUSED", today.minusDays(10));
        transition("PAUSED", "ACTIVE", today.minusDays(5));
        for (String kind : new String[]{"REHEARSAL", "DRESS_REHEARSAL", "SECTION_CLASS"}) {
            assertTrue(roster.isExpected(event(today.minusDays(21), kind), members.selectById(memberId)));
            assertFalse(roster.isExpected(event(today.minusDays(20), kind), members.selectById(memberId)));
            assertFalse(roster.isExpected(event(today.minusDays(16), kind), members.selectById(memberId)));
            assertTrue(roster.isExpected(event(today.minusDays(15), kind), members.selectById(memberId)));
            assertFalse(roster.isExpected(event(today.minusDays(10), kind), members.selectById(memberId)));
            assertTrue(roster.isExpected(event(today.minusDays(5), kind), members.selectById(memberId)));
        }
    }

    @Test
    void same_day_last_transition_controls_the_whole_day_without_deleting_existing_rows() {
        Event e = event(today, "REHEARSAL");
        row(e, "PRESENT");
        setMemberStatus(admin, memberId, "PAUSED");
        assertFalse(roster.isExpected(e, members.selectById(memberId)));
        assertNull(roster.mineAt(e, members.selectById(memberId), "PRESENT"));
        setMemberStatus(admin, memberId, "ACTIVE");
        assertTrue(roster.isExpected(e, members.selectById(memberId)));
        setMemberStatus(admin, memberId, "PAUSED");
        assertFalse(roster.isExpected(e, members.selectById(memberId)));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM member_status_history WHERE member_id = ?", Integer.class, memberId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attendance WHERE member_id = ?", Integer.class, memberId));
        assertEquals(0, attendance.eventAttendance(e.getId(), admin).present());
    }

    @Test
    void event_personal_aggregate_sessions_and_csv_all_ignore_non_expected_existing_records() throws Exception {
        Event before = event(today.minusDays(21), "REHEARSAL");
        Event paused = event(today.minusDays(19), "REHEARSAL");
        Event restored = event(today.minusDays(14), "REHEARSAL");
        row(before, "PRESENT"); row(paused, "LEAVE"); row(restored, "PARTIAL");
        transition("ACTIVE", "PAUSED", today.minusDays(20));
        transition("PAUSED", "ACTIVE", today.minusDays(15));
        var single = attendance.eventAttendance(paused.getId(), admin);
        assertEquals(0, single.expected()); assertEquals(0, single.present()); assertEquals(0, single.leave());
        assertTrue(single.rows().isEmpty());
        var personal = attendance.memberSummary(memberId, joined, today, admin).rehearsal();
        assertEquals(2, personal.expected()); assertEquals(2, personal.present());
        assertEquals(0, personal.leave()); assertEquals(0, personal.absent());
        var aggregate = attendance.aggregate(joined, today, admin).kinds().stream()
                .filter(k -> k.kind().equals("REHEARSAL")).findFirst().orElseThrow();
        assertEquals(2, aggregate.expected()); assertEquals(2, aggregate.present()); assertEquals(0, aggregate.leave());
        var sessions = attendance.sessions(1, 20, admin).list();
        var excluded = sessions.stream().filter(s -> s.eventId() == paused.getId()).findFirst().orElseThrow();
        assertEquals(0, excluded.expected()); assertEquals(0, excluded.leave());
        String csv = attendance.exportCsv(joined, today, "REHEARSAL");
        assertFalse(csv.contains(paused.getDate().toString()));
        assertTrue(csv.contains(before.getDate().toString()));
        assertTrue(csv.contains(restored.getDate().toString()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attendance WHERE member_id = ?", Integer.class, memberId));
        api(adminToken(), "POST", "/api/events/" + paused.getId() + "/attendance", "{\"memberId\":" + memberId + "}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void currently_paused_keeps_active_history_in_the_personal_denominator() {
        Event past = event(today.minusDays(1), "REHEARSAL"); row(past, "PRESENT");
        Event now = event(today, "REHEARSAL");
        setMemberStatus(admin, memberId, "PAUSED");
        var summary = attendance.memberSummary(memberId, joined, today, admin).rehearsal();
        assertEquals(1, summary.expected()); assertEquals(1, summary.present());
        assertEquals(0, attendance.eventAttendance(now.getId(), admin).expected());
    }

    @Test
    void final_approval_after_resume_cannot_backfill_leave_for_a_paused_event_day() {
        long eventId = event(LocalDate.now().plusDays(2), "REHEARSAL").getId();
        var request = leave.create(eventId, new CreateRequest("休团前申请"), currentUserOf(memberId));
        LocalDate day = today.minusDays(10);
        jdbc.update("UPDATE event SET date = ? WHERE id = ?", day, eventId);
        transition("ACTIVE", "PAUSED", day);
        transition("PAUSED", "ACTIVE", day.plusDays(1));
        for (var approval : request.approvals()) leave.decide(approval.id(), new DecideRequest("APPROVED", null), admin);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attendance WHERE event_id = ?", Integer.class, eventId));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM leave_request WHERE id = ?", String.class, request.id()));
    }

    @Test
    void failed_transaction_rolls_back_both_status_and_history() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM member_status_history", Integer.class);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            setMemberStatus(admin, memberId, "PAUSED");
            throw new IllegalStateException("测试强制回滚");
        }));
        assertEquals("ACTIVE", members.selectById(memberId).getStatus());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM member_status_history", Integer.class));
    }

    @Test
    void old_members_do_not_receive_invented_pause_dates_or_rewrite_the_previous_legacy_result() {
        jdbc.update("DELETE FROM member_status_history WHERE member_id = ?", memberId);
        Event old = event(today.minusDays(8), "REHEARSAL");
        setMemberStatus(admin, memberId, "PAUSED");
        assertTrue(roster.isExpected(old, members.selectById(memberId)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member_status_history WHERE member_id = ?", Integer.class, memberId));
        assertEquals(today, jdbc.queryForObject("SELECT effective_date FROM member_status_history WHERE member_id = ?", LocalDate.class, memberId));
    }

    private Event event(LocalDate date, String kind) {
        long id = eventService.create(new EventCreateRequest(kind, "区间验证活动", date, LocalTime.of(19, 0),
                LocalTime.of(21, 0), "测试排练厅", kind.equals("SECTION_CLASS") ? section : null, null), admin).id();
        return events.selectById(id);
    }

    private void transition(String before, String after, LocalDate date) {
        history.record(memberId, before, after, date, admin.memberId());
        jdbc.update("UPDATE member SET status = ? WHERE id = ?", after, memberId);
    }

    private void row(Event event, String state) {
        jdbc.update("INSERT INTO attendance (event_id,member_id,status,marked_at,marked_by) VALUES (?,?,?,NOW(),?)",
                event.getId(), memberId, state, admin.memberId());
    }
}
