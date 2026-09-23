package com.forband.auth;

import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/02 权限模型：默认拒绝、401/403 区分、横向越权。 */
class PermissionTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long section2;
    private long memberAId;
    private String memberAToken;
    private long leaderOfSec2Id;
    private String leaderOfSec2Token;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        section2 = seedSection("弹拨声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse leader = createMember(admin, "声部长乙", "leaderB", section2, "SECTION_LEADER");
        leaderOfSec2Id = leader.id();
        leaderOfSec2Token = loginToken("leaderB", leader.initialPassword());
    }

    @Test
    void no_token_or_bad_token_401_20002() throws Exception {
        api(null, "GET", "/api/sections", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
        api("garbage-token", "GET", "/api/sections", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void member_hitting_admin_endpoint_403_30001() throws Exception {
        api(memberAToken, "POST", "/api/admin/members",
                "{\"name\":\"x\",\"account\":\"x1\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void member_reading_other_summary_403_30002() throws Exception {
        api(memberAToken, "GET", "/api/members/" + leaderOfSec2Id + "/attendance-summary", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30002));
    }

    @Test
    void section_leader_cannot_mark_for_others_403_30001() throws Exception {
        // 代签收窄到管理员 + 团长（docs/02 §2.3）：声部长**根本不能代签**了——
        // 所以这里是 30001（没有权限），不再是过去那条"跨声部"的 30003
        long eventId = openRehearsal();
        api(leaderOfSec2Token, "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + memberAId + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void member_cannot_mark_for_others_403_30001() throws Exception {
        long eventId = openRehearsal();
        api(memberAToken, "POST", "/api/events/" + eventId + "/attendance",
                "{\"memberId\":" + leaderOfSec2Id + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void http_401_and_403_strictly_distinguished() throws Exception {
        int noAuth = api(null, "GET", "/api/sections", null).andReturn().getResponse().getStatus();
        int noPermission = api(memberAToken, "POST", "/api/admin/members",
                "{\"name\":\"x\",\"account\":\"x1\"}").andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertEquals(401, noAuth);
        org.junit.jupiter.api.Assertions.assertEquals(403, noPermission);
    }

    private long openRehearsal() {
        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        com.forband.event.dto.EventCreateRequest request = new com.forband.event.dto.EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                start.plusHours(1).toLocalTime(), "排练厅", null, null);
        return eventService.create(request, admin).id();
    }
}
