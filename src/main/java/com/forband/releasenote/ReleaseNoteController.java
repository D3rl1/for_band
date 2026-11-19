package com.forband.releasenote;

import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.releasenote.dto.ReleaseNoteDtos.ReleaseNoteView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看公告（docs/15 §15.3）：**所有登录角色，含荣誉团员**——这是对"新功能默认不给荣誉团员"
 * 那条例外条款的**有意例外**（公告是纯只读信息，荣誉团员的定位就是"只读的过去"）。
 */
@RestController
@RequestMapping("/api/release-notes")
@RequireRole
public class ReleaseNoteController {

    private final ReleaseNoteService releaseNoteService;

    public ReleaseNoteController(ReleaseNoteService releaseNoteService) {
        this.releaseNoteService = releaseNoteService;
    }

    /** 最新一条（给登录弹层用）。**没有公告时 data 是 null**，前端直接跳过整个环节。 */
    @GetMapping("/latest")
    public ApiResponse<ReleaseNoteView> latest() {
        return ApiResponse.ok(releaseNoteService.latest());
    }

    @GetMapping
    public ApiResponse<PageResult<ReleaseNoteView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(releaseNoteService.page(page, size));
    }
}
