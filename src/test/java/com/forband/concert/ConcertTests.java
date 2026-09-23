package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演出档案（docs/07 + docs/02 §2.3）：看＝全团（含荣誉团员）；档案/曲目单/名单的改动＝管理员·团长·指挥；
 * **照片上传是全员、删除限本人或管理员**；**参演名单可以放已毕业的人**（"毕业了回团参演"）。
 */
class ConcertTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private String memberAToken;
    private long memberAId;
    private String memberBToken;
    private String conductorToken;
    private long honoraryId;
    private String honoraryInitial;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", section1, "MEMBER");
        memberBToken = loginToken("memberB", b.initialPassword());
        MemberCreateResponse c = createMember(admin, "指挥", "conductor", null, "CONDUCTOR");
        conductorToken = loginToken("conductor", c.initialPassword());

        MemberCreateResponse h = createMember(admin, "老团长", "honorary", section1, "MEMBER");
        honoraryId = h.id();
        honoraryInitial = h.initialPassword();   // 先取：首登会把 initial_password 清空
        setMemberStatus(admin, honoraryId, "GRADUATED");
        memberService.update(honoraryId, new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null), admin);
    }

    @Test
    void conductor_can_create_and_everyone_can_read() throws Exception {
        long id = createConcert("仲夏音乐会");
        api(memberAToken, "GET", "/api/concerts/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concert.name").value("仲夏音乐会"))
                .andExpect(jsonPath("$.data.concert.statusLabel").value("计划中"));
        api(memberAToken, "GET", "/api/concerts", null)
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void member_cannot_edit_archive_program_or_cast_403() throws Exception {
        long id = createConcert("仲夏音乐会");
        api(memberAToken, "PUT", "/api/concerts/" + id, "{\"name\":\"x\",\"date\":\"2026-01-01\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(memberAToken, "PUT", "/api/concerts/" + id + "/program", "{\"items\":[]}")
                .andExpect(status().isForbidden());
        api(memberAToken, "PUT", "/api/concerts/" + id + "/cast", "{\"members\":[]}")
                .andExpect(status().isForbidden());
    }

    @Test
    void program_comes_from_the_piece_library_only() throws Exception {
        long id = createConcert("仲夏音乐会");
        long pieceId = createPiece("金蛇狂舞");

        api(conductorToken, "PUT", "/api/concerts/" + id + "/program",
                "{\"items\":[{\"pieceId\":" + pieceId + ",\"conductor\":\"李老师\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value(1))
                .andExpect(jsonPath("$.data[0].title").value("金蛇狂舞"))
                .andExpect(jsonPath("$.data[0].conductor").value("李老师"));

        // 不在曲目库里的 id → 拒绝（曲目单**不自由填写**）
        api(conductorToken, "PUT", "/api/concerts/" + id + "/program",
                "{\"items\":[{\"pieceId\":99999}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void cast_accepts_graduated_and_honorary_members() throws Exception {
        long id = createConcert("仲夏音乐会");
        api(conductorToken, "PUT", "/api/concerts/" + id + "/cast",
                "{\"members\":[{\"memberId\":" + memberAId + "},"
                        + "{\"memberId\":" + honoraryId + ",\"note\":\"特邀\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        // 荣誉团员照样进得了名单（不计考勤、不能自己报名，但"回团参演"是允许的）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM concert_cast WHERE member_id = ?", Integer.class, honoraryId));
        assertEquals("特邀", jdbc.queryForObject(
                "SELECT note FROM concert_cast WHERE member_id = ?", String.class, honoraryId));
        String body = api(memberAToken, "GET", "/api/concerts/" + id, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("荣誉团员"), "名单里要带出他的角色");
        assertTrue(body.contains("老团长"));
    }

    @Test
    void photos_are_writable_by_anyone_but_only_owner_or_admin_can_delete() throws Exception {
        long id = createConcert("仲夏音乐会");
        long fileA = uploadPhoto(memberAToken, "a.png");
        long photoId = addPhoto(memberAToken, id, fileA);

        // 别人删不了（上传者本人 / 管理员才行）
        api(memberBToken, "DELETE", "/api/concerts/" + id + "/photos/" + photoId, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 管理员可以
        api(adminToken(), "DELETE", "/api/concerts/" + id + "/photos/" + photoId, null)
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM concert_photo", Integer.class));

        // 上传者本人也能删自己传的
        long photoId2 = addPhoto(memberAToken, id, uploadPhoto(memberAToken, "b.png"));
        api(memberAToken, "DELETE", "/api/concerts/" + id + "/photos/" + photoId2, null)
                .andExpect(status().isOk());
    }

    @Test
    void program_sheet_is_printable_html_and_escapes_text() throws Exception {
        long id = createConcert("仲夏<script>音乐会");
        long pieceId = createPiece("金蛇<狂舞>");
        api(conductorToken, "PUT", "/api/concerts/" + id + "/program",
                "{\"items\":[{\"pieceId\":" + pieceId + ",\"conductor\":\"李老师\"}]}")
                .andExpect(status().isOk());

        String html = api(memberAToken, "GET", "/api/concerts/" + id + "/program.html", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("金蛇&lt;狂舞&gt;"), "曲名里的尖括号必须转义（SECURITY.md C3）");
        assertTrue(html.contains("李老师"));
        // 未登录 → 401（`SECURITY.md` A4）
        api(null, "GET", "/api/concerts/" + id + "/program.html", null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void honorary_member_can_view_archives_but_not_edit() throws Exception {
        long id = createConcert("仲夏音乐会");
        api(honoraryToken(), "GET", "/api/concerts/" + id, null).andExpect(status().isOk());
        api(honoraryToken(), "PUT", "/api/concerts/" + id, "{\"name\":\"x\",\"date\":\"2026-01-01\"}")
                .andExpect(status().isForbidden());
    }

    // ---------- 造数 ----------

    private String honoraryToken() {
        return loginToken("honorary", honoraryInitial);
    }

    private long createConcert(String name) throws Exception {
        api(conductorToken, "POST", "/api/concerts",
                "{\"name\":\"" + name + "\",\"date\":\"" + LocalDate.now() + "\","
                        + "\"venue\":\"音乐厅\",\"videoUrl\":\"https://example.com/v\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM concert", Long.class);
    }

    private long createPiece(String title) throws Exception {
        api(conductorToken, "POST", "/api/pieces", "{\"title\":\"" + title + "\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM piece", Long.class);
    }

    private long uploadPhoto(String token, String filename) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0,
                0, 0, 0, 13};
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", png);
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }

    private long addPhoto(String token, long concertId, long fileId) throws Exception {
        api(token, "POST", "/api/concerts/" + concertId + "/photos",
                "{\"fileId\":" + fileId + ",\"caption\":\"合影\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM concert_photo", Long.class);
    }
}
