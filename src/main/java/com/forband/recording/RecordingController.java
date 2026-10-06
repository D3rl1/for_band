package com.forband.recording;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import com.forband.recording.dto.RecordingDtos.CommentRequest;
import com.forband.recording.dto.RecordingDtos.CommentView;
import com.forband.recording.dto.RecordingDtos.CreateRequest;
import com.forband.recording.dto.RecordingDtos.RecordingDetailView;
import com.forband.recording.dto.RecordingDtos.RecordingRow;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 练习录音（docs/08 §8.3）。可见性（团员仅自己 / 声部长本声部 / 指挥·团长·管理员全部）**全部在服务层收口**，
 * 这里只做参数转发——**藏按钮不算权限**（`SECURITY.md` C2）。
 * 留痕：只"删录音"写日志（`docs/11` §11.3）；上传与点评不在清单里 → 不写。
 */
@RestController
@RequestMapping("/api/recordings")
@RequireRole
public class RecordingController {

    private final RecordingService recordingService;

    public RecordingController(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    /** `?memberId=&sectionId=&pieceId=` 只是**筛选**，缩不了权限（服务层还会再套可见性白名单）。 */
    @GetMapping
    public ApiResponse<PageResult<RecordingRow>> list(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) Long pieceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser viewer) {
        return ApiResponse.ok(recordingService.page(memberId, sectionId, pieceId, page, size, viewer));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecordingDetailView> detail(@PathVariable long id, CurrentUser viewer) {
        return ApiResponse.ok(recordingService.detail(id, viewer));
    }

    /** 上传：登录 + **非荣誉团员**（服务层判）。归属（memberId / sectionId）一律取自登录身份。 */
    @PostMapping
    public ApiResponse<RecordingRow> create(@RequestBody @Valid CreateRequest request,
                                            CurrentUser uploader) {
        return ApiResponse.ok(recordingService.create(request, uploader));
    }

    @DeleteMapping("/{id}")
    @OpLog(action = OpAction.RECORDING_DELETE, targetType = "recording")
    public ApiResponse<Void> delete(@PathVariable long id, CurrentUser operator) {
        recordingService.delete(id, operator);
        return ApiResponse.ok();
    }

    /** 点评：**能看见就能说**（双向交流，docs/08 §8.4）。 */
    @PostMapping("/{id}/comments")
    public ApiResponse<CommentView> comment(@PathVariable long id,
                                            @RequestBody @Valid CommentRequest request,
                                            CurrentUser author) {
        return ApiResponse.ok(recordingService.comment(id, request, author));
    }
}
