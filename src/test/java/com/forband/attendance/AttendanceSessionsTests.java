package com.forband.attendance;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「历次场次」列表（`docs/05` §5.4 的主视图改版，`交接单 #110`）。
 * <p>
 * ⭐ 钉住四件事：**管理层拿得到每场的四个数**、**普通团员拿到的是 `null` 只留 `mine`**、
 * **声部长只看得到本声部的声部课**、**按日期倒序**。
 * ⚠️ 它**不许改应到场次的口径**——那条由既有的 `CheckInTests` / `SectionChangeAttendanceTests` 守着。
 */
class AttendanceSessionsTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long memberAId;
    private String memberAToken;
    private String sectionLeaderAToken;
    private long rehearsalId;
    private long classAId;
    private long classBId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        long sectionA = seedSection("胡琴声部");
        long sectionB = seedSection("琵琶声部");
        MemberCreateResponse a = createMember(admin, "甲团员", "memberA", sectionA, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "乙团员", "memberB", sectionB, "MEMBER");
        MemberCreateResponse la = createMember(admin, "甲部长", "leadA", sectionA, "SECTION_LEADER");
        sectionLeaderAToken = loginToken("leadA", la.initialPassword());
        createMember(admin, "乙部长", "leadB", sectionB, "SECTION_LEADER");
        // ⚠️ **入团日期要回拨**：默认 joined_at = 今天，而"应到"要 `joined_at <= 活动日期`
        //    ⇒ 不回拨的话这些过去的活动里**一个人都不算应到**，四个数全是 0（看着像实现坏了）。
        jdbc.update("UPDATE member SET joined_at = CURDATE() - INTERVAL 30 DAY");

        // 三场（日期逐天靠近今天，好验"倒序"）：团级排练 + 两个声部各自的声部课
        rehearsalId = event("REHEARSAL", "周五排练", LocalDate.now().minusDays(3), null);
        classAId = event("SECTION_CLASS", "甲声部课", LocalDate.now().minusDays(2), sectionA);
        classBId = event("SECTION_CLASS", "乙声部课", LocalDate.now().minusDays(1), sectionB);

        // 甲在排练上签过到（PROVES `mine = PRESENT`）；声部课他没签 ⇒ 应到没签 = 缺勤
        jdbc.update("INSERT INTO attendance (event_id, member_id, status, marked_at, marked_by) "
                + "VALUES (?, ?, 'PRESENT', NOW(), ?)", rehearsalId, memberAId, memberAId);
    }

    private long event(String kind, String title, LocalDate date, Long sectionId) {
        return eventService.create(new EventCreateRequest(kind, title, date,
                LocalTime.of(18, 30), LocalTime.of(20, 30), "排练厅", sectionId, null), admin).id();
    }

    @Test
    void management_gets_per_session_counts_in_descending_date_order() throws Exception {
        api(adminToken(), "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                // 倒序：昨天（乙声部课）→ 前天（甲声部课）→ 大前天（排练）
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) classBId))
                .andExpect(jsonPath("$.data.list[1].eventId").value((int) classAId))
                .andExpect(jsonPath("$.data.list[2].eventId").value((int) rehearsalId))
                // 排练：应到 4（两个声部各 1 部长 + 1 团员，团级活动都算应到）、出勤 1、缺勤 3
                .andExpect(jsonPath("$.data.list[2].expected").value(4))
                .andExpect(jsonPath("$.data.list[2].present").value(1))
                .andExpect(jsonPath("$.data.list[2].leave").value(0))
                .andExpect(jsonPath("$.data.list[2].absent").value(3));
    }

    @Test
    void partial_counts_as_present_and_explicit_absent_passes_through() throws Exception {
        jdbc.update("UPDATE attendance SET status = 'PARTIAL' WHERE event_id = ? AND member_id = ?",
                rehearsalId, memberAId);
        jdbc.update("INSERT INTO attendance (event_id, member_id, status, marked_at, marked_by) "
                + "VALUES (?, ?, 'ABSENT', NOW(), ?)", classAId, memberAId, memberAId);

        // 管理视图：PARTIAL 计入出勤（排练仍 出勤 1 / 缺勤 3），ABSENT 行不算任何加分项
        api(adminToken(), "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].eventId").value((int) classAId))
                .andExpect(jsonPath("$.data.list[1].expected").value(2))
                .andExpect(jsonPath("$.data.list[1].present").value(0))
                .andExpect(jsonPath("$.data.list[1].leave").value(0))
                .andExpect(jsonPath("$.data.list[1].absent").value(2))
                .andExpect(jsonPath("$.data.list[2].eventId").value((int) rehearsalId))
                .andExpect(jsonPath("$.data.list[2].present").value(1))
                .andExpect(jsonPath("$.data.list[2].leave").value(0))
                .andExpect(jsonPath("$.data.list[2].absent").value(3));

        // 团员自己那一格：四态**原样下发**（前端据此显示「遗憾离场」「缺勤」）
        api(memberAToken, "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(jsonPath("$.data.list[0].mine").value("ABSENT"))
                .andExpect(jsonPath("$.data.list[1].mine").value("PARTIAL"));
    }

    @Test
    void ordinary_member_gets_only_their_own_status_and_no_counts() throws Exception {
        // ⚠️ 团员的范围**只有两场**：本声部的声部课 + 团级排练（乙声部的声部课不在他的列表里）。
        api(memberAToken, "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                // ⚠️ 乙声部（别人的声部课）**不在他的列表里** ⇒ 第 1 行是甲声部课
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) classAId))
                // ⭐ 五个汇总数**一律裁成 null**（他人考勤不可见，docs/02 §2.3）
                .andExpect(jsonPath("$.data.list[0].expected").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].present").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].rate").doesNotExist())
                // ⭐ 自己那一格照给：甲声部课**应到没签** ⇒ 缺勤；排练上签过到 ⇒ 出勤
                .andExpect(jsonPath("$.data.list[0].mine").value("ABSENT"))
                .andExpect(jsonPath("$.data.list[1].mine").value("PRESENT"));
    }

    @Test
    void section_leader_sees_only_own_sections_class() throws Exception {
        // 甲部长：看得到团级排练 + 本声部声部课；**看不到乙声部的声部课**
        api(sectionLeaderAToken, "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) classAId))
                .andExpect(jsonPath("$.data.list[1].eventId").value((int) rehearsalId));
    }

    @Test
    void paging_is_stable() throws Exception {
        api(adminToken(), "GET", "/api/attendance/sessions?page=2&size=2", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) rehearsalId));
    }

    @Test
    void canceled_events_are_not_listed() throws Exception {
        long extra = event("REHEARSAL", "取消掉的", LocalDate.now(), null);
        api(adminToken(), "POST", "/api/events/" + extra + "/cancel", null).andExpect(status().isOk());
        api(adminToken(), "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(jsonPath("$.data.total").value(3));   // 还是那三场
    }

    @Test
    void nobody_is_expected_before_they_joined() throws Exception {
        // 把甲的入团日期推到"所有活动之后" ⇒ 甲声部课不再是他的应到
        // ⇒ `mine` 是 **null**（"没轮到我"），**不是** "ABSENT"（"缺勤"）——这两者必须分得开。
        jdbc.update("UPDATE member SET joined_at = CURDATE() + INTERVAL 1 DAY WHERE id = ?", memberAId);
        api(memberAToken, "GET", "/api/attendance/sessions?size=50", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].eventId").value((int) classAId))
                .andExpect(jsonPath("$.data.list[0].mine").doesNotExist());
    }
}
