package com.forband.file;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.file.FileTicketService.TicketLinks;
import com.forband.file.dto.FileDtos.FileView;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一文件接口（docs/08 §8.5）：上传 + **鉴权下载**。
 * 上传：登录即可（谁能把它挂到哪个实体上，由各板块自己的接口把关）。
 * 下载：`GET /{id}` **必须带 `Authorization` 头**（桌面 / fetch 用）；手机浏览器没法带头，走
 * `GET /{id}/ticket` 取签名 URL、再 `GET /{id}/view?t=…`（见 {@link FileTicketService}）。
 */
@RestController
@RequestMapping("/api/files")
@RequireRole
public class FileController {

    private final FileService fileService;
    private final FileTicketService fileTicketService;

    public FileController(FileService fileService, FileTicketService fileTicketService) {
        this.fileService = fileService;
        this.fileTicketService = fileTicketService;
    }

    @PostMapping
    public ApiResponse<FileView> upload(@RequestParam("file") MultipartFile file, CurrentUser user) {
        return ApiResponse.ok(fileService.upload(file, user));
    }

    /** 老路径：头部鉴权，frontend 的 `fetch` / 桌面用。**不动**（回归面）。 */
    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id, CurrentUser viewer) {
        StoredFile row = fileService.requireViewable(id, viewer);
        // inline：谱子 PDF 直接预览、音频直接播放；想存盘由浏览器自己提供入口
        // （docs/08 §8.5 明确"这不是技术保证，是产品层面的约定"——文件能看就必然拿得到字节流）
        return stream(row, false);
    }

    /** 取签名 URL（手机用）。⚠️ **必须鉴权** —— 荣誉团员在 `requireViewable` 这里就吃 403。 */
    @GetMapping("/{id}/ticket")
    public ApiResponse<TicketLinks> ticket(@PathVariable long id, CurrentUser viewer) {
        fileService.requireViewable(id, viewer);
        return ApiResponse.ok(fileTicketService.issue(id));
    }

    /**
     * 用票据取文件（**绕过拦截器**，见 {@code WebMvcConfig} 的白名单）——浏览器导航带不了 `Authorization` 头。
     * ⚠️ 这条路径没有任何头部鉴权兜底，**全靠票据**：无票 / 伪造 / 跨文件 / 过期一律 401
     * （{@link FileTicketService#verify}），**绝不 fallback 成开放静态读**。`dl=1` → 下载（`attachment` + 真文件名）。
     */
    @GetMapping("/{id}/view")
    public ResponseEntity<InputStreamResource> view(@PathVariable long id,
                                                    @RequestParam(value = "t", required = false) String ticket,
                                                    @RequestParam(value = "dl", defaultValue = "0") String dl) {
        fileTicketService.verify(id, ticket);
        return stream(fileService.require(id), "1".equals(dl));
    }

    private ResponseEntity<InputStreamResource> stream(StoredFile row, boolean download) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(row.getOriginalName(), download))
                // 内容按 fileId **不可变**（改文件必然是新 id）⇒ 可长缓存 + immutable；
                // private 防共享/代理缓存串号。票据按天分桶 ⇒ URL 稳定 ⇒ 这条缓存才吃得到（不重复下载）。
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
                // 票据在 URL 里 ⇒ 别让它随 Referer 漏给第三方
                .header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.parseMediaType(row.getContentType()))
                .contentLength(row.getSizeBytes() == null ? -1 : row.getSizeBytes())
                .body(new InputStreamResource(fileService.open(row)));
    }

    /** `Content-Disposition`：`inline`（看）/ `attachment`（下载），真名走 `filename*`、ASCII 走兜底。 */
    private static String contentDisposition(String originalName, boolean download) {
        return (download ? "attachment" : "inline")
                + "; filename=\"" + asciiFallback(originalName) + "\"; "
                + "filename*=UTF-8''" + URLEncoder.encode(
                        originalName == null ? "file" : originalName, StandardCharsets.UTF_8)
                        .replace("+", "%20");
    }

    /** `Content-Disposition` 的 ASCII 兜底名：非 ASCII 一律替成 `_`（真正的名字走 `filename*`）。 */
    private static String asciiFallback(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String cleaned = name.replaceAll("[\\\\\"\\r\\n]", "_");
        return cleaned.replaceAll("[^\\x20-\\x7E]", "_");
    }
}
