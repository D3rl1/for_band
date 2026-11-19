package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「我们的专场」专页（`docs/17`）：展示侧按年聚合、策展侧**仅管理员**。
 * <p>
 * ⚠️ 本类补掉 `交接单` `#83` 的欠账 —— `#71` 验收里那条"**只有 `is_showcase = 1` 的进时间线**"
 * 当时断言不到（时间线端点属 `#72`），在这里落地。
 * ⚠️ 动了权限 ⇒ 反例为主（`AGENTS.md` 硬规矩其一）：四个非管理员角色 × 全部策展端点。
 */
class ShowcaseTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private String memberToken;
    private String leaderToken;
    private String conductorToken;
    private String sectionLeaderToken;
    private String honoraryToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        memberToken = tokenOf(createMember(admin, "团员甲", "memberA", section1, "MEMBER"));
        leaderToken = tokenOf(createMember(admin, "团长", "leader1", section1, "LEADER"));
        conductorToken = tokenOf(createMember(admin, "指挥", "conductor", section1, "CONDUCTOR"));
        sectionLeaderToken = tokenOf(createMember(admin, "声部长", "seclead", section1, "SECTION_LEADER"));
        // 荣誉团员：能看（与看演出档案同一个权限），不能策展（docs/17 §17.4「可见性」）
        MemberCreateResponse h = createMember(admin, "老团长", "honorary", section1, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        jdbc.update("UPDATE member SET role = 'HONORARY', honorary_until = DATE_ADD(CURDATE(), INTERVAL 1 YEAR) "
                + "WHERE id = ?", h.id());
        honoraryToken = loginToken("honorary", h.initialPassword());
    }

    // ---------- 展示侧 ----------

    /** `#83`：只有 `is_showcase = 1` 的进时间线；按年聚合；**没有策展图的年份不出现**。 */
    @Test
    void timeline_aggregates_by_year_and_only_includes_showcases_with_photos() throws Exception {
        int thisYear = LocalDate.now().getYear();
        long oldShowcase = openShowcase("2024 专场", LocalDate.of(2024, 5, 1));      // 去年：1 张
        long thisShowcase = openShowcase("今年专场", LocalDate.now());               // 今年：2 张
        openShowcase("没加图的专场", LocalDate.of(2023, 3, 3));                       // ⚠️ 没加图 ⇒ 不该出现

        addShowcasePhoto(thisShowcase, uploadPhoto("a.png"));
        addShowcasePhoto(thisShowcase, uploadPhoto("b.png"));
        addShowcasePhoto(oldShowcase, uploadPhoto("c.png"));

        api(memberToken, "GET", "/api/showcase/timeline", null)
                .andExpect(status().isOk())
                // 年份升序、且只有"有图的"两年
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].year").value(2024))
                .andExpect(jsonPath("$.data[0].photoCount").value(1))
                .andExpect(jsonPath("$.data[1].year").value(thisYear))
                .andExpect(jsonPath("$.data[1].photoCount").value(2));
    }

    /** 非专场（`is_showcase = 0`）即使有晒图，也不上时间线。 */
    @Test
    void ordinary_concert_never_appears_on_the_timeline() throws Exception {
        long id = createConcert("普通演出", LocalDate.now());
        addShowcasePhoto(id, uploadPhoto("x.png"));   // 直接给普通演出挂一张策展图
        api(memberToken, "GET", "/api/showcase/timeline", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 某年全部策展图：**按「演出日期 → `sort_no`」排**，且只回该年。 */
    @Test
    void year_photos_are_sorted_by_concert_date_then_sort_no() throws Exception {
        int thisYear = LocalDate.now().getYear();
        long later = openShowcase("下半年专场", LocalDate.of(thisYear, 9, 1));
        long earlier = openShowcase("年初专场", LocalDate.of(thisYear, 2, 1));
        long lastYear = openShowcase("去年专场", LocalDate.of(thisYear - 1, 6, 1));
        long p1 = addShowcasePhoto(earlier, uploadPhoto("e1.png"));
        long p2 = addShowcasePhoto(earlier, uploadPhoto("e2.png"));
        addShowcasePhoto(later, uploadPhoto("l1.png"));
        addShowcasePhoto(lastYear, uploadPhoto("y1.png"));
        // 把年初那两张的顺序倒过来，验证 sort_no 起作用
        api(adminToken(), "PUT", "/api/admin/showcase/" + earlier + "/photos/order",
                "{\"photoIds\":[" + p2 + "," + p1 + "]}").andExpect(status().isOk());

        api(memberToken, "GET", "/api/showcase/years/" + thisYear, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                // 2 月那两张在前（且按 sort_no 排：先 p2 后 p1），9 月那张在后
                .andExpect(jsonPath("$.data[0].photoId").value(p2))
                .andExpect(jsonPath("$.data[1].photoId").value(p1))
                .andExpect(jsonPath("$.data[2].photoId").value(jdbc.queryForObject(
                        "SELECT id FROM showcase_photo WHERE concert_id = ?", Long.class, later)));
        // 去年的不在这一年里
        api(memberToken, "GET", "/api/showcase/years/" + (thisYear - 1), null)
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /** 荣誉团员**能看**（与看演出档案同一个权限）。 */
    @Test
    void honorary_member_can_read_timeline_and_years() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        addShowcasePhoto(id, uploadPhoto("h.png"));
        api(honoraryToken, "GET", "/api/showcase/timeline", null).andExpect(status().isOk());
        api(honoraryToken, "GET", "/api/showcase/years/" + LocalDate.now().getYear(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        api(null, "GET", "/api/showcase/timeline", null).andExpect(status().isUnauthorized());
    }

    // ---------- 策展侧：权限反例 ----------

    /** ⚠️ 四个非管理员角色 × 六个策展端点 → 403 + `30001`（动了权限，必须有反例）。 */
    @Test
    void non_admins_cannot_curate_403_30001() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long photoId = addShowcasePhoto(id, uploadPhoto("p.png"));
        for (String token : new String[]{memberToken, leaderToken, conductorToken, sectionLeaderToken}) {
            api(token, "POST", "/api/admin/showcase",
                    "{\"name\":\"x\",\"date\":\"" + LocalDate.now() + "\"}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "PUT", "/api/admin/showcase/" + id, "{\"isShowcase\":false}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "GET", "/api/admin/showcase/" + id + "/candidates", null)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "GET", "/api/showcase/years/2026", null).andExpect(status().isOk());   // 展示侧人人都能看
            api(token, "POST", "/api/admin/showcase/" + id + "/photos", "{\"fileId\":1}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "PUT", "/api/admin/showcase/" + id + "/photos/order", "{\"photoIds\":[]}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "PUT", "/api/admin/showcase/" + id + "/photos/" + photoId, "{\"note\":\"改\"}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
            api(token, "DELETE", "/api/admin/showcase/" + id + "/photos/" + photoId, null)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        }
        // 反例要断言"数据没变"：标记还是专场、那张图还在
        assertEquals(1, count("SELECT COUNT(*) FROM showcase_photo WHERE id = ?", photoId));
        assertEquals(1, count("SELECT is_showcase FROM concert WHERE id = ?", id));
    }

    // ---------- 策展侧：行为 ----------

    /** 开新时间点建出来的**就是一条普通演出记录**（`status = PLANNED`）；重复开同一场 → `10001`。 */
    @Test
    void open_creates_an_ordinary_concert_and_duplicate_is_rejected() throws Exception {
        LocalDate date = LocalDate.now();
        api(adminToken(), "POST", "/api/admin/showcase",
                "{\"name\":\"换届专场\",\"date\":\"" + date + "\",\"venue\":\"音乐厅\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isShowcase").value(true));
        assertEquals(1, count("SELECT is_showcase FROM concert WHERE name = ?", "换届专场"));
        assertEquals("PLANNED", jdbc.queryForObject(
                "SELECT status FROM concert WHERE name = '换届专场'", String.class));
        // 幂等失败要说得出来：同一场（同名同日）再来一次 → 10001
        api(adminToken(), "POST", "/api/admin/showcase",
                "{\"name\":\"换届专场\",\"date\":\"" + date + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        // 缺日期 → 10001
        api(adminToken(), "POST", "/api/admin/showcase", "{\"name\":\"只有名字\"}")
                .andExpect(status().isUnprocessableEntity());
    }

    /** 设为 / 取消专场**双向**，且**只翻标记、绝不删演出档案**。 */
    @Test
    void toggle_is_two_way_and_keeps_the_archive() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long photoId = addShowcasePhoto(id, uploadPhoto("p.png"));
        api(adminToken(), "PUT", "/api/admin/showcase/" + id, "{\"isShowcase\":false}")
                .andExpect(status().isOk());
        // 演出档案、晒图墙、策展图都还在；只是不再上时间线
        assertEquals(1, count("SELECT COUNT(*) FROM concert WHERE id = ?", id));
        assertEquals(1, count("SELECT COUNT(*) FROM showcase_photo WHERE id = ?", photoId));
        api(memberToken, "GET", "/api/showcase/timeline", null)
                .andExpect(jsonPath("$.data.length()").value(0));
        // 撤错了还能标回来
        api(adminToken(), "PUT", "/api/admin/showcase/" + id, "{\"isShowcase\":true}")
                .andExpect(status().isOk());
        api(memberToken, "GET", "/api/showcase/timeline", null)
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /** 排序是 **whole-replace**；id 不属于这场 → `10001`。 */
    @Test
    void order_is_whole_replace_and_rejects_foreign_photo() throws Exception {
        long a = openShowcase("专场 A", LocalDate.now());
        long b = openShowcase("专场 B", LocalDate.now());
        long a1 = addShowcasePhoto(a, uploadPhoto("a1.png"));
        long a2 = addShowcasePhoto(a, uploadPhoto("a2.png"));
        long b1 = addShowcasePhoto(b, uploadPhoto("b1.png"));

        api(adminToken(), "PUT", "/api/admin/showcase/" + a + "/photos/order",
                "{\"photoIds\":[" + a2 + "," + a1 + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].photoId").value(a2))
                .andExpect(jsonPath("$.data[1].photoId").value(a1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT sort_no FROM showcase_photo WHERE id = ?", Integer.class, a2));

        // ⚠️ 把 B 的图塞进 A 的排序里 → 10001，且顺序没被改坏
        api(adminToken(), "PUT", "/api/admin/showcase/" + a + "/photos/order",
                "{\"photoIds\":[" + a1 + "," + b1 + "]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        assertEquals(1, jdbc.queryForObject(
                "SELECT sort_no FROM showcase_photo WHERE id = ?", Integer.class, a2));
    }

    /** 文案上限 2000 字；刚好 2000 可以、2001 拒绝（`10001`）；清空文案要真的清掉。 */
    @Test
    void note_length_is_capped_and_can_be_cleared() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long photoId = addShowcasePhoto(id, uploadPhoto("p.png"));
        String long2000 = "字".repeat(2000);
        api(adminToken(), "PUT", "/api/admin/showcase/" + id + "/photos/" + photoId,
                "{\"note\":\"" + long2000 + "\"}").andExpect(status().isOk());
        api(adminToken(), "PUT", "/api/admin/showcase/" + id + "/photos/" + photoId,
                "{\"note\":\"" + long2000 + "字\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        // 清空（updateById 忽略 null ⇒ 必须走显式 set，这条就是它的反例）
        api(adminToken(), "PUT", "/api/admin/showcase/" + id + "/photos/" + photoId,
                "{\"note\":null}").andExpect(status().isOk());
        assertEquals(0, count("SELECT COUNT(*) FROM showcase_photo WHERE id = ? AND note IS NOT NULL", photoId));
    }

    /** 删策展图**只删关联行**，`file` 还在（`docs/17` §17.5：同一张图可能还在晒图墙上）。 */
    @Test
    void deleting_a_curated_photo_keeps_the_file() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long fileId = uploadPhoto("keep.png");
        long photoId = addShowcasePhoto(id, fileId);
        api(adminToken(), "DELETE", "/api/admin/showcase/" + id + "/photos/" + photoId, null)
                .andExpect(status().isOk());
        assertEquals(0, count("SELECT COUNT(*) FROM showcase_photo WHERE id = ?", photoId));
        assertEquals(1, count("SELECT COUNT(*) FROM file WHERE id = ?", fileId));
    }

    /** 「从已有的里挑」：列这场晒图墙上的照片，且**不改 `concert_photo` 一个字**。 */
    @Test
    void candidates_list_the_photo_wall_without_changing_it() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long fileId = uploadPhoto("wall.png");
        api(memberToken, "POST", "/api/concerts/" + id + "/photos",
                "{\"fileId\":" + fileId + ",\"caption\":\"合影\"}").andExpect(status().isOk());
        int before = count("SELECT COUNT(*) FROM concert_photo WHERE concert_id = ?", id);
        api(adminToken(), "GET", "/api/admin/showcase/" + id + "/candidates", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].caption").value("合影"));
        assertEquals(before, count("SELECT COUNT(*) FROM concert_photo WHERE concert_id = ?", id));
    }

    /** 加图时 `sort_no` 由服务端追加到末尾。 */
    @Test
    void add_photo_appends_sort_no() throws Exception {
        long id = openShowcase("专场", LocalDate.now());
        long p1 = addShowcasePhoto(id, uploadPhoto("1.png"));
        long p2 = addShowcasePhoto(id, uploadPhoto("2.png"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT sort_no FROM showcase_photo WHERE id = ?", Integer.class, p1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT sort_no FROM showcase_photo WHERE id = ?", Integer.class, p2));
    }

    /** 管理页要能**先看现状**：`years/{year}` 带上 `concertId`，按它筛出这一场（不必为管理页单开端点）。 */
    @Test
    void year_photos_carry_concert_id_so_the_admin_page_can_filter_one_concert() throws Exception {
        int year = LocalDate.now().getYear();
        // 两场都落在同一年、但日期分先后 ⇒ 顺序由「演出日期」定，不靠插入顺序侥幸
        long id = openShowcase("专场", LocalDate.of(year, 5, 1));
        long other = openShowcase("同一年的另一场", LocalDate.of(year, 6, 1));
        long p1 = addShowcasePhoto(id, uploadPhoto("1.png"));
        long p2 = addShowcasePhoto(id, uploadPhoto("2.png"));
        addShowcasePhoto(other, uploadPhoto("3.png"));
        api(adminToken(), "GET", "/api/showcase/years/" + year, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].concertId").value(id))
                .andExpect(jsonPath("$.data[0].photoId").value(p1))
                .andExpect(jsonPath("$.data[1].concertId").value(id))
                .andExpect(jsonPath("$.data[1].photoId").value(p2))
                .andExpect(jsonPath("$.data[2].concertId").value(other));
    }

    /** 找不到的演出 / 图 → 404（不是 500）。 */
    @Test
    void missing_concert_or_photo_is_404() throws Exception {
        api(adminToken(), "GET", "/api/admin/showcase/999999/candidates", null)
                .andExpect(status().isNotFound());
        long id = openShowcase("专场", LocalDate.now());
        api(adminToken(), "DELETE", "/api/admin/showcase/" + id + "/photos/999999", null)
                .andExpect(status().isNotFound());
        api(adminToken(), "POST", "/api/admin/showcase/" + id + "/photos", "{\"fileId\":999999}")
                .andExpect(status().isNotFound());
    }

    // ---------- 造数 ----------

    private String tokenOf(MemberCreateResponse member) {
        return loginToken(member.account(), member.initialPassword());
    }

    private int count(String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    /** 走接口建一场专场，返回 id（`is_showcase = 1`）。 */
    private long openShowcase(String name, LocalDate date) throws Exception {
        api(adminToken(), "POST", "/api/admin/showcase",
                "{\"name\":\"" + name + "\",\"date\":\"" + date + "\"}").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM concert WHERE name = ?", Long.class, name);
    }

    /** 建一场**普通**演出（`is_showcase` 由 DB 默认值给 0）。 */
    private long createConcert(String name, LocalDate date) throws Exception {
        api(adminToken(), "POST", "/api/concerts",
                "{\"name\":\"" + name + "\",\"date\":\"" + date + "\"}").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM concert WHERE name = ?", Long.class, name);
    }

    private long addShowcasePhoto(long concertId, long fileId) throws Exception {
        api(adminToken(), "POST", "/api/admin/showcase/" + concertId + "/photos",
                "{\"fileId\":" + fileId + "}").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM showcase_photo", Long.class);
    }

    private long uploadPhoto(String filename) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0,
                0, 0, 0, 13};
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", png);
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }
}
