package com.forband.releasenote;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 更新公告（docs/15 §15.5）：没有公告时 latest 是 null、只有管理员能发、发布后不可改不可删、
 * 荣誉团员**能看**（对防腐条款的有意例外）。
 */
class ReleaseNoteTests extends IntegrationTestBase {

    private CurrentUser admin;
    private String memberAToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        long section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAToken = loginToken("memberA", a.initialPassword());
    }

    @Test
    void latest_is_null_when_nothing_published() throws Exception {
        // 一条都没有 → data 是 null（前端据此"不弹、不占位、不出空层"）
        api(memberAToken, "GET", "/api/release-notes/latest", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        api(memberAToken, "GET", "/api/release-notes", null)
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void admin_publishes_then_latest_and_history_agree() throws Exception {
        // ⚠️ 换行在 JSON 里必须写成 `\n`（Java 字符串里是 `\\n`）——直接放真换行是非法 JSON，会 400
        api(adminToken(), "POST", "/api/admin/release-notes",
                "{\"title\":\"本次更新：签到加了确认\",\"content\":\"第一行\\n第二行\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedByName").value("测试管理员"));

        api(memberAToken, "GET", "/api/release-notes/latest", null)
                .andExpect(jsonPath("$.data.title").value("本次更新：签到加了确认"))
                .andExpect(jsonPath("$.data.content").value("第一行\n第二行"));   // 纯文本，换行原样

        api(adminToken(), "POST", "/api/admin/release-notes",
                "{\"title\":\"第二条\",\"content\":\"b\"}").andExpect(status().isOk());
        // latest = id 最大那条；历史 id 倒序
        api(memberAToken, "GET", "/api/release-notes/latest", null)
                .andExpect(jsonPath("$.data.title").value("第二条"));
        api(memberAToken, "GET", "/api/release-notes?size=10", null)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].title").value("第二条"))
                .andExpect(jsonPath("$.data.list[1].title").value("本次更新：签到加了确认"));
    }

    @Test
    void only_admin_can_publish_403_30001() throws Exception {
        api(memberAToken, "POST", "/api/admin/release-notes", "{\"title\":\"x\",\"content\":\"y\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_note", Integer.class));
    }

    @Test
    void blank_title_or_content_422_10001() throws Exception {
        api(adminToken(), "POST", "/api/admin/release-notes", "{\"title\":\"\",\"content\":\"y\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(adminToken(), "POST", "/api/admin/release-notes", "{\"title\":\"x\",\"content\":\"  \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void published_note_is_immutable() throws Exception {
        api(adminToken(), "POST", "/api/admin/release-notes", "{\"title\":\"x\",\"content\":\"y\"}")
                .andExpect(status().isOk());
        // **发布后不可改不可删**——要改就再发一条（docs/13 §13.3㉒）。
        // 这里刻意只有 POST：带 id 的路径压根没有 handler → 404，说明"没有改/删这回事"。
        // （集合路径上换个方法本来该是 405，但会被 GlobalExceptionHandler 的兜底吞成 500——
        //   那是既有行为，已记进移交事项，不在这条测试里替规格定义 405 该回什么码。）
        api(adminToken(), "PUT", "/api/admin/release-notes/1", "{\"title\":\"改\"}")
                .andExpect(status().isNotFound());
        api(adminToken(), "DELETE", "/api/admin/release-notes/1", null)
                .andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_note", Integer.class));
    }

    @Test
    void published_at_is_written_by_server() throws Exception {
        api(adminToken(), "POST", "/api/admin/release-notes", "{\"title\":\"x\",\"content\":\"y\"}")
                .andExpect(status().isOk());
        Object published = jdbc.queryForObject("SELECT published_at FROM release_note", Object.class);
        assertFalse(published == null, "published_at 是业务时间，必须显式写（不能靠自动填充）");
    }
}
