package com.forband.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 录音管理视图 + 本周作业（docs/08 §8.3/§8.4、docs/02 §2.3）。
 * <p>
 * 重点不是"功能能用"，而是**几个容易写错的边界**：周的归属（周一 00:00 算不算本周）、
 * 三态怎么算（未关联的录音不算完成度）、声部长横向越权、历史周只读、
 * 有提交的作业改曲目/删除必须被拒。
 */
class RecordingAssignmentTests extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CurrentUser admin;
    private String adminToken;
    private long section1;
    private long section2;
    private long memberA1Id;        // 声部 1 团员
    private String memberA1Token;
    private long memberA2Id;        // 声部 1 第二个团员（用来凑 DONE）
    private String memberA2Token;
    private long memberBId;         // 声部 2 团员
    private String memberBToken;
    private CurrentUser leaderA;    // 声部 1 声部长（本身也属于"应录成员"）
    private String leaderAToken;
    private CurrentUser leaderB;
    private String leaderBToken;
    private String conductorToken;  // 指挥：没有声部，不参与作业
    private String leaderToken;     // 团长（Role.LEADER）

    @BeforeEach
    void setUp() throws Exception {
        cleanTables();
        admin = seedAdmin();
        adminToken = adminToken();
        section1 = seedSection("吹管声部");
        section2 = seedSection("弹拨声部");

        MemberCreateResponse a1 = createMember(admin, "团员甲", "memberA1", section1, "MEMBER");
        memberA1Id = a1.id();
        memberA1Token = loginToken("memberA1", a1.initialPassword());
        MemberCreateResponse a2 = createMember(admin, "团员乙", "memberA2", section1, "MEMBER");
        memberA2Id = a2.id();
        memberA2Token = loginToken("memberA2", a2.initialPassword());
        MemberCreateResponse b = createMember(admin, "团员丙", "memberB", section2, "MEMBER");
        memberBId = b.id();
        memberBToken = loginToken("memberB", b.initialPassword());

        MemberCreateResponse la = createMember(admin, "声部长甲", "leaderA", section1, "SECTION_LEADER");
        leaderA = currentUserOf(la.id());
        leaderAToken = loginToken("leaderA", la.initialPassword());
        MemberCreateResponse lb = createMember(admin, "声部长乙", "leaderB", section2, "SECTION_LEADER");
        leaderB = currentUserOf(lb.id());
        leaderBToken = loginToken("leaderB", lb.initialPassword());

        MemberCreateResponse c = createMember(admin, "指挥", "conductor", null, "CONDUCTOR");
        conductorToken = loginToken("conductor", c.initialPassword());
        MemberCreateResponse tz = createMember(admin, "团长", "leader", null, "LEADER");
        leaderToken = loginToken("leader", tz.initialPassword());

        // 应录名单要求 joined_at <= 本周一；新建成员默认 joined_at = 今天，
        // 测试若不在周一跑就会被漏掉 —— 统一回拨，去掉日期依赖。
        jdbc.update("UPDATE member SET joined_at = '2020-01-01'");
    }

    // ================================================================ 1. 布置权限

    @Test
    void section_leader_cannot_assign_another_section() throws Exception {
        api(leaderAToken, "POST", "/api/recordings/assignments",
                "{\"sectionId\":" + section2 + ",\"title\":\"长弓\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30003));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recording_assignment", Integer.class));

        api(leaderAToken, "POST", "/api/recordings/assignments",
                "{\"sectionId\":" + section1 + ",\"title\":\"单跳\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sectionId").value((int) section1));
    }

    @Test
    void admin_conductor_and_leader_can_assign_any_section() throws Exception {
        createAssignment(adminToken, section2, "管理员的作业", null);
        createAssignment(conductorToken, section1, "指挥的作业", null);
        createAssignment(leaderToken, section2, "团长的作业", null);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM recording_assignment", Integer.class));
    }

    // ================================================================ 2. 三态

    @Test
    void tri_state_counts_only_linked_recordings() throws Exception {
        long basic = createAssignment(adminToken, section1, "长音基本功", null);
        long etude = createAssignment(adminToken, section1, "练习曲", null);

        // 甲：交 1 个作业 + 1 条没关联的录音 → PARTIAL，未关联数 1
        linkedRecording(memberA1Token, basic);
        unlinkedRecording(memberA1Token, "随手录的");
        // 乙：两个作业都交 → DONE
        linkedRecording(memberA2Token, basic);
        linkedRecording(memberA2Token, etude);
        // 声部长甲：一条都没交 → MISS（他在应录名单里）
        // 声部 2 没布置作业，丙传了也没用 → state = null（不适用，不是"未交"）
        unlinkedRecording(memberBToken, "声部二的");

        JsonNode view = manage(adminToken, null);

        JsonNode partial = memberRow(view, section1, memberA1Id);
        assertEquals("PARTIAL", partial.path("state").asText());
        assertEquals(1, partial.path("doneCount").asInt());
        assertEquals(2, partial.path("totalCount").asInt());
        assertEquals(1, partial.path("unlinkedCount").asInt());
        assertEquals(2, partial.path("recordings").size());

        JsonNode done = memberRow(view, section1, memberA2Id);
        assertEquals("DONE", done.path("state").asText());
        assertEquals(2, done.path("doneCount").asInt());
        assertEquals(0, done.path("unlinkedCount").asInt());

        JsonNode miss = memberRow(view, section1, leaderA.memberId());
        assertEquals("MISS", miss.path("state").asText());
        assertEquals(0, miss.path("recordings").size());

        JsonNode noAssignment = memberRow(view, section2, memberBId);
        assertTrue(noAssignment.path("state").isNull());
        assertEquals(0, noAssignment.path("totalCount").asInt());
        assertEquals(1, noAssignment.path("recordings").size());
    }

    @Test
    void submission_count_dedupes_by_member() throws Exception {
        long assignment = createAssignment(adminToken, section1, "长音基本功", null);
        // 同一个人交两条 → 管理视图里的 submittedCount 仍是 1（按 distinct member_id）
        linkedRecording(memberA1Token, assignment);
        linkedRecording(memberA1Token, assignment);

        JsonNode view = manage(adminToken, null);
        JsonNode row = assignmentRow(view, assignment);
        assertEquals(1, row.path("submittedCount").asInt());
    }

    // ================================================================ 3. 周界

    @Test
    void week_boundary_is_monday_0000_to_sunday_2359() throws Exception {
        LocalDate monday = RecordingAssignmentService.currentWeekStart();
        long mondayEdge = unlinkedRecording(memberA1Token, "周一零点");
        long sundayEdge = unlinkedRecording(memberA1Token, "周日最后一秒");
        long nextMonday = unlinkedRecording(memberA1Token, "下周一零点");
        setUploadedAt(mondayEdge, monday.atStartOfDay());
        setUploadedAt(sundayEdge, monday.plusDays(6).atTime(23, 59, 59));
        setUploadedAt(nextMonday, monday.plusDays(7).atStartOfDay());

        JsonNode current = manage(adminToken, null);
        assertEquals(monday.toString(), current.path("weekStart").asText());
        assertTrue(current.path("current").asBoolean());
        // 周一 00:00 与周日 23:59:59 属于本周；下周一 00:00 属于下一周（左闭右开）
        assertEquals(2, memberRow(current, section1, memberA1Id).path("recordings").size());

        // 传周中任意一天都要归一到周一
        JsonNode midweek = manage(adminToken, monday.plusDays(3));
        assertEquals(monday.toString(), midweek.path("weekStart").asText());

        JsonNode next = manage(adminToken, monday.plusDays(7));
        assertEquals(monday.plusDays(7).toString(), next.path("weekStart").asText());
        assertEquals(false, next.path("current").asBoolean());
        assertEquals(1, memberRow(next, section1, memberA1Id).path("recordings").size());
    }

    // ================================================================ 4. 改 / 删

    @Test
    void assignment_with_submissions_can_only_change_title() throws Exception {
        long piece1 = seedPiece("曲目一");
        long piece2 = seedPiece("曲目二");
        long assignment = createAssignment(adminToken, section1, "原作业", piece1);
        linkedRecording(memberA1Token, assignment);

        api(adminToken, "PUT", "/api/recordings/assignments/" + assignment,
                "{\"title\":\"改名\",\"pieceId\":" + piece2 + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("只能修改标题")));

        api(adminToken, "PUT", "/api/recordings/assignments/" + assignment,
                "{\"title\":\"改名\",\"pieceId\":" + piece1 + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("改名"));
        assertEquals("改名", jdbc.queryForObject(
                "SELECT title FROM recording_assignment WHERE id = ?", String.class, assignment));

        api(adminToken, "DELETE", "/api/recordings/assignments/" + assignment, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("1 人")));

        JsonNode view = manage(adminToken, null);
        assertEquals(false, assignmentRow(view, assignment).path("canDelete").asBoolean());
        assertEquals(1, assignmentRow(view, assignment).path("submittedCount").asInt());
    }

    @Test
    void assignment_without_submissions_is_deletable() throws Exception {
        long assignment = createAssignment(adminToken, section1, "没人交的作业", null);
        JsonNode view = manage(adminToken, null);
        assertEquals(true, assignmentRow(view, assignment).path("canDelete").asBoolean());

        api(adminToken, "DELETE", "/api/recordings/assignments/" + assignment, null)
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recording_assignment WHERE id = ?", Integer.class, assignment));
    }

    // ================================================================ 5. 历史周只读

    @Test
    void historical_week_is_read_only() throws Exception {
        LocalDate lastWeek = RecordingAssignmentService.currentWeekStart().minusWeeks(1);
        long historical = insertAssignment(lastWeek, section1, "上周的作业", null);

        api(adminToken, "PUT", "/api/recordings/assignments/" + historical,
                "{\"title\":\"偷改历史\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("历史周")));
        api(adminToken, "DELETE", "/api/recordings/assignments/" + historical, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("历史周")));

        long lastWeekRecording = unlinkedRecording(memberA1Token, "上周的录音");
        setUploadedAt(lastWeekRecording, lastWeek.plusDays(2).atTime(10, 0));

        JsonNode view = manage(adminToken, lastWeek);
        assertEquals(lastWeek.toString(), view.path("weekStart").asText());
        assertEquals(false, view.path("current").asBoolean());
        assertEquals(0, view.path("assignments").size());
        JsonNode row = memberRow(view, section1, memberA1Id);
        assertTrue(row.path("state").isNull(), "历史周没有完成度");
        assertEquals(0, row.path("totalCount").asInt());
        assertEquals(1, row.path("recordings").size());
    }

    // ================================================================ 6. 上传关联

    @Test
    void upload_without_assignment_still_works() throws Exception {
        long file = uploadAudio(memberA1Token, "plain.m4a");
        api(memberA1Token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"title\":\"没关联\"}")
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT assignment_id FROM recording WHERE file_id = ?",
                Long.class, file));
    }

    @Test
    void upload_with_assignment_backfills_piece_and_ignores_client_piece() throws Exception {
        long piece1 = seedPiece("作业指定曲目");
        long piece2 = seedPiece("客户端乱传的曲目");
        long assignment = createAssignment(adminToken, section1, "本周作业", piece1);

        long file = uploadAudio(memberA1Token, "linked.m4a");
        api(memberA1Token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"title\":\"关联了\",\"assignmentId\":" + assignment
                        + ",\"pieceId\":" + piece2 + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignmentId").value((int) assignment))
                .andExpect(jsonPath("$.data.assignmentTitle").value("本周作业"));

        assertEquals(assignment, jdbc.queryForObject(
                "SELECT assignment_id FROM recording WHERE file_id = ?", Long.class, file));
        // 曲目以作业为准：客户端传的 piece2 必须被无视
        assertEquals(piece1, jdbc.queryForObject(
                "SELECT piece_id FROM recording WHERE file_id = ?", Long.class, file));
    }

    @Test
    void upload_rejects_foreign_historical_and_missing_assignments() throws Exception {
        long otherSection = createAssignment(adminToken, section2, "别声部的作业", null);
        LocalDate lastWeek = RecordingAssignmentService.currentWeekStart().minusWeeks(1);
        long historical = insertAssignment(lastWeek, section1, "上周的作业", null);
        long ownSection = createAssignment(adminToken, section1, "本声部的作业", null);

        assertUploadRejected(memberA1Token, otherSection, "本声部");
        assertUploadRejected(memberA1Token, historical, "本周");
        assertUploadRejected(memberA1Token, 999_999L, "不存在");
        // 指挥没有声部 → 同样不能关联任何声部作业（否则完成度会算到别人头上）
        assertUploadRejected(conductorToken, ownSection, "本声部");

        // 对照：本声部本周的合法作业能过
        long file = uploadAudio(memberA1Token, "ok.m4a");
        api(memberA1Token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"assignmentId\":" + ownSection + "}")
                .andExpect(status().isOk());
    }

    // ================================================================ 7. 角色门控

    @Test
    void plain_member_cannot_use_management_endpoints() throws Exception {
        long assignment = createAssignment(adminToken, section1, "作业", null);

        api(memberA1Token, "GET", "/api/recordings/manage", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        api(memberA1Token, "GET", "/api/recordings/manage/weeks", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        api(memberA1Token, "POST", "/api/recordings/assignments",
                "{\"sectionId\":" + section1 + ",\"title\":\"我也想布置\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        api(memberA1Token, "PUT", "/api/recordings/assignments/" + assignment,
                "{\"title\":\"改作业\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
        api(memberA1Token, "DELETE", "/api/recordings/assignments/" + assignment, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void current_assignments_are_scoped_to_own_section_and_week() throws Exception {
        long mine = createAssignment(adminToken, section1, "本声部作业", null);
        createAssignment(adminToken, section2, "别声部作业", null);
        LocalDate lastWeek = RecordingAssignmentService.currentWeekStart().minusWeeks(1);
        insertAssignment(lastWeek, section1, "上周作业", null);

        api(memberA1Token, "GET", "/api/recordings/assignments/current", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) mine))
                .andExpect(jsonPath("$.data[0].title").value("本声部作业"))
                .andExpect(jsonPath("$.data[0].linked").value(false));

        linkedRecording(memberA1Token, mine);
        api(memberA1Token, "GET", "/api/recordings/assignments/current", null)
                .andExpect(jsonPath("$.data[0].linked").value(true));

        // 指挥没有声部 → 空列表，不是 403
        api(conductorToken, "GET", "/api/recordings/assignments/current", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ================================================================ 8. 历史周列表

    @Test
    void weeks_are_grouped_descending_and_scoped_for_section_leaders() throws Exception {
        LocalDate monday = RecordingAssignmentService.currentWeekStart();
        LocalDate lastWeek = monday.minusWeeks(1);
        LocalDate twoWeeksAgo = monday.minusWeeks(2);

        setUploadedAt(unlinkedRecording(memberA1Token, "本周"), monday.plusDays(1).atTime(9, 0));
        setUploadedAt(unlinkedRecording(memberA1Token, "上周一"), lastWeek.plusDays(0).atTime(9, 0));
        setUploadedAt(unlinkedRecording(memberBToken, "上周二"), lastWeek.plusDays(1).atTime(9, 0));
        setUploadedAt(unlinkedRecording(memberA1Token, "两周前"), twoWeeksAgo.plusDays(2).atTime(9, 0));

        api(adminToken, "GET", "/api/recordings/manage/weeks", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].weekStart").value(lastWeek.toString()))
                .andExpect(jsonPath("$.data[0].count").value(2))
                .andExpect(jsonPath("$.data[0].sectionCount").value(2))
                .andExpect(jsonPath("$.data[1].weekStart").value(twoWeeksAgo.toString()))
                .andExpect(jsonPath("$.data[1].count").value(1))
                .andExpect(jsonPath("$.data[1].sectionCount").value(1));

        // 声部长只数本声部：上周他自己声部只有 1 条
        api(leaderAToken, "GET", "/api/recordings/manage/weeks", null)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].count").value(1))
                .andExpect(jsonPath("$.data[0].sectionCount").value(1));
        // 声部 2 只有上周有录音 → 只有一条
        api(leaderBToken, "GET", "/api/recordings/manage/weeks", null)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].count").value(1));
    }

    @Test
    void section_leader_manage_sees_only_own_section() throws Exception {
        long own = createAssignment(adminToken, section1, "本声部作业", null);
        createAssignment(adminToken, section2, "别声部作业", null);
        unlinkedRecording(memberA1Token, "声部一");
        unlinkedRecording(memberBToken, "声部二");

        JsonNode view = manage(leaderAToken, null);
        assertEquals(1, view.path("assignments").size());
        assertEquals(own, view.path("assignments").get(0).path("id").asLong());
        assertEquals(1, view.path("sections").size());
        assertEquals(section1, view.path("sections").get(0).path("sectionId").asLong());
    }

    // ================================================================ 造数

    private static byte[] m4aBytes() {
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

    private long unlinkedRecording(String token, String title) throws Exception {
        long file = uploadAudio(token, "r.m4a");
        api(token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"title\":\"" + title + "\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM recording", Long.class);
    }

    private long linkedRecording(String token, long assignmentId) throws Exception {
        long file = uploadAudio(token, "r.m4a");
        api(token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"assignmentId\":" + assignmentId + "}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM recording", Long.class);
    }

    private void assertUploadRejected(String token, long assignmentId, String messagePart)
            throws Exception {
        long file = uploadAudio(token, "bad.m4a");
        api(token, "POST", "/api/recordings",
                "{\"fileId\":" + file + ",\"assignmentId\":" + assignmentId + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", containsString(messagePart)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recording WHERE file_id = ?", Integer.class, file),
                "被拒的上传不应落库");
    }

    private void setUploadedAt(long recordingId, LocalDateTime time) {
        jdbc.update("UPDATE recording SET uploaded_at = ? WHERE id = ?", time, recordingId);
    }

    private long seedPiece(String title) {
        jdbc.update("INSERT INTO piece (title, status, created_by, created_at, updated_at) "
                        + "VALUES (?, 'REHEARSING', ?, NOW(), NOW())",
                title, admin.memberId());
        return jdbc.queryForObject("SELECT MAX(id) FROM piece", Long.class);
    }

    private long createAssignment(String token, long sectionId, String title, Long pieceId)
            throws Exception {
        String body = "{\"sectionId\":" + sectionId + ",\"title\":\"" + title + "\""
                + (pieceId == null ? "" : ",\"pieceId\":" + pieceId) + "}";
        JsonNode response = JSON.readTree(api(token, "POST", "/api/recordings/assignments", body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return response.path("data").path("id").asLong();
    }

    /** 历史上（非本周）的作业只能直接落库：接口只允许布置本周。 */
    private long insertAssignment(LocalDate weekStart, long sectionId, String title, Long pieceId) {
        jdbc.update("INSERT INTO recording_assignment "
                        + "(week_start, section_id, title, piece_id, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                weekStart, sectionId, title, pieceId, admin.memberId());
        return jdbc.queryForObject("SELECT MAX(id) FROM recording_assignment", Long.class);
    }

    private JsonNode manage(String token, LocalDate weekStart) throws Exception {
        String url = "/api/recordings/manage"
                + (weekStart == null ? "" : "?weekStart=" + weekStart);
        JsonNode response = JSON.readTree(api(token, "GET", url, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return response.path("data");
    }

    private static JsonNode memberRow(JsonNode view, long sectionId, long memberId) {
        for (JsonNode section : view.path("sections")) {
            if (section.path("sectionId").asLong() == sectionId) {
                for (JsonNode member : section.path("members")) {
                    if (member.path("memberId").asLong() == memberId) {
                        return member;
                    }
                }
            }
        }
        throw new AssertionError("管理视图里找不到 section=" + sectionId + " 的 member=" + memberId);
    }

    private static JsonNode assignmentRow(JsonNode view, long assignmentId) {
        for (JsonNode assignment : view.path("assignments")) {
            if (assignment.path("id").asLong() == assignmentId) {
                return assignment;
            }
        }
        throw new AssertionError("管理视图里找不到作业 id=" + assignmentId);
    }
}
