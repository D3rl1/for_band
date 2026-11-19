package com.forband.file;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储用量（docs/08 §8.3/§8.5）：**仅管理员**可见、数值要对得上、超阈值要提醒。
 * <p>
 * ⚠️ 这里用 `@TestPropertySource` 把警戒线压到 **1 字节**，这样"超阈值"这条分支是**确定可测**的
 * （默认 2GB 的话永远测不到 true）——它会让 Spring 另起一个上下文，慢一点，但换来确定性。
 */
@TestPropertySource(properties = "band.storage.warn-bytes=1")
class StorageUsageTests extends IntegrationTestBase {

    private CurrentUser admin;
    private String memberAToken;
    private String leaderToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        long section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse l = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        leaderToken = loginToken("tuanzhang", l.initialPassword());
    }

    @Test
    void only_admin_can_read_and_anonymous_is_401() throws Exception {
        api(adminToken(), "GET", "/api/admin/storage", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 团员 / 团长都不行：这是系统维护数据，不是业务信息
        api(memberAToken, "GET", "/api/admin/storage", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(leaderToken, "GET", "/api/admin/storage", null)
                .andExpect(status().isForbidden());
        api(null, "GET", "/api/admin/storage", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void numbers_match_what_is_actually_stored() throws Exception {
        // 空库：全 0
        api(adminToken(), "GET", "/api/admin/storage", null)
                .andExpect(jsonPath("$.data.totalBytes").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.thresholdBytes").value(1))
                .andExpect(jsonPath("$.data.overThreshold").value(false));

        // 传两个文件，只有一个真的挂到曲目上 → **总数按磁盘上全部算**，分类只算挂上去的那个
        long orphan = upload("orphan.pdf");
        long attached = upload("score.pdf");
        long pieceId = createPiece();
        api(adminToken(), "POST", "/api/pieces/" + pieceId + "/attachments",
                "{\"fileId\":" + attached + ",\"kind\":\"SCORE\"}")
                .andExpect(status().isOk());

        long total = jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM file", Long.class);
        long attachedSize = jdbc.queryForObject(
                "SELECT size_bytes FROM file WHERE id = ?", Long.class, attached);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM file", Integer.class));
        assertTrue(orphan > 0);

        api(adminToken(), "GET", "/api/admin/storage", null)
                .andExpect(jsonPath("$.data.totalBytes").value((int) total))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.byKind[0].kind").value("PIECE_ATTACHMENT"))
                .andExpect(jsonPath("$.data.byKind[0].label").value("曲目附件"))
                .andExpect(jsonPath("$.data.byKind[0].bytes").value((int) attachedSize))
                .andExpect(jsonPath("$.data.byKind[0].count").value(1))
                .andExpect(jsonPath("$.data.byKind[1].count").value(0))
                .andExpect(jsonPath("$.data.byKind[2].count").value(0));
    }

    @Test
    void over_threshold_is_true_when_usage_exceeds_the_limit() throws Exception {
        upload("any.pdf");   // 警戒线是 1 字节 → 只要有东西就超
        api(adminToken(), "GET", "/api/admin/storage", null)
                .andExpect(jsonPath("$.data.overThreshold").value(true))
                .andExpect(jsonPath("$.data.thresholdBytes").value(1));
    }

    // ---------- 造数 ----------

    private long upload(String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf",
                ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n"
                        + " ".repeat(64)).getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }

    private long createPiece() throws Exception {
        api(adminToken(), "POST", "/api/pieces", "{\"title\":\"金蛇狂舞\"}").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM piece", Long.class);
    }
}
