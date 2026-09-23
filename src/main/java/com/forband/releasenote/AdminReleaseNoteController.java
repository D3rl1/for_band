package com.forband.releasenote;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.releasenote.dto.ReleaseNoteDtos.CreateRequest;
import com.forband.releasenote.dto.ReleaseNoteDtos.ReleaseNoteView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布公告（docs/15 §15.3）：**仅管理员**（属"系统层"）。
 * 刻意**只有 POST**——**发布后不可改不可删**，要改就再发一条（docs/13 §13.3㉒）。
 * ⚠️ 也**不加 `@OpLog`**：`published_by` / `published_at` 自带留痕，与"处理反馈不进清单"同标准
 * （`docs/11` §11.3 对"发布公告"留白了，已记进 `移交事项-2026-09-23.md` 请规格点头）。
 */
@RestController
@RequestMapping("/api/admin/release-notes")
@RequireRole(Role.ADMIN)
public class AdminReleaseNoteController {

    private final ReleaseNoteService releaseNoteService;

    public AdminReleaseNoteController(ReleaseNoteService releaseNoteService) {
        this.releaseNoteService = releaseNoteService;
    }

    @PostMapping
    public ApiResponse<ReleaseNoteView> publish(@RequestBody @Valid CreateRequest request,
                                                CurrentUser publisher) {
        return ApiResponse.ok(releaseNoteService.publish(request, publisher));
    }
}
