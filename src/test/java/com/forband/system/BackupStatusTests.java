package com.forband.system;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 备份状态（docs/12 §12.5「⚡ 备份成败怎么让应用知道」）：**仅管理员**可读；
 * 状态文件读不出来必须回"未知"而**不是 500**；>48 小时与"从没成功过"都靠页面标红，
 * 所以端点只需把时间与 `staleHours` 如实给出。
 * <p>
 * 状态文件路径用 `@TestPropertySource` 指到 `target/` 下一个固定文件（镜像 `StorageUsageTests` 的写法）：
 * 这样"文件不存在 / JSON 坏了 / 时间戳坏了"三种失败都能**确定造出来**。
 */
@TestPropertySource(properties = "band.backup.status-file=target/test-backup-status.json")
class BackupStatusTests extends IntegrationTestBase {

    private static final Path STATUS_FILE = Path.of("target", "test-backup-status.json");

    private CurrentUser admin;
    private String memberToken;
    private String leaderToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanTables();
        admin = seedAdmin();
        MemberCreateResponse m = createMember(admin, "团员甲", "memberA", null, "MEMBER");
        memberToken = loginToken("memberA", m.initialPassword());
        MemberCreateResponse l = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        leaderToken = loginToken("tuanzhang", l.initialPassword());
        Files.deleteIfExists(STATUS_FILE);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(STATUS_FILE);
    }

    @Test
    void only_admin_can_read_and_anonymous_is_401() throws Exception {
        writeStatus("""
                {"ok":true,"last_attempt_at":"%s","last_success_at":"%s","message":""}
                """.formatted(hoursAgo(1), hoursAgo(1)));

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        api(memberToken, "GET", "/api/admin/backup-status", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(leaderToken, "GET", "/api/admin/backup-status", null)
                .andExpect(status().isForbidden());
        api(null, "GET", "/api/admin/backup-status", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    /** 脚本还没跑过（或文件被删掉）：必须回"未知"，**不许 500**。 */
    @Test
    void missing_file_is_unknown_and_not_500() throws Exception {
        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.known").value(false))
                .andExpect(jsonPath("$.data.ok").doesNotExist())
                .andExpect(jsonPath("$.data.lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$.data.staleHours").doesNotExist());
    }

    /** 脚本没按原子写、读到了半截 JSON：同样回"未知"，**不许 500**。 */
    @Test
    void broken_json_is_unknown_and_not_500() throws Exception {
        writeStatus("{\"ok\":true,\"last_success_at\":\"2026-09-2");

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(false));
    }

    /** JSON 合法但时间戳不是 ISO 8601：**整体按未知**（不能"ok 照给、staleHours 给 null"——那会让页面不标红）。 */
    @Test
    void unparseable_timestamp_is_unknown_and_not_500() throws Exception {
        writeStatus("""
                {"ok":true,"last_attempt_at":"昨天","last_success_at":"昨天","message":""}
                """);

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(false));
    }

    /** 成功态：`staleHours` 是"距上次成功的小时数"，页面拿它判 >48。 */
    @Test
    void fresh_success_reports_small_stale_hours() throws Exception {
        writeStatus("""
                {"ok":true,"last_attempt_at":"%s","last_success_at":"%s","message":""}
                """.formatted(hoursAgo(2), hoursAgo(2)));

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(true))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.staleHours", lessThan(3.0)));
    }

    /**
     * 失败态：**`last_success_at` 保留上次成功的时间**（脚本的规矩），所以它比 `last_attempt_at` 早很多
     * ——页面因此会标红，这才是"备份失败必须可见"的关键路径。
     */
    @Test
    void failed_attempt_keeps_last_success_and_reports_over_48h() throws Exception {
        writeStatus("""
                {"ok":false,"last_attempt_at":"%s","last_success_at":"%s","message":"mysqldump 失败：Can't connect to MySQL server"}
                """.formatted(hoursAgo(1), hoursAgo(72)));

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(true))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("Can't connect")))
                .andExpect(jsonPath("$.data.lastSuccessAt").isNotEmpty())
                .andExpect(jsonPath("$.data.staleHours", greaterThan(48.0)));
    }

    /**
     * ⚠️ 时区**两种 ISO 写法都要认**：`+0800`（`date +%z` 的产物，脚本最可能写成这样）
     * 与 `+08:00`。`OffsetDateTime.parse` 只认后者——这条就是防它（2026-09-25 实测写错时
     * 页面会永远显示"未知"）。
     */
    @Test
    void compact_offset_without_colon_is_accepted() throws Exception {
        writeStatus("""
                {"ok":true,"last_attempt_at":"%s","last_success_at":"%s","message":""}
                """.formatted(
                OffsetDateTime.now().minusHours(3)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")),
                OffsetDateTime.now().minusHours(3)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))));

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(true))
                .andExpect(jsonPath("$.data.staleHours", greaterThan(2.0)))
                .andExpect(jsonPath("$.data.staleHours", lessThan(4.0)));
    }

    /** 从没成功过：`staleHours` 为 null（页面按"标红"处理），但 `ok`/`message` 照样给出。 */
    @Test
    void never_succeeded_has_null_stale_hours() throws Exception {
        writeStatus("""
                {"ok":false,"last_attempt_at":"%s","last_success_at":null,"message":"mysqldump 失败"}
                """.formatted(hoursAgo(1)));

        api(adminToken(), "GET", "/api/admin/backup-status", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.known").value(true))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$.data.staleHours").doesNotExist());
    }

    // ---------- 造数 ----------

    private void writeStatus(String json) throws IOException {
        Files.createDirectories(STATUS_FILE.getParent());
        Files.writeString(STATUS_FILE, json, StandardCharsets.UTF_8);
    }

    private static String hoursAgo(long hours) {
        return OffsetDateTime.now().minusHours(hours).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
