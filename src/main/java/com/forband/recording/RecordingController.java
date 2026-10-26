package com.forband.recording;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import com.forband.recording.dto.RecordingAssignmentDtos.AssignmentView;
import com.forband.recording.dto.RecordingAssignmentDtos.CreateAssignmentRequest;
import com.forband.recording.dto.RecordingAssignmentDtos.CurrentAssignmentView;
import com.forband.recording.dto.RecordingAssignmentDtos.ManageView;
import com.forband.recording.dto.RecordingAssignmentDtos.UpdateAssignmentRequest;
import com.forband.recording.dto.RecordingAssignmentDtos.WeekView;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final RecordingAssignmentService assignmentService;

    public RecordingController(RecordingService recordingService,
                               RecordingAssignmentService assignmentService) {
        this.recordingService = recordingService;
        this.assignmentService = assignmentService;
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

    /** 录音管理视图（docs/08 §8.3）：声部长在服务层被强制收窄到本声部。 */
    @GetMapping("/manage")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<ManageView> manage(@RequestParam(required = false) String weekStart,
                                          CurrentUser viewer) {
        return ApiResponse.ok(assignmentService.manage(weekStart, viewer));
    }

    /** 历史周列表：`before` 缺省本周一（严格早于它），`limit` 缺省 12（夹 1–52）。 */
    @GetMapping("/manage/weeks")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<List<WeekView>> weeks(@RequestParam(required = false) String before,
                                             @RequestParam(required = false) Integer limit,
                                             CurrentUser viewer) {
        return ApiResponse.ok(assignmentService.weeks(before, limit, viewer));
    }

    /** 上传表单用：本人当前声部**本周**的作业（任何登录角色都能拿，没有声部就返回空）。 */
    @GetMapping("/assignments/current")
    public ApiResponse<List<CurrentAssignmentView>> currentAssignments(CurrentUser user) {
        return ApiResponse.ok(assignmentService.currentAssignments(user));
    }

    /** 布置本周作业；声部长只能布置本声部（服务层判，藏按钮不算权限）。 */
    @PostMapping("/assignments")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<AssignmentView> createAssignment(
            @RequestBody @Valid CreateAssignmentRequest request, CurrentUser user) {
        return ApiResponse.ok(assignmentService.create(request, user));
    }

    /** 修改本周作业：已有提交时只许改标题。 */
    @PutMapping("/assignments/{id}")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<AssignmentView> updateAssignment(@PathVariable long id,
                                                        @RequestBody @Valid UpdateAssignmentRequest request,
                                                        CurrentUser user) {
        return ApiResponse.ok(assignmentService.update(id, request, user));
    }

    /** 删除本周作业：已有任意录音关联时拒绝，并说明已有几人提交。 */
    @DeleteMapping("/assignments/{id}")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<Void> deleteAssignment(@PathVariable long id, CurrentUser user) {
        assignmentService.delete(id, user);
        return ApiResponse.ok();
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
