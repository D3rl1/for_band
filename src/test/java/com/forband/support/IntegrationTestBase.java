package com.forband.support;

import com.forband.auth.AuthService;
import com.forband.auth.CurrentUser;
import com.forband.auth.Role;
import com.forband.auth.dto.LoginRequest;
import com.forband.event.EventService;
import com.forband.member.MemberService;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 集成测试基类：本机 MySQL 的 band_test 库（profile 叠加：local 出密码、test 出库与测试口令）。
 * 每个用例前清空全部表，由 seedAdmin/seedSection 补最小造数。
 * 鉴权走真实 token（AuthService.login 换 Bearer）经 MockMvc，拦截器与异常映射全在覆盖内。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
public abstract class IntegrationTestBase {

    public static final String ADMIN_ACCOUNT = "root-admin";
    public static final String ADMIN_PASSWORD = "admin-pw-123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AuthService authService;

    @Autowired
    protected MemberService memberService;

    @Autowired
    protected EventService eventService;

    @Autowired
    protected SectionMapper sectionMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 清空**当前库里所有表**（每个用例前调）。
     * <p>
     * 2026-10-06 重写：**表清单改为从 `information_schema` 自动发现，不再手工维护**。
     * <p>
     * 原做法维护一个按"外键顺序"排好的 `TABLES_IN_DELETE_ORDER` 数组。实测发现：
     * ⓐ 那份顺序**从来没起过作用**——`sql/schema.sql` 里**一条 DB 级 `FOREIGN KEY` 都没有**，
     *    全部是"逻辑外键"（只有普通索引 + 注释写 `FK→section`，约束落在服务层，见 `docs/13`）。
     *    也就是说 `DELETE` 顺序无所谓，注释里的"必须排在它前面"是**流传下来的错觉**。
     * ⓑ 真正的坑只有一个：**新加表忘了登记 ⇒ 旧用例带着它的脏数据跑，静默不报**
     *    （本项目最怕的失败形态：不报错、只是结果悄悄错）。现在自动发现 ⇒ **"忘了加"不再可能**。
     * <p>
     * ⚠️ 唯一要守的是：**只许清测试库**（这是全库 `DELETE`，配错 profile = 清掉真实数据）。
     * 见 {@link #requireTestSchema}。
     */
    protected void cleanTables() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        requireTestSchema(schema);
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                String.class, schema);
        for (String table : tables) {
            jdbc.update("DELETE FROM `" + table + "`");
        }
    }

    /**
     * 库名不像测试库就**拒删**。全库 `DELETE` 是这里唯一的破坏性动作：把 `application-test.yml`
     * 的 url 误改成正式库（或在 `local` 里覆盖），`mvn test` 就会把真实名册清空。
     * 报错总好过静默清库——而且这个错误会在**清表那一刻**就炸，不会拖到断言失败才暴露。
     */
    static void requireTestSchema(String schema) {
        if (schema == null || !schema.toLowerCase().contains("test")) {
            throw new IllegalStateException(
                    "cleanTables() 只允许清测试库，当前连的是：" + schema
                            + "。请检查 application-test.yml 的 spring.datasource.url。");
        }
    }

    // ---------- 造数 ----------

    /** 清表后系统里没有管理员，用它补一个（is_system=1），返回其 CurrentUser。 */
    protected CurrentUser seedAdmin() {
        jdbc.update("""
                INSERT INTO member (name, role, status, account, password_hash, is_system, created_at, updated_at)
                VALUES ('测试管理员', 'ADMIN', 'ACTIVE', ?, ?, 1, NOW(), NOW())
                """, ADMIN_ACCOUNT, encoder.encode(ADMIN_PASSWORD));
        Long id = jdbc.queryForObject(
                "SELECT id FROM member WHERE account = ?", Long.class, ADMIN_ACCOUNT);
        return new CurrentUser(id, "测试管理员", Role.ADMIN, null, true);
    }

    protected String adminToken() {
        return loginToken(ADMIN_ACCOUNT, ADMIN_PASSWORD);
    }

    protected long seedSection(String name) {
        Section section = new Section();
        section.setName(name);
        section.setOrderNo(0);
        sectionMapper.insert(section);
        return section.getId();
    }

    /** 走管理员建号路径造团员，返回含初始密码的响应。 */
    protected MemberCreateResponse createMember(CurrentUser admin, String name, String account,
                                                Long sectionId, String role) {
        return memberService.create(new com.forband.member.dto.MemberCreateRequest(
                name, account, sectionId, role, null, null, null, null, null), admin);
    }

    protected void setMemberStatus(CurrentUser admin, long memberId, String status) {
        memberService.update(memberId,
                new com.forband.member.dto.MemberUpdateRequest(
                        null, null, null, status, null, null, null, null, null, null,
                        null, null, null, null, null, null), admin);
    }

    protected CurrentUser currentUserOf(long memberId) {
        return jdbc.queryForObject(
                "SELECT id, name, role, section_id, is_system FROM member WHERE id = ?",
                (rs, i) -> new CurrentUser(rs.getLong("id"), rs.getString("name"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("section_id") == null ? null : rs.getLong("section_id"),
                        rs.getBoolean("is_system")),
                memberId);
    }

    protected String loginToken(String account, String password) {
        return authService.login(new LoginRequest(account, password), "127.0.0.1", null).token();
    }

    // ---------- MockMvc 便捷 ----------

    protected ResultActions api(String token, String method, String url, String json) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), url);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(builder);
    }

    protected long memberIdOf(String account) {
        return jdbc.queryForObject("SELECT id FROM member WHERE account = ?", Long.class, account);
    }

    /**
     * 造活动时的"结束时间"（`event` 只有 `date` + 两个 `time` 列，**不能跨天**：docs/13 不变式 12）。
     * <p>
     * 可测试常写成 `start.plusMinutes(d).toLocalTime()`——**起点 + 时长一旦越过午夜**，
     * `toLocalTime()` 会绕回 `00:xx`，于是创建被「开始时间晚于结束时间」拒掉：
     * 这不是被测代码坏了，而是造数本身过不了校验，且**只在临近午夜跑测试时才炸**
     * （2026-09-25 22:2x 实测 21 条报错，全是这个）。所以统一收在这里：跨天就夹到当天最后一秒，
     * 其余原样返回——**只有本来就会失败的用例会走到夹取那一支**，不影响其余用例的时间语义。
     */
    protected static LocalTime endTimeWithinDay(LocalDateTime start, long durationMinutes) {
        LocalDateTime end = start.plusMinutes(durationMinutes);
        if (end.toLocalDate().equals(start.toLocalDate())) {
            return end.toLocalTime();
        }
        LocalDateTime lastSecond = start.toLocalDate().atTime(23, 59, 59);
        return (lastSecond.isAfter(start) ? lastSecond : start.minusMinutes(1)).toLocalTime();
    }
}
