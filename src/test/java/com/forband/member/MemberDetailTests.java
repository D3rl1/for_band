package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「别人的主页」（`docs/14` §14.6.4）读侧裁剪：`MemberService.toView()` 是**唯一生产点**，
 * 所以「名册列表」与「单个团员」两条路径的裁剪必须一模一样（两个方法都测）。
 * <p>
 * ⚠️ **两条口径是用户 2026-09-25 当场定的，与 `docs/03` §3.4 那张表相反**（`交接单.md` `#42` 的移交事项）：
 * `contact` **对全团可见**、`account` **维持可见**。这两条各有专门用例钉住，
 * 免得将来有人照着旧表"顺手收回去"。
 */
class MemberDetailTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long memberAId;
    private long leaderId;
    private String memberAToken;
    private String memberBToken;
    private String leaderToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        long section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", section1, "MEMBER");
        memberBToken = loginToken("memberB", b.initialPassword());
        MemberCreateResponse l = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        leaderId = l.id();
        leaderToken = loginToken("tuanzhang", l.initialPassword());

        // 把甲的敏感字段都填上，作为"必须裁掉"的基线
        memberService.update(memberAId, new MemberUpdateRequest(
                null, null, null, null, "2020001", "笙", "13800000000", "想转声部",
                null, LocalDate.of(2020, 9, 1), "fluent-frog", "吹笙的", "wx-abc",
                LocalDate.of(1999, 3, 5), null), admin);
    }

    @Test
    void other_viewer_gets_month_day_birthday_and_year_only_joined() throws Exception {
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("团员甲"))
                .andExpect(jsonPath("$.data.sectionName").value("吹管声部"))
                .andExpect(jsonPath("$.data.instrument").value("笙"))
                .andExpect(jsonPath("$.data.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.avatar").value("fluent-frog"))
                .andExpect(jsonPath("$.data.intro").value("吹笙的"))
                // 生日只到「月-日」：**不带年份**（docs/03 §3.4 + docs/13 §13.3②；这是 #8 的落地）
                .andExpect(jsonPath("$.data.birthday").value("03-05"))
                // 入团时间只到年份
                .andExpect(jsonPath("$.data.joinedAt").value("2020"));
    }

    /** 反例：这几个是账号级 / 管理信息，别人**一个都不该拿到**。 */
    @Test
    void other_viewer_cannot_see_studentno_note_and_account_level_fields() throws Exception {
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentNo").doesNotExist())
                .andExpect(jsonPath("$.data.note").doesNotExist())
                // ⚠️ activated 是 Boolean：**null 才是"不给看"**，别退化成 false（false 会冒充"未激活"）
                .andExpect(jsonPath("$.data.activated").doesNotExist())
                .andExpect(jsonPath("$.data.honoraryUntil").doesNotExist())
                .andExpect(jsonPath("$.data.lastLoginAt").doesNotExist());
    }

    /**
     * ⚠️ `contact` **对全团可见**——用户 2026-09-25 明确改的口径（"填了就说明不担心"），
     * 与 `docs/03` §3.4 的旧表相反。**别照旧表把这条收回去**（移交事项里已请规格侧改表）。
     */
    @Test
    void contact_is_visible_to_others() throws Exception {
        api(memberBToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.contact").value("wx-abc"));
    }

    @Test
    void self_and_admin_get_full_values_but_self_still_not_note() throws Exception {
        // 本人看自己：学号 / 完整生日 / 完整入团日期都给
        api(memberAToken, "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.studentNo").value("2020001"))
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"))
                .andExpect(jsonPath("$.data.joinedAt").value("2020-09-01"))
                // ⚠️ 但**管理员备注连本人也看不到**（docs/03 §3.4：那是管理员的工作备注）
                .andExpect(jsonPath("$.data.note").doesNotExist());
        // 管理员：全都有
        api(adminToken(), "GET", "/api/members/" + memberAId, null)
                .andExpect(jsonPath("$.data.studentNo").value("2020001"))
                .andExpect(jsonPath("$.data.birthday").value("1999-03-05"))
                .andExpect(jsonPath("$.data.joinedAt").value("2020-09-01"))
                .andExpect(jsonPath("$.data.note").value("想转声部"))
                // ⚠️ `activated` 的含义是「`initial_password` 已清空」= **已激活**
                //    （`MemberService` 装配处 + `admin-members.html` 的「已激活 / 未激活」都是这个极性）。
                //    甲在 setUp 里登录过 ⇒ 首登已把初始密码清掉 ⇒ true。
                .andExpect(jsonPath("$.data.activated").value(true))
                .andExpect(jsonPath("$.data.account").value("memberA"));
    }

    /** 名册列表与单个团员走的是同一个 `toView()`——但得真测一遍，别只信"应该一样"。 */
    @Test
    void member_list_crops_the_same_way() throws Exception {
        String list = api(memberBToken, "GET", "/api/members?size=50", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(list.contains("\"03-05\""), "名册里别人的生日应当只到「月-日」");
        assertTrue(list.contains("wx-abc"), "contact 对全团可见（用户 2026-09-25 定），列表里应当有");
        assertFalse(list.contains("1999-03-05"), "名册不能泄漏他人的**完整**生日（带年份）");
        assertFalse(list.contains("2020001"), "名册不能泄漏他人学号");
        assertFalse(list.contains("想转声部"), "名册不能泄漏管理员备注");
    }

    @Test
    void honorary_can_view_others_detail() throws Exception {
        String token = seedHonorary();
        api(token, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("团员甲"))
                // 荣誉团员也是"别人"，一样只到月-日
                .andExpect(jsonPath("$.data.birthday").value("03-05"));
    }

    /**
     * 反例：**没有声部的人**（团长 / 指挥 / 未分配声部的团员）的主页也必须打得开。
     * <p>
     * 这条是补漏：`toView()` 要拿声部名，而单条取名的旧写法是 `List.of(声部 id)`，
     * 而 **`List.of` 不收 null** —— 看这些人的资料会直接 NPE → 500，
     * 名册里点一下团长的名字就能撞上（现有用例的造数全都带声部，所以一直没被发现）。
     */
    @Test
    void member_without_section_has_a_reachable_detail_page() throws Exception {
        // 别人（团员）看团长
        api(memberBToken, "GET", "/api/members/" + leaderId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("团长"))
                .andExpect(jsonPath("$.data.role").value("LEADER"))
                .andExpect(jsonPath("$.data.sectionName").doesNotExist());
        // 团长看自己：走的是同一条单条取名路径，同样不许 500
        api(leaderToken, "GET", "/api/members/" + leaderId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sectionName").doesNotExist());
    }

    @Test
    void system_admin_detail_is_404_and_unauthenticated_is_401() throws Exception {
        api(adminToken(), "GET", "/api/members/" + admin.memberId(), null)
                .andExpect(status().isNotFound());
        api(null, "GET", "/api/members/" + memberAId, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    /** 造一个**能登录**的荣誉团员（角色 HONORARY + 有效期未过 ⇒ canLogin 放行），返回其 token。 */
    private String seedHonorary() throws Exception {
        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", null, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null, LocalDate.now().plusYears(2), null,
                null, null, null, null, null), admin);
        return loginToken("honorary", h.initialPassword());
    }
}
