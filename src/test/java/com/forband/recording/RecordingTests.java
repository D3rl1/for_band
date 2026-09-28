package com.forband.recording;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 练习录音的**权限**（docs/08 §8.4 + docs/02 §2.3）。`SECURITY.md` A2 点名了
 * `GET /api/recordings/{id}` 与 `DELETE /api/recordings/{id}`，所以这一条一条全是越权路径：
 * 列表、详情、**文件流**（绕过界面的那条路）、点评、删除。
 */
class RecordingTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private long section2;
    private long memberAId;         // 声部 1 的团员
    private String memberAToken;
    private long memberBId;         // 声部 2 的团员
    private String memberBToken;
    private CurrentUser leader1;     // 声部 1 的声部长
    private String leader1Token;
    private CurrentUser leader2;     // 声部 2 的声部长
    private String leader2Token;
    private String conductorToken;
    private String leaderToken;      // 团长（Role.LEADER，无声部）——⚠️ 与上面 leader1/leader2（**声部长**）不是一回事
    private long honoraryId;
    private String honoraryInitial;
    private long recordingAId;       // A 的一条录音
    private long fileOfA;

    @BeforeEach
    void setUp() throws Exception {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        section2 = seedSection("弹拨声部");

        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAId = a.id();
        memberAToken = loginToken("memberA", a.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员乙", "memberB", section2, "MEMBER");
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());

        MemberCreateResponse l1 = createMember(admin, "声部长甲", "leaderA", section1, "SECTION_LEADER");
        leader1 = currentUserOf(l1.id());
        leader1Token = loginToken("leaderA", l1.initialPassword());
        MemberCreateResponse l2 = createMember(admin, "声部长乙", "leaderB", section2, "SECTION_LEADER");
        leader2 = currentUserOf(l2.id());
        leader2Token = loginToken("leaderB", l2.initialPassword());

        MemberCreateResponse c = createMember(admin, "指挥", "conductor", null, "CONDUCTOR");
        conductorToken = loginToken("conductor", c.initialPassword());

        MemberCreateResponse tz = createMember(admin, "团长", "leader", null, "LEADER");
        leaderToken = loginToken("leader", tz.initialPassword());

        MemberCreateResponse h = createMember(admin, "老团员", "honorary", section1, "MEMBER");
        honoraryId = h.id();
        honoraryInitial = h.initialPassword();
        setMemberStatus(admin, honoraryId, "GRADUATED");
        memberService.update(honoraryId, new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null, null), admin);

        fileOfA = uploadAudio(memberAToken, "take1.m4a");
        recordingAId = createRecording(memberAToken, fileOfA, "第一段");
    }

    // ---------- 上传 ----------

    @Test
    void uploader_without_section_can_still_upload() throws Exception {
        // 指挥没有声部（member.section_id 为 NULL）：section_id 可空，否则"上传自己的录音"这条会被堵死
        long file = uploadAudio(conductorToken, "cond.m4a");
        api(conductorToken, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"title\":\"指挥的示范\"}")
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject(
                "SELECT section_id FROM recording WHERE member_id = "
                        + "(SELECT id FROM member WHERE account = 'conductor')", Long.class));
    }

    @Test
    void section_id_is_filled_from_the_uploader() throws Exception {
        assertEquals(section1, jdbc.queryForObject(
                "SELECT section_id FROM recording WHERE id = ?", Long.class, recordingAId));
    }

    @Test
    void client_supplied_member_id_is_ignored() throws Exception {
        // 想冒名上传：DTO 里压根没有 memberId 字段 → 落库仍按登录身份（SECURITY.md A2 的 IDOR 防线）
        long file = uploadAudio(memberBToken, "fake.m4a");
        api(memberBToken, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"memberId\":" + memberAId + ",\"sectionId\":" + section1 + "}")
                .andExpect(status().isOk());
        assertEquals(memberBId, jdbc.queryForObject(
                "SELECT member_id FROM recording WHERE file_id = ?", Long.class, file));
        assertEquals(section2, jdbc.queryForObject(
                "SELECT section_id FROM recording WHERE file_id = ?", Long.class, file));
    }

    @Test
    void honorary_member_cannot_upload_403() throws Exception {
        String token = loginToken("honorary", honoraryInitial);
        long file = uploadAudio(token, "h.m4a");
        api(token, "POST", "/api/recordings", "{\"fileId\":" + file + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recording", Integer.class));
    }

    @Test
    void unknown_piece_or_file_is_rejected() throws Exception {
        api(memberAToken, "POST", "/api/recordings",
                "{\"fileId\":" + fileOfA + ",\"pieceId\":99999}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
        api(memberAToken, "POST", "/api/recordings", "{\"fileId\":99999}")
                .andExpect(status().isNotFound());
        api(memberAToken, "POST", "/api/recordings", "{}")
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 可见性：列表 / 详情 / 文件流 ----------

    @Test
    void member_sees_only_own_recordings() throws Exception {
        long fileB = uploadAudio(memberBToken, "b.m4a");
        createRecording(memberBToken, fileB, "乙的");

        api(memberAToken, "GET", "/api/recordings", null)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value((int) recordingAId));
        // 也不能靠筛选参数绕过：请求别人的 memberId 依然只回自己的那一条
        api(memberAToken, "GET", "/api/recordings?memberId=" + memberBId, null)
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void other_member_cannot_read_detail_or_file_stream() throws Exception {
        api(memberBToken, "GET", "/api/recordings/" + recordingAId, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // ⭐ 关键一条：界面藏了按钮也没用——**文件流那条路必须同样挡住**
        api(memberBToken, "GET", "/api/files/" + fileOfA, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 本人拿得到
        api(memberAToken, "GET", "/api/files/" + fileOfA, null).andExpect(status().isOk());
    }

    @Test
    void section_leader_scoped_to_own_section() throws Exception {
        // 本声部：看得到（详情 + 文件流 + 点评）
        api(leader1Token, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isOk());
        api(leader1Token, "GET", "/api/files/" + fileOfA, null).andExpect(status().isOk());
        api(leader1Token, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"注意气息\"}").andExpect(status().isOk());

        // 他声部的声部长：看不到、拿不到文件流、也点评不了
        api(leader2Token, "GET", "/api/recordings/" + recordingAId, null)
                .andExpect(status().isForbidden());
        api(leader2Token, "GET", "/api/files/" + fileOfA, null)
                .andExpect(status().isForbidden());
        api(leader2Token, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"x\"}").andExpect(status().isForbidden());
        // 列表里也看不到；他自己的声部那条反而看得到
        api(leader2Token, "GET", "/api/recordings", null)
                .andExpect(jsonPath("$.data.total").value(0));
        assertEquals(section1, leader1.sectionId());
        assertEquals(section2, leader2.sectionId());
    }

    @Test
    void member_can_comment_on_own_but_not_on_others() throws Exception {
        // docs/08 §8.4 的"双向"：声部长留言后团员要能追问 → 自己的录音下面可以说话
        api(memberAToken, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"老师这里我该用哪种指法？\"}")
                .andExpect(status().isOk());
        api(memberBToken, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"插一句\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void conduct_leader_admin_see_all() throws Exception {
        long fileB = uploadAudio(memberBToken, "b2.m4a");
        createRecording(memberBToken, fileB, "乙的");

        for (String token : new String[]{conductorToken, adminToken()}) {
            api(token, "GET", "/api/recordings", null)
                    .andExpect(jsonPath("$.data.total").value(2));
        }
        api(conductorToken, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isOk());
    }

    // ---------- 删除 ----------

    @Test
    void delete_follows_the_matrix() throws Exception {
        // 团员删别人的 → 403
        api(memberBToken, "DELETE", "/api/recordings/" + recordingAId, null)
                .andExpect(status().isForbidden());
        // 他声部声部长删 → 403
        api(leader2Token, "DELETE", "/api/recordings/" + recordingAId, null)
                .andExpect(status().isForbidden());
        // 本声部声部长删 → 200（docs/02 §2.3「声部长 ✅ 本声部」）
        api(leader1Token, "DELETE", "/api/recordings/" + recordingAId, null)
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recording", Integer.class));
    }

    @Test
    void owner_and_management_can_delete() throws Exception {
        // 本人删自己的
        long fileB = uploadAudio(memberBToken, "b3.m4a");
        long recB = createRecording(memberBToken, fileB, "乙的");
        api(memberBToken, "DELETE", "/api/recordings/" + recB, null).andExpect(status().isOk());

        // 管理员删别人的（「能删的三方」里声部长那一支在 delete_follows_the_matrix）
        long fileC = uploadAudio(memberAToken, "c.m4a");
        long recC = createRecording(memberAToken, fileC, "甲的");
        api(adminToken(), "DELETE", "/api/recordings/" + recC, null).andExpect(status().isOk());
    }

    /**
     * 反例（`AGENTS.md` 硬规矩：动了权限必须配反例）：**团长 / 指挥不能删任何人的录音**。
     * <p>
     * 规格 2026-09-23 已把「删除录音」由「管理员 / 团长 / 指挥」收窄为「**本人 / 本声部长 / 管理员**」
     * （`docs/02:104-107` 明写"口径以 `08` 为准"，`docs/08:88` 也反过来写死"不能删"），
     * **代码到 2026-09-26 才跟上**（`交接单.md` `#57`）。
     * ⚠️ 这条越权长期是绿灯，因为**上一条测试原先把"指挥删任意 → 200"断言成了预期行为**——
     * 断言"谁不能做"必须与规格的"谁不能做"同源，不能照代码的现状抄。
     */
    @Test
    void leader_and_conductor_cannot_delete_anyone_403_30001() throws Exception {
        // 先确认"本人"这一支对团长 / 指挥照常成立（他们删**自己上传的**仍然合法）
        long fileL = uploadAudio(leaderToken, "l1.m4a");
        long recL = createRecording(leaderToken, fileL, "团长的");
        api(leaderToken, "DELETE", "/api/recordings/" + recL, null).andExpect(status().isOk());

        long fileC1 = uploadAudio(conductorToken, "c1.m4a");
        long recC1 = createRecording(conductorToken, fileC1, "指挥的");
        api(conductorToken, "DELETE", "/api/recordings/" + recC1, null).andExpect(status().isOk());

        // 别人的（团员甲的）→ 两个角色都拒绝
        for (String token : new String[]{leaderToken, conductorToken}) {
            api(token, "DELETE", "/api/recordings/" + recordingAId, null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(30001));
        }
        // ⚠️ 反例要验"真的没被删掉"，只看响应码不够
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recording WHERE id = ?", Integer.class, recordingAId));

        // 别误伤：他们照旧**能听全部**（canView 一个字没动）
        api(leaderToken, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isOk());
        api(leaderToken, "GET", "/api/recordings", null)
                .andExpect(jsonPath("$.data.total").value(1));
        api(conductorToken, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isOk());
    }

    @Test
    void anonymous_is_401() throws Exception {
        api(null, "GET", "/api/recordings", null).andExpect(status().isUnauthorized());
        api(null, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isUnauthorized());
        api(null, "GET", "/api/files/" + fileOfA, null).andExpect(status().isUnauthorized());
    }

    @Test
    void honorary_member_sees_only_what_they_uploaded() throws Exception {
        String token = loginToken("honorary", honoraryInitial);
        // 没发过任何录音（也不能发）→ 列表空、看别人的一律 403
        api(token, "GET", "/api/recordings", null).andExpect(jsonPath("$.data.total").value(0));
        api(token, "GET", "/api/recordings/" + recordingAId, null).andExpect(status().isForbidden());
        api(token, "GET", "/api/files/" + fileOfA, null).andExpect(status().isForbidden());
    }

    @Test
    void comments_are_flat_and_ordered_by_time() throws Exception {
        api(leader1Token, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"first\"}").andExpect(status().isOk());
        api(memberAToken, "POST", "/api/recordings/" + recordingAId + "/comments",
                "{\"content\":\"second\"}").andExpect(status().isOk());
        api(memberAToken, "GET", "/api/recordings/" + recordingAId, null)
                .andExpect(jsonPath("$.data.comments.length()").value(2))
                .andExpect(jsonPath("$.data.comments[0].content").value("first"))
                .andExpect(jsonPath("$.data.comments[1].content").value("second"))
                .andExpect(jsonPath("$.data.recording.commentCount").value(2));
    }

    // ---------- 造数 ----------

    private static byte[] m4aBytes() {
        // ISO-BMFF 头：第 4~8 字节是 "ftyp" → 批 0 的嗅探认成 audio/mp4
        return new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0,
                0, 0, 0, 0, 'M', '4', 'A', ' ', 'm', 'p', '4', '2', 0, 0, 0, 0};
    }

    private long uploadAudio(String token, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "audio/mp4", m4aBytes());
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }

    private long createRecording(String token, long fileId, String title) throws Exception {
        api(token, "POST", "/api/recordings",
                "{\"fileId\":" + fileId + ",\"title\":\"" + title + "\",\"durationSec\":95}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM recording", Long.class);
    }

}
