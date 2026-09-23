package com.forband.file;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
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
 * 下载：**必须鉴权**，按该文件所属实体的可见性判定后返回流。
 */
@RestController
@RequestMapping("/api/files")
@RequireRole
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    public ApiResponse<FileView> upload(@RequestParam("file") MultipartFile file, CurrentUser user) {
        return ApiResponse.ok(fileService.upload(file, user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id, CurrentUser viewer) {
        StoredFile row = fileService.requireViewable(id, viewer);
        // inline：谱子 PDF 直接预览、音频直接播放；想存盘由浏览器自己提供入口
        // （docs/08 §8.5 明确"这不是技术保证，是产品层面的约定"——文件能看就必然拿得到字节流）
        String disposition = "inline; filename=\"" + asciiFallback(row.getOriginalName()) + "\"; "
                + "filename*=UTF-8''" + URLEncoder.encode(
                        row.getOriginalName() == null ? "file" : row.getOriginalName(), StandardCharsets.UTF_8)
                        .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .contentType(MediaType.parseMediaType(row.getContentType()))
                .contentLength(row.getSizeBytes() == null ? -1 : row.getSizeBytes())
                .body(new InputStreamResource(fileService.open(row)));
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
