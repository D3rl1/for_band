package com.forband.auth;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
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

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** #249：只验证不依赖待核定历史区间模型的资格、会话和权限，不声称历史统计完成。 */
class PausedMemberTests extends IntegrationTestBase {
    @Autowired private LeaveService leaveService;
    @Autowired private PermissionService permissionService;
    private CurrentUser admin;
    private long section;
    private MemberCreateResponse member;
    private String token;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section = seedSection("验证声部");
        member = createMember(admin, "休团验证", "paused249", section, "MEMBER");
        token = loginToken("paused249", member.initialPassword());
    }

    @Test
    void pause_and_resume_keep_old_tokens_and_dates_and_allow_fresh_login() throws Exception {
        LocalDate joined = LocalDate.now().minusYears(1);
        jdbc.update("UPDATE member SET joined_at = ? WHERE id = ?", joined, member.id());
        for (String state : new String[]{"PAUSED", "ACTIVE", "PAUSED", "ACTIVE"}) {
            setMemberStatus(admin, member.id(), state);
            api(token, "GET", "/api/auth/me", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value(state))
                    .andExpect(jsonPath("$.data.role").value("MEMBER"))
                    .andExpect(jsonPath("$.data.sectionId").value((int) section));
            String fresh = loginToken("paused249", member.initialPassword());
            api(fresh, "GET", "/api/auth/me", null).andExpect(status().isOk());
            assertEquals(joined, jdbc.queryForObject("SELECT joined_at FROM member WHERE id = ?", LocalDate.class, member.id()));
            assertNull(jdbc.queryForObject("SELECT left_at FROM member WHERE id = ?", LocalDate.class, member.id()));
        }
    }

    @Test
    void paused_old_session_cannot_check_in_be_marked_or_request_leave_then_resume_uses_latest_status() throws Exception {
        long event = futureEvent();
        CurrentUser cached = currentUserOf(member.id());
        setMemberStatus(admin, member.id(), "PAUSED");
        api(token, "POST", "/api/events/" + event + "/attendance", "{}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(10001));
        api(adminToken(), "POST", "/api/events/" + event + "/attendance", "{\"memberId\":" + member.id() + "}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(10001));
        api(token, "POST", "/api/events/" + event + "/leave-requests", "{\"reason\":\"不应申请\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        assertEquals(ErrorCode.NO_PERMISSION, assertThrows(BusinessException.class,
                () -> leaveService.create(event, new CreateRequest("缓存身份"), cached)).getErrorCode());
        assertEquals(0, count("attendance"));
        assertEquals(0, count("leave_request"));
        setMemberStatus(admin, member.id(), "ACTIVE");
        api(token, "POST", "/api/events/" + event + "/leave-requests", "{\"reason\":\"恢复后正常申请\"}")
                .andExpect(status().isOk());
    }

    @Test
    void pending_leave_is_retained_but_final_approval_while_paused_does_not_write_attendance() throws Exception {
        RequestView request = leaveService.create(futureEvent(), new CreateRequest("先前申请"), currentUserOf(member.id()));
        setMemberStatus(admin, member.id(), "PAUSED");
        api(token, "POST", "/api/leave-requests/" + request.id() + "/cancel", null)
                .andExpect(status().isForbidden());
        for (var approval : request.approvals()) {
            leaveService.decide(approval.id(), new DecideRequest("APPROVED", null), admin);
        }
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM leave_request WHERE id = ?", String.class, request.id()));
        assertEquals(2, count("leave_approval"));
        assertEquals(0, count("attendance"));
    }

    @Test
    void paused_management_roles_still_approve_others_without_self_approval_or_cross_section_access() throws Exception {
        var sl = createMember(admin, "休团声部长", "sl249", section, "SECTION_LEADER");
        var leader = createMember(admin, "休团团长", "leader249", section, "LEADER");
        String slToken = loginToken("sl249", sl.initialPassword());
        String leaderToken = loginToken("leader249", leader.initialPassword());
        RequestView own = leaveService.create(futureEvent(), new CreateRequest("团长原申请"), currentUserOf(leader.id()));
        RequestView other = leaveService.create(futureEvent(), new CreateRequest("他人申请"), currentUserOf(member.id()));
        setMemberStatus(admin, sl.id(), "PAUSED");
        setMemberStatus(admin, leader.id(), "PAUSED");
        for (var approval : other.approvals()) {
            api(approval.requiredRole().equals("LEADER") ? leaderToken : slToken,
                    "POST", "/api/leave-approvals/" + approval.id() + "/decide", "{\"decision\":\"APPROVED\"}")
                    .andExpect(status().isOk());
        }
        long ownLeaderRow = own.approvals().stream().filter(a -> a.requiredRole().equals("LEADER")).findFirst().orElseThrow().id();
        api(leaderToken, "POST", "/api/leave-approvals/" + ownLeaderRow + "/decide", "{\"decision\":\"APPROVED\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        long otherSection = seedSection("另一声部");
        api(slToken, "POST", "/api/recordings/assignments", "{\"sectionId\":" + otherSection + ",\"title\":\"越权\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30003));
    }

    @Test
    void paused_section_leader_still_occupies_the_office() {
        var sl = createMember(admin, "仍任职声部长", "office249", section, "SECTION_LEADER");
        setMemberStatus(admin, sl.id(), "PAUSED");
        assertEquals(ErrorCode.SECTION_LEADER_EXISTS, assertThrows(BusinessException.class,
                () -> createMember(admin, "不能重复任命", "duplicate249", section, "SECTION_LEADER")).getErrorCode());
    }

    @Test
    void paused_member_retains_non_attendance_writes_but_never_gains_admin_permissions() throws Exception {
        setMemberStatus(admin, member.id(), "PAUSED");
        api(token, "GET", "/api/pieces", null).andExpect(status().isOk());
        api(token, "PUT", "/api/me/profile", "{\"intro\":\"休团仍可更新\"}").andExpect(status().isOk());
        api(token, "POST", "/api/issues", "{\"title\":\"休团讨论\",\"content\":\"仍可参与\",\"deviceInfo\":\"隔离测试\"}").andExpect(status().isOk());
        api(token, "POST", "/api/admin/members", "{\"name\":\"越权\",\"account\":\"bad249\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        memberService.revokeSessions(member.id());
        api(token, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        api(loginToken("paused249", member.initialPassword()), "GET", "/api/auth/me", null).andExpect(status().isOk());
    }

    @Test
    void left_graduated_and_invalid_honorary_fail_closed_even_for_an_old_token() throws Exception {
        for (String state : new String[]{"LEFT", "GRADUATED"}) {
            jdbc.update("UPDATE member SET status = ?, role = 'MEMBER', honorary_until = NULL WHERE id = ?", state, member.id());
            api(token, "GET", "/api/auth/me", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(20003));
            api(null, "POST", "/api/auth/login", "{\"account\":\"paused249\",\"password\":\"" + member.initialPassword() + "\"}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(20003));
            jdbc.update("UPDATE member SET status = 'ACTIVE' WHERE id = ?", member.id());
            token = loginToken("paused249", member.initialPassword());
        }
        jdbc.update("UPDATE member SET role = 'HONORARY', status = 'GRADUATED', honorary_until = NULL WHERE id = ?", member.id());
        assertFalse(permissionService.canLogin(memberEntity()));
        jdbc.update("UPDATE member SET honorary_until = ? WHERE id = ?", LocalDate.now().minusDays(1), member.id());
        assertFalse(permissionService.canLogin(memberEntity()));
        jdbc.update("UPDATE member SET status = 'PAUSED', honorary_until = ? WHERE id = ?", LocalDate.now().plusYears(1), member.id());
        api(token, "GET", "/api/auth/me", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(20003));
    }

    private com.forband.member.Member memberEntity() {
        var m = new com.forband.member.Member();
        m.setRole("HONORARY");
        m.setStatus(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.id()));
        m.setHonoraryUntil(jdbc.queryForObject("SELECT honorary_until FROM member WHERE id = ?", LocalDate.class, member.id()));
        return m;
    }

    private long futureEvent() {
        return eventService.create(new EventCreateRequest("REHEARSAL", "资格验证排练", LocalDate.now().plusDays(2),
                LocalTime.of(19, 0), LocalTime.of(21, 0), "测试排练厅", null, null), admin).id();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
