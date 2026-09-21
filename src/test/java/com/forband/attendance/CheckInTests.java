package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/05 §5.3 签到规则：窗口、幂等（D9）、代签留痕。 */
class CheckInTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;
    private String memberAToken;
    private long leaderId;
    private String leaderToken;

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
        long eventId = event(startsInMinutes(-180), 60); // 已结束超 2 小时
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
        long eventId = event(startsInMinutes(10), 60);
        api(leaderToken, "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedByName").value("声部长"));

        assertEquals(leaderId, markedByOf(eventId, memberAId)); // 代签：marked_by = 操作人
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
