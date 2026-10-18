package com.forband.file;

import com.forband.auth.CurrentUser;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件**签名 URL**（docs/08 §8.5，2026-10-07）：手机浏览器带不了 `Authorization` 头 ⇒
 * 靠 `GET /{id}/ticket` 取票、`GET /{id}/view?t=…` 用票取文件。
 * <p>
 * 这一版最要紧的是**那条被放行的路径**（`view` 绕过了默认拒绝的拦截器）——所以反例比正例多：
 * 无票 / 伪造 / 跨文件 / 过期**一律 401**，且**老路径 `GET /{id}` 不能被顺手改成公开**。
 * 跨天那两条靠 {@link FileTicketServiceTests} 的固定钟覆盖（这里跑真钟，只验"日内稳定"）。
 */
class FileTicketTests extends IntegrationTestBase {

    private CurrentUser admin;
    private String memberAToken;
    private String tuanzhangToken;
    private String honoraryToken;

    @BeforeEach
    void setUp() {
        cleanTables();
        admin = seedAdmin();
        long section = seedSection("吹管声部");
        MemberCreateResponse a = createMember(admin, "团员甲", "memberA", section, "MEMBER");
        memberAToken = loginToken("memberA", a.initialPassword());

        MemberCreateResponse tz = createMember(admin, "团长", "tuanzhang", null, "LEADER");
        tuanzhangToken = loginToken("tuanzhang", tz.initialPassword());

        MemberCreateResponse h = createMember(admin, "荣誉团员", "honorary", section, "MEMBER");
        setMemberStatus(admin, h.id(), "GRADUATED");
        memberService.update(h.id(), new com.forband.member.dto.MemberUpdateRequest(
                null, null, "HONORARY", null, null, null, null, null,
                LocalDate.now().plusYears(2), null, null, null, null, null, null, null), admin);
        honoraryToken = loginToken("honorary", h.initialPassword());
    }

    // ---------- 正例 ----------

    @Test
    void ticket_then_view_opens_without_authorization_header() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");

        MvcResult ticket = api(memberAToken, "GET", "/api/files/" + fileId + "/ticket", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view").value(Matchers.containsString("/api/files/" + fileId + "/view")))
                .andExpect(jsonPath("$.data.download").value(Matchers.containsString("dl=1")))
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andReturn();
        String view = JsonPath.read(ticket.getResponse().getContentAsString(), "$.data.view");
        String download = JsonPath.read(ticket.getResponse().getContentAsString(), "$.data.download");

        // ⭐ 关键：**不带任何 Authorization 头**，纯 URL 取文件 ⇒ 200 + 字节与上传时一致
        MvcResult got = mockMvc.perform(get(view))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("immutable")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline")))
                .andReturn();
        assertArrayEquals(pdfBytes(), got.getResponse().getContentAsByteArray());

        // dl=1 ⇒ 下载：`attachment` + 中文真名走 filename*
        mockMvc.perform(get(download))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("filename*=UTF-8''")));
    }

    @Test
    void ticket_is_stable_within_a_day_so_the_browser_can_cache() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");
        // URL 一字不变 ⇒ 吃到 Cache-Control ⇒ **反复打开不重复下载**（用户明确诉求）
        assertEquals(viewUrl(memberAToken, fileId), viewUrl(memberAToken, fileId));
    }

    @Test
    void a_plain_member_can_ticket_a_piece_score() throws Exception {
        // 团长传的谱子挂到曲目上 —— 普通团员登录即可取（docs/06 §6.4），走票据这条路同样成立
        long pieceId = createPiece(tuanzhangToken, "金蛇狂舞");
        long fileId = uploadPdf(tuanzhangToken, "score.pdf");
        api(tuanzhangToken, "POST", "/api/pieces/" + pieceId + "/attachments",
                "{\"fileId\":" + fileId + ",\"kind\":\"SCORE\"}").andExpect(status().isOk());

        String view = viewUrl(memberAToken, fileId);
        mockMvc.perform(get(view)).andExpect(status().isOk());
    }

    @Test
    void old_authenticated_path_still_works_and_did_not_become_public() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");
        // 回归：带 Bearer 的老路径照旧
        api(memberAToken, "GET", "/api/files/" + fileId, null).andExpect(status().isOk());
        // ⚠️ 证明"没把整段开成公开"：老路径无头仍 401（放行的只有 `/{id}/view` 那一条）
        api(null, "GET", "/api/files/" + fileId, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    // ---------- 反例（放行段全靠票据兜底） ----------

    @Test
    void view_without_ticket_or_with_a_forged_one_is_401() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");
        mockMvc.perform(get("/api/files/" + fileId + "/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
        mockMvc.perform(get("/api/files/" + fileId + "/view?t="))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId + "/view?t=not-a-real-ticket"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tampered_ticket_is_401() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");
        String ticket = ticket(memberAToken, fileId);
        char last = ticket.charAt(ticket.length() - 1);
        String tampered = ticket.substring(0, ticket.length() - 1) + (last == 'A' ? 'B' : 'A');
        mockMvc.perform(get("/api/files/" + fileId + "/view?t=" + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void ticket_for_one_file_cannot_open_another() throws Exception {
        long mine = uploadPdf(memberAToken, "a.pdf");
        long other = uploadPdf(memberAToken, "b.pdf");
        String ticket = ticket(memberAToken, mine);
        // 票据绑 fileId：拿 A 的票打 B ⇒ 401
        mockMvc.perform(get("/api/files/" + other + "/view?t=" + ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void honorary_member_cannot_get_a_ticket_for_a_piece_score() throws Exception {
        long pieceId = createPiece(tuanzhangToken, "金蛇狂舞");
        long fileId = uploadPdf(tuanzhangToken, "score.pdf");
        api(tuanzhangToken, "POST", "/api/pieces/" + pieceId + "/attachments",
                "{\"fileId\":" + fileId + ",\"kind\":\"SCORE\"}").andExpect(status().isOk());

        // 荣誉团员在取票端点就吃 403（`requireViewable` → PieceFileAccessRule），根本拿不到 URL
        api(honoraryToken, "GET", "/api/files/" + fileId + "/ticket", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30001));
        // 对照：团长/团员能取到
        api(memberAToken, "GET", "/api/files/" + fileId + "/ticket", null)
                .andExpect(status().isOk());
    }

    @Test
    void ticket_endpoint_requires_authentication() throws Exception {
        long fileId = uploadPdf(memberAToken, "总谱.pdf");
        api(null, "GET", "/api/files/" + fileId + "/ticket", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20002));
    }

    // ---------- 造数 ----------

    private String viewUrl(String token, long fileId) throws Exception {
        return JsonPath.read(
                api(token, "GET", "/api/files/" + fileId + "/ticket", null)
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString(), "$.data.view");
    }

    private String ticket(String token, long fileId) throws Exception {
        String view = viewUrl(token, fileId);
        return view.substring(view.indexOf("t=") + 2, view.indexOf("&dl="));
    }

    private long createPiece(String token, String title) throws Exception {
        api(token, "POST", "/api/pieces", "{\"title\":\"" + title + "\"}").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM piece", Long.class);
    }

    private long uploadPdf(String token, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", pdfBytes());
        mockMvc.perform(multipart("/api/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT MAX(id) FROM file", Long.class);
    }

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
