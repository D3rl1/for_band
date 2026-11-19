package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「参演过 N 场专场」（`docs/14` §14.6.4「个人页」第 6 条，`交接单 #117`）。
 * <p>
 * ⭐ 钉住三件事：**只数专场**（普通演出不算）、**没参演过回 0**（不是 404 / null）、
 * **别人也看得到**（它本来就是给团员主页用的）。
 */
class MemberShowcaseCountTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long memberAId;
    private String memberAToken;
    private long memberBId;
    private String memberBToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        MemberCreateResponse a = createMember(admin, "甲团员", "memberA", seedSection("胡琴声部"), "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "乙团员", "memberB", seedSection("琵琶声部"), "MEMBER");
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());
    }

    /** 开一场专场（`POST /api/admin/showcase` 建出来的就是 `is_showcase = 1`）。 */
    private long showcase(String name, String date) throws Exception {
        String body = api(adminToken(), "POST", "/api/admin/showcase",
                "{\"name\":\"" + name + "\",\"date\":\"" + date + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.id")).longValue();
    }

    private void cast(long concertId, long memberId) throws Exception {
        api(adminToken(), "PUT", "/api/concerts/" + concertId + "/cast",
                "{\"members\":[{\"memberId\":" + memberId + "}]}").andExpect(status().isOk());
    }

    @Test
    void counts_only_showcases_and_zero_is_a_real_zero() throws Exception {
        // ⚠️ 一场没参演过 ⇒ **回 0**（不是 404、不是 null）——"一场没参演过"是个事实
        api(memberAToken, "GET", "/api/members/" + memberAId + "/showcase-count", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));

        long s1 = showcase("专场一", "2024-06-01");
        long s2 = showcase("专场二", "2025-06-01");
        long normal = showcase("校庆晚会", "2025-12-01");
        // ⭐ 把第三场**改成非专场**（现实里它只是一场普通演出）
        jdbc.update("UPDATE concert SET is_showcase = 0 WHERE id = ?", normal);

        cast(s1, memberAId);
        cast(s2, memberAId);
        cast(normal, memberAId);

        // 参演了 3 场、其中只有 2 场是专场 ⇒ 数 2
        api(memberAToken, "GET", "/api/members/" + memberAId + "/showcase-count", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    void others_can_see_it_too() throws Exception {
        long s1 = showcase("专场一", "2024-06-01");
        cast(s1, memberAId);
        // ⚠️ 乙既不是管理员也不是甲本人 —— 照样看得到（这块本来就是给别人看团员主页用的）
        api(memberBToken, "GET", "/api/members/" + memberAId + "/showcase-count", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        // 而乙自己没参演过 ⇒ 0
        api(memberBToken, "GET", "/api/members/" + memberBId + "/showcase-count", null)
                .andExpect(jsonPath("$.data.count").value(0));
    }
}
