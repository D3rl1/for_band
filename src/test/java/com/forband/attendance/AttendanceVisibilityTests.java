package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * #223（`docs/02` §2.3 / §2.4）：考勤可见性必须是**显式白名单**，不许用「非 MEMBER / 非 HONORARY」
 * 反向推导——那等于给未来新角色默认放行。
 * <p>
 * 口径（`交接单 #223` 验收 ① ② ③）：MEMBER / HONORARY 只看自己；ADMIN / LEADER / CONDUCTOR /
 * SECTION_LEADER 可看全团；**其余角色默认拒绝**（枚举之外的"新角色"由 `Switch` 的 `default` 兜住）。
 * 前端 `stats.html` 也不再比角色，改用 `/api/auth/me` 下发的 `canViewAllAttendance`。
 */
class AttendanceVisibilityTests extends IntegrationTestBase {

    @Autowired
    private PermissionService permissionService;

    private CurrentUser admin;
    private long section1;
    private long eventId;

    private long memberAId;
    private String memberAToken;
    private String honoraryToken;
    private String sectionLeaderToken;
    private String leaderToken;
    private String conductorToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");

        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        createMember(admin, "团员乙", "memberB", section1, "MEMBER");

        MemberCreateResponse sl = createMember(admin, "声部长", "secleader", section1, "SECTION_LEADER");
        sectionLeaderToken = loginToken("secleader", sl.initialPassword());
        MemberCreateResponse leader = createMember(admin, "团长", "tuanzhang", section1, "LEADER");
        leaderToken = loginToken("tuanzhang", leader.initialPassword());
        // 指挥是外聘老师：不进应到名单，但可看全团考勤
        MemberCreateResponse conductor = createMember(admin, "指挥", "zhihui", section1, "CONDUCTOR");
        conductorToken = loginToken("zhihui", conductor.initialPassword());

        // 荣誉团员不能走建号入口（业务规则：只能由已毕业成员「改角色」授予），
        // 所以先按 MEMBER 建号，再直接把身份改成「已毕业 + 荣誉 + 有效期未过」（docs/01 §1.4 的登录条件）
        MemberCreateResponse honorary = createMember(admin, "荣誉团员", "honor", null, "MEMBER");
        jdbc.update("UPDATE member SET role = 'HONORARY', status = 'GRADUATED', "
                + "honorary_until = DATE_ADD(CURDATE(), INTERVAL 30 DAY) WHERE id = ?", honorary.id());
        honoraryToken = loginToken("honor", honorary.initialPassword());

        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(19).withMinute(0);
        eventId = eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, 120), "排练厅", null, null), admin).id();
    }

    @Test
    void attendance_detail_only_self_for_member_and_honorary() throws Exception {
        // 应到名单 = 团员甲 / 团员乙 / 声部长 / 团长（指挥、荣誉、管理员不进名单）
        api(memberAToken, "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].memberId").value((int) memberAId));

        // 荣誉团员不在应到名单里 ⇒ 一行也看不到；关键是**不能看到别人**（不是只把别人裁掉）
        api(honoraryToken, "GET", "/api/events/" + eventId + "/attendance", null)
                .andExpect(jsonPath("$.data.rows.length()").value(0));
    }

    @Test
    void attendance_detail_full_roster_for_management_whitelist() throws Exception {
        for (String token : List.of(adminToken(), leaderToken, conductorToken, sectionLeaderToken)) {
            api(token, "GET", "/api/events/" + eventId + "/attendance", null)
                    .andExpect(jsonPath("$.data.rows.length()").value(4));
        }
    }

    @Test
    void me_exposes_can_view_all_attendance_capability() throws Exception {
        api(memberAToken, "GET", "/api/auth/me", null)
                .andExpect(jsonPath("$.data.canViewAllAttendance").value(false));
        api(honoraryToken, "GET", "/api/auth/me", null)
                .andExpect(jsonPath("$.data.canViewAllAttendance").value(false));
        for (String token : List.of(adminToken(), leaderToken, conductorToken, sectionLeaderToken)) {
            api(token, "GET", "/api/auth/me", null)
                    .andExpect(jsonPath("$.data.canViewAllAttendance").value(true));
        }
    }

    @Test
    void capability_is_a_whitelist_and_defaults_to_deny() {
        for (Role role : Role.values()) {
            boolean expected = role == Role.ADMIN || role == Role.LEADER
                    || role == Role.CONDUCTOR || role == Role.SECTION_LEADER;
            CurrentUser user = new CurrentUser(1, "谁", role, null, false);
            if (expected) {
                assertTrue(permissionService.canViewAllAttendance(user), role + " 应可看全团考勤");
            } else {
                assertFalse(permissionService.canViewAllAttendance(user), role + " 不应看全团考勤");
            }
        }
    }

}
