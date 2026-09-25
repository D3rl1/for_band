package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `GET /api/birthdays/today`（`docs/03` §3.3 登记 + `docs/14` §14.6.4「生日通告」）。
 * <p>
 * 钉住四件事：**寿星口径**（ACTIVE 且排除 is_system）、**不下发 `birthday`**、
 * 没有寿星返回空数组、**登录即可**（含荣誉团员）。
 * 日期本身的边界（含 2-29 平年）在 `BirthdayRuleTests` 里用纯单测覆盖——那个靠集成测试测不稳。
 */
class BirthdayTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long memberAId;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
    }

    @Test
    void todays_birthday_shows_up_and_never_carries_the_birthday_field() throws Exception {
        jdbc.update("UPDATE member SET birthday = CURDATE(), instrument = '笙' WHERE id = ?", memberAId);

        api(adminToken(), "GET", "/api/birthdays/today", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("团员甲"))
                .andExpect(jsonPath("$.data[0].sectionName").value("吹管声部"))
                .andExpect(jsonPath("$.data[0].instrument").value("笙"))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"))
                // ⭐ 规格的硬要求：只给姓名与职称，**不下发生日**——
                // 否则"生日对外只到「月-日」"那条可见性口径就被绕过了
                .andExpect(jsonPath("$.data[0].birthday").doesNotExist());
    }

    @Test
    void no_one_has_a_birthday_today_returns_empty_array() throws Exception {
        api(adminToken(), "GET", "/api/birthdays/today", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void a_non_active_member_is_not_announced() throws Exception {
        jdbc.update("UPDATE member SET birthday = CURDATE() WHERE id = ?", memberAId);
        setMemberStatus(admin, memberAId, "PAUSED");

        api(adminToken(), "GET", "/api/birthdays/today", null)
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void the_system_admin_is_not_announced() throws Exception {
        // seedAdmin 建的那行 is_system = 1；寿星口径要排除它
        jdbc.update("UPDATE member SET birthday = CURDATE() WHERE account = ?", ADMIN_ACCOUNT);

        api(adminToken(), "GET", "/api/birthdays/today", null)
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void an_honorary_member_is_not_announced_but_can_read_the_list() throws Exception {
        // 荣誉团员是 GRADUATED ⇒ 他自己过生日也不通告（"已离团的人不通告"）
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", section1, "MEMBER");
        jdbc.update("UPDATE member SET birthday = CURDATE() WHERE id = ?", h.id());
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null, LocalDate.now().plusYears(2), null,
                null, null, null, null), admin);
        // 另一个在团的人今天生日 —— 这才是该被通告的那位
        jdbc.update("UPDATE member SET birthday = CURDATE() WHERE id = ?", memberAId);

        String token = loginToken("honorary", h.initialPassword());
        api(token, "GET", "/api/birthdays/today", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("团员甲"));
    }

    @Test
    void needs_login_401() throws Exception {
        api(null, "GET", "/api/birthdays/today", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }
}
