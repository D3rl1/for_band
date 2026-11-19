package com.forband.piece;

import com.forband.auth.CurrentUser;
import com.forband.event.dto.EventCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 曲目库（docs/06 + docs/02 §2.3）：看＝登录（含荣誉团员）、增删改＝管理员/团长/指挥；
 * **荣誉团员看得到曲名、拿不到附件**（`08` §8.5 的 403 口径）。
 */
class PieceTests extends IntegrationTestBase {

    private CurrentUser admin;
    private long section1;
    private String memberAToken;
    private String honoraryToken;
    private String tuanzhangToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        section1 = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section1, "MEMBER");
        memberAToken = loginToken("memberA", a.initialPassword());

        MemberCreateResponse tz = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        tuanzhangToken = loginToken("tuanzhang", tz.initialPassword());

        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", section1, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null, null, null), admin);
        honoraryToken = loginToken("honorary", h.initialPassword());
    }

    @Test
    void leader_can_create_then_member_can_read() throws Exception {
        long id = createPiece(tuanzhangToken, "金蛇狂舞");
        api(memberAToken, "GET", "/api/pieces/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.piece.title").value("金蛇狂舞"))
                .andExpect(jsonPath("$.data.piece.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.piece.statusLabel").value("备选"));
        // 荣誉团员也看得到曲名（只减不增里"曲目元数据"是给的）
        api(honoraryToken, "GET", "/api/pieces/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.piece.title").value("金蛇狂舞"));
    }

    @Test
    void member_cannot_create_or_update_403() throws Exception {
        long id = createPiece(tuanzhangToken, "金蛇狂舞");
        api(memberAToken, "POST", "/api/pieces", "{\"title\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        api(memberAToken, "PUT", "/api/pieces/" + id, "{\"title\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 荣誉团员更不能改（"上传/修改/删除任何内容 ❌ 一律不可"）
        api(honoraryToken, "POST", "/api/pieces", "{\"title\":\"x\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void list_filters_by_status_and_keyword() throws Exception {
        createPiece(tuanzhangToken, "金蛇狂舞");
        long rehearsing = createPiece(tuanzhangToken, "茉莉花");
        api(tuanzhangToken, "PUT", "/api/pieces/" + rehearsing, "{\"title\":\"茉莉花\",\"status\":\"REHEARSING\"}")
                .andExpect(status().isOk());

        api(memberAToken, "GET", "/api/pieces?status=REHEARSING", null)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].title").value("茉莉花"));
        api(memberAToken, "GET", "/api/pieces?keyword=金蛇", null)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].title").value("金蛇狂舞"));
        api(memberAToken, "GET", "/api/pieces?status=NOPE", null)
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void bad_status_is_rejected() throws Exception {
        api(tuanzhangToken, "POST", "/api/pieces", "{\"title\":\"x\",\"status\":\"ARCHIVED\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void attachments_go_through_the_shared_file_endpoint() throws Exception {
        long pieceId = createPiece(tuanzhangToken, "金蛇狂舞");
        long fileId = uploadFile(tuanzhangToken, "score.pdf", pdfBytes());

        api(tuanzhangToken, "POST", "/api/pieces/" + pieceId + "/attachments",
                "{\"fileId\":" + fileId + ",\"kind\":\"SCORE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("SCORE"))
                .andExpect(jsonPath("$.data.originalName").value("score.pdf"));

        // 详情里带出附件；团员可取（登录即可下载）
        api(memberAToken, "GET", "/api/pieces/" + pieceId, null)
                .andExpect(jsonPath("$.data.attachments.length()").value(1));
        api(memberAToken, "GET", "/api/files/" + fileId, null).andExpect(status().isOk());

        // ⭐ 荣誉团员：**曲名看得到，附件一律 403**（docs/06 §6.4 + docs/08 §8.5）
        api(honoraryToken, "GET", "/api/files/" + fileId, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));

        // 附件类型白名单
        api(tuanzhangToken, "POST", "/api/pieces/" + pieceId + "/attachments",
                "{\"fileId\":" + fileId + ",\"kind\":\"VIDEO\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(10001));

        // 删附件：团员不行，团长行
        long attachmentId = jdbc.queryForObject(
                "SELECT id FROM piece_attachment WHERE piece_id = ?", Long.class, pieceId);
        api(memberAToken, "DELETE", "/api/pieces/" + pieceId + "/attachments/" + attachmentId, null)
                .andExpect(status().isForbidden());
        api(tuanzhangToken, "DELETE", "/api/pieces/" + pieceId + "/attachments/" + attachmentId, null)
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_attachment", Integer.class));
    }

    @Test
    void event_can_be_linked_to_pieces_and_members_can_see_them() throws Exception {
        long first = createPiece(tuanzhangToken, "金蛇狂舞");
        long second = createPiece(tuanzhangToken, "茉莉花");
        long eventId = event(startsInMinutes(60));

        api(tuanzhangToken, "PUT", "/api/events/" + eventId + "/pieces",
                "{\"pieceIds\":[" + second + "," + first + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].pieceId").value((int) second))
                .andExpect(jsonPath("$.data[0].orderNo").value(1));

        api(memberAToken, "GET", "/api/events/" + eventId + "/pieces", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM event_piece", Integer.class));

        // 整体替换：再设一次只剩一首
        api(tuanzhangToken, "PUT", "/api/events/" + eventId + "/pieces",
                "{\"pieceIds\":[" + first + "]}").andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_piece", Integer.class));

        // 团员不能改活动的曲目单（"能改这场活动的人才能改它练什么曲"）
        api(memberAToken, "PUT", "/api/events/" + eventId + "/pieces", "{\"pieceIds\":[]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
    }

    // ---------- 造数 ----------

    private long createPiece(String token, String title) throws Exception {
        api(token, "POST", "/api/pieces", "{\"title\":\"" + title + "\",\"composer\":\"民歌\"}")
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM piece", Long.class);
    }

    private long uploadFile(String token, String filename, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content);
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private long event(LocalDateTime start) {
        return eventService.create(new EventCreateRequest(
                "REHEARSAL", "排练", start.toLocalDate(), start.toLocalTime(),
                endTimeWithinDay(start, 60), "排练厅", null, null), admin).id();
    }

    private LocalDateTime startsInMinutes(long minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }
}
