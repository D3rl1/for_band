package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主页访客记录（`docs/13` §13.3㉓ + `docs/14` §14.6.4「最近来看过我的人」）。
 * <p>
 * 钉住四件事：**同一人同一天只一条**（靠**唯一键**，不是代码判重）、**本人看自己不记**、
 * **只有被访问者本人能看/能清自己的**（管理员/团长也不行）、**不显示"来了几次"**。
 */
class ProfileVisitTests extends IntegrationTestBase {

    @Autowired
    private ProfileVisitService profileVisitService;

    private CurrentUser admin;
    private long memberAId;
    private long memberBId;
    private String memberAToken;
    private String memberBToken;
    private String memberCToken;
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
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());
        MemberCreateResponse c = createMember(admin, "团员丙", "memberC", section1, "MEMBER");
        memberCToken = loginToken("memberC", c.initialPassword());
        MemberCreateResponse l = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        leaderToken = loginToken("tuanzhang", l.initialPassword());
    }

    @Test
    void visiting_other_records_one_row_and_same_day_repeat_still_one() throws Exception {
        api(memberAToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());
        api(memberAToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());
        api(memberAToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());

        // ⭐ 去重**靠唯一键**：同一天来三次也只一行（"来了几次"根本无处可存，所以规格才说不显示次数）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_visit WHERE host_id = ?", Integer.class, memberBId));
        api(memberBToken, "GET", "/api/me/profile-visits", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].viewerId").value((int) memberAId))
                .andExpect(jsonPath("$.data.list[0].viewerName").value("团员甲"))
                .andExpect(jsonPath("$.data.list[0].visitDate").value(LocalDate.now().toString()))
                // ⚠️ 出参里**不许有**"次数"这类字段（规格写死：不做攀比式可视化）
                .andExpect(jsonPath("$.data.list[0].count").doesNotExist());
    }

    /** 反例：本人看自己**不记**（否则"我刚看过我自己"会变成一条访客记录）。 */
    @Test
    void visiting_self_records_nothing() throws Exception {
        api(memberAToken, "GET", "/api/members/" + memberAId, null).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM profile_visit", Integer.class));
        api(memberAToken, "GET", "/api/me/profile-visits", null)
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    /**
     * 倒序：最近来的在前。
     * ⚠️ **必须用 jdbc 直接落库并显式给时间**——`DATETIME` 没有小数秒，同一秒内造的行排序不稳定
     * （`MeTests` 里同款坑，那里也是这么办的）。
     */
    @Test
    void list_is_newest_first() throws Exception {
        insertVisit(memberAId, memberBId, LocalDate.now().minusDays(2), LocalDateTime.now().minusDays(2));
        insertVisit(memberIdOf("memberC"), memberBId, LocalDate.now(), LocalDateTime.now());

        api(memberBToken, "GET", "/api/me/profile-visits", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].viewerName").value("团员丙"))
                .andExpect(jsonPath("$.data.list[1].viewerName").value("团员甲"));
    }

    /** 反例：别人（含管理员 / 团长）**看不到**我的访客记录——`hostId` 只来自 token，所以是结构性的。 */
    @Test
    void admin_and_leader_cannot_see_others_visits() throws Exception {
        api(memberAToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());

        api(adminToken(), "GET", "/api/me/profile-visits", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        api(leaderToken, "GET", "/api/me/profile-visits", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 同一条也适用于"删"：管理员清自己的，清不掉别人的
        api(adminToken(), "DELETE", "/api/me/profile-visits", null).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM profile_visit", Integer.class),
                "管理员只能清自己的记录，别人的一条都不能少");
    }

    @Test
    void delete_clears_only_own() throws Exception {
        // 乙被两个人看过；另外还有一条"甲看丙"——它**不归乙管**，必须留下
        api(memberAToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());
        api(memberCToken, "GET", "/api/members/" + memberBId, null).andExpect(status().isOk());
        api(memberAToken, "GET", "/api/members/" + memberIdOf("memberC"), null)
                .andExpect(status().isOk());

        api(memberBToken, "DELETE", "/api/me/profile-visits", null).andExpect(status().isOk());
        api(memberBToken, "GET", "/api/me/profile-visits", null)
                .andExpect(jsonPath("$.data.total").value(0));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_visit WHERE host_id = ?", Integer.class, memberBId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM profile_visit", Integer.class),
                "只该清掉乙自己名下的那两条，「甲看丙」那条不归他管");
    }

    /** ⭐ 证明"去重真靠唯一键"：绕过服务层直插第二条 → 数据库必须拒。 */
    @Test
    void unique_key_rejects_duplicate_row() {
        insertVisit(memberAId, memberBId, LocalDate.now(), LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class,
                () -> insertVisit(memberAId, memberBId, LocalDate.now(), LocalDateTime.now()),
                "同一 (viewer, host, date) 的第二次插入必须被唯一键拒掉");
    }

    /**
     * 并发反例（`交接单` `#43` 点名要的那条）：同一对 viewer / host 同一天**并发**插入 → 只留一条，
     * 而且**不能冒异常**（服务层要吞掉唯一键冲突）。靠唯一键，不靠代码判重——代码判重挡不住并发。
     */
    @Test
    void concurrent_same_day_insert_leaves_single_row() throws Exception {
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        profileVisitService.record(memberAId, memberBId);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发插入没有在 10 秒内结束");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failed.get(), "唯一键冲突必须在服务层被吞掉，不能冒成异常");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM profile_visit", Integer.class));
    }

    @Test
    void anonymous_is_401() throws Exception {
        api(null, "GET", "/api/me/profile-visits", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
        api(null, "DELETE", "/api/me/profile-visits", null)
                .andExpect(status().isUnauthorized());
    }

    // ---------- 造数 ----------

    private void insertVisit(long viewerId, long hostId, LocalDate date, LocalDateTime at) {
        jdbc.update("INSERT INTO profile_visit (viewer_id, host_id, visit_date, visited_at, created_at)"
                + " VALUES (?, ?, ?, ?, NOW())", viewerId, hostId, date, at);
    }
}
