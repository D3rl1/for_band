package com.forband.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫 {@link IntegrationTestBase#cleanTables()}。
 * <p>
 * 它是测试基建里**唯一**的破坏性动作（对整个库逐表 `DELETE`），所以按项目纪律：
 * 写完守卫必须造反例验算——"只跑出一次全绿"什么都说明不了。
 */
class CleanTablesTests extends IntegrationTestBase {

    /** 只认 ASCII 表名，免得把 schema.sql 注释里那句 `CREATE TABLE IF NOT EXISTS` 的说明文字当成表名。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create table if not exists\\s+`?([a-z_][a-z0-9_]*)`?", Pattern.CASE_INSENSITIVE);

    /**
     * 正例：造出脏数据（含一对"逻辑外键"父子），清完必须一条不剩。
     * <p>
     * 顺带钉住一件事：`section_note.author_id` 指向 `member`，若哪天有人真给库里补上 DB 级 FK，
     * 这行就是"先删父表必炸"的形态——那时本用例当场变红，正好提醒"顺序"重新变成真问题。
     */
    @Test
    void clears_every_table_including_a_child_row_written_after_its_parent() {
        cleanTables();

        long sectionId = seedSection("探针声部");
        jdbc.update("""
                INSERT INTO member (name, role, status, section_id, account, password_hash, is_system, created_at, updated_at)
                VALUES ('探针', 'MEMBER', 'ACTIVE', ?, 'probe-acct', 'x', 0, NOW(), NOW())
                """, sectionId);
        long memberId = jdbc.queryForObject(
                "SELECT id FROM member WHERE account = 'probe-acct'", Long.class);
        jdbc.update("INSERT INTO section_note (section_id, author_id, body, created_at, updated_at) "
                + "VALUES (?, ?, '探针留言', NOW(), NOW())", sectionId, memberId);

        assertThat(countIn("member")).isEqualTo(1);
        assertThat(countIn("section_note")).isEqualTo(1);

        cleanTables();

        assertThat(countIn("member")).isZero();
        assertThat(countIn("section_note")).isZero();
        assertThat(countIn("section")).isZero();
    }

    /** 反例：库名不像测试库 ⇒ 拒删。配错 profile 时宁可当场炸，也不能把真实名册清空。 */
    @Test
    void refuses_to_wipe_a_database_whose_name_is_not_a_test_database() {
        assertThatThrownBy(() -> IntegrationTestBase.requireTestSchema("band"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("band");
        assertThatThrownBy(() -> IntegrationTestBase.requireTestSchema(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IntegrationTestBase.requireTestSchema("band_prod"))
                .isInstanceOf(IllegalStateException.class);

        // 正例不能漏：本基类实际连的库必须放行，否则上面几条全是在测一个永远不进的分支。
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).contains("test");
    }

    /**
     * 端到端反例：真拿一个**非测试库**喂给 `cleanTables()`，守卫必须在**删除任何东西之前**拦下。
     * <p>
     * 为什么还要这条：上面那个 `requireTestSchema("band")` 只是**纸上验算**——直接给函数喂一个假库名，
     * 证明的是"谓词本身写对了"，**证明不了** "`cleanTables()` 真的把**当前连着的那个库名**交给了守卫"。
     * 万一哪天有人把它改成传常量、或把守卫挪到删除循环**之后**，单测照样全绿，而**真实名册会被清空**。
     * <p>
     * 做法：借一条**真实连接**、把它切到 `information_schema`（MySQL 自带系统目录），用它造个
     * `JdbcTemplate` 当探针，再让 `cleanTables()` 照着它跑。⚠️ 两条前置断言**顺序不能反**：
     * 先证"探针真的指向别处"，再证"这个探测目标本身删不动东西"——**先证安全，才允许动手**。
     * <p>
     * ⚠️ 用完必须**还原连接状态**：catalog 是**会话级**的，连接一关就回到 Hikari 池，不还原就会把
     * 池里某条连接永久指到 `information_schema`、后续用例莫名其妙全炸（与"别把
     * `SET FOREIGN_KEY_CHECKS=0` 留在池里"是同一个道理）。
     */
    @Test
    void end_to_end_refuses_to_wipe_a_real_non_test_catalog() throws Exception {
        DataSource ds = jdbc.getDataSource();
        assertThat(ds).as("没有 DataSource 就造不出探针，这条用例就没意义").isNotNull();

        try (Connection probe = ds.getConnection()) {
            String originalCatalog = probe.getCatalog();
            assertThat(originalCatalog)
                    .as("拿不到原 catalog 就没法安全还原，不能在这种前提下跑")
                    .isNotBlank();
            probe.setCatalog("information_schema");

            JdbcTemplate probeJdbc = new JdbcTemplate(new SingleConnectionDataSource(probe, true));
            try {
                // 前置①：探针真的指向别处。不证这条，下面等于在断言"守卫放行"，整条用例在空转。
                assertThat(probeJdbc.queryForObject("SELECT DATABASE()", String.class))
                        .isEqualTo("information_schema");
                // 前置②：这个探测目标本身零破坏面（把"安全"从推断变成实测）。⚠️ 必须在动手之前。
                assertThat(probeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'information_schema' AND table_type = 'BASE TABLE'",
                        Long.class))
                        .as("探测目标里有可删的表，这个探针就不安全了")
                        .isZero();

                JdbcTemplate saved = this.jdbc;
                this.jdbc = probeJdbc;
                try {
                    // 断言必须落到**守卫自己那句话**上：只说"抛了异常"会把"因为别的原因炸了"也算通过
                    // （项目纪律：报不出来、和报错地方不对，一样坏）。
                    assertThatThrownBy(this::cleanTables)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("只允许清测试库")
                            .hasMessageContaining("information_schema");
                } finally {
                    this.jdbc = saved;
                }
            } finally {
                probe.setCatalog(originalCatalog);   // ⚠️ 还原，见方法注释
            }
        }
    }

    /**
     * 反例（"疑似漏加新表"的提示）：`sql/schema.sql` 里声明的每张表，测试库里都得真的存在。
     * <p>
     * 判据取自 schema.sql 本身而不是另一份手写清单——**清单会跟真表漂移，schema.sql 不会**。
     * 表加进 schema.sql 却忘了迁到 band_test 时，用例会以"表不存在"这种离现场很远的方式红，
     * 这里把它变成一句看得懂的话。
     */
    @Test
    void every_table_declared_in_schema_sql_exists_in_the_test_database() throws IOException {
        Set<String> declared = new TreeSet<>();
        String ddl = Files.readString(Path.of("sql/schema.sql"), StandardCharsets.UTF_8);
        Matcher m = CREATE_TABLE.matcher(ddl);
        while (m.find()) {
            declared.add(m.group(1));
        }
        // 解析器自检：一个字都没解析出来，说明正则早失效了——不能算通过。
        assertThat(declared).hasSizeGreaterThan(20);

        Set<String> actual = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class));

        assertThat(actual).containsAll(declared);
    }

    private long countIn(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
    }
}
