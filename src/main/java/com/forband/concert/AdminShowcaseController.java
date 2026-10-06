package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.concert.dto.ShowcaseDtos.AddPhotoRequest;
import com.forband.concert.dto.ShowcaseDtos.CandidateRow;
import com.forband.concert.dto.ShowcaseDtos.NoteRequest;
import com.forband.concert.dto.ShowcaseDtos.OpenRequest;
import com.forband.concert.dto.ShowcaseDtos.OpenResult;
import com.forband.concert.dto.ShowcaseDtos.OrderRequest;
import com.forband.concert.dto.ShowcaseDtos.ShowcasePhotoRow;
import com.forband.concert.dto.ShowcaseDtos.ToggleRequest;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我们的专场」专页的**策展侧**（`docs/17` §17.3/§17.5）：**仅管理员**（`02` §2.3）。
 * <p>
 * ⚠️ **"开新时间点"为什么不做进 `POST /api/concerts`**：那条是 管理员 / 团长 / 指挥 三方可用，
 * 而开一个节点只给管理员 —— 独立一条窄接口，权限就是一个干净的类级 `@RequireRole(ADMIN)`。
 * 但它**建出来的就是一条普通演出记录**，之后照常由三方用 `07` 的接口维护。
 */
@RestController
@RequestMapping("/api/admin/showcase")
@RequireRole(Role.ADMIN)
public class AdminShowcaseController {

    private final ShowcaseService showcaseService;

    public AdminShowcaseController(ShowcaseService showcaseService) {
        this.showcaseService = showcaseService;
    }

    /** 开一个新时间点（`name` / `date` 必填；已是专场 → `10001`）。 */
    @PostMapping
    @OpLog(action = OpAction.SHOWCASE_CREATE, targetType = "concert")
    public ApiResponse<OpenResult> open(@RequestBody @Valid OpenRequest request,
                                        CurrentUser operator) {
        return ApiResponse.ok(showcaseService.open(request, operator));
    }

    /** 设为 / 取消专场：**必须双向**，且**只翻标记、绝不删演出档案**。 */
    @PutMapping("/{concertId}")
    @OpLog(action = OpAction.SHOWCASE_TOGGLE, targetType = "concert")
    public ApiResponse<Void> toggle(@PathVariable long concertId,
                                    @RequestBody @Valid ToggleRequest request,
                                    CurrentUser operator) {
        showcaseService.toggle(concertId, request.isShowcase(), operator);
        return ApiResponse.ok();
    }

    /** 这场演出**晒图墙上已有的**照片（"从已有的里挑"）。只读。 */
    @GetMapping("/{concertId}/candidates")
    public ApiResponse<List<CandidateRow>> candidates(@PathVariable long concertId) {
        return ApiResponse.ok(showcaseService.candidates(concertId));
    }

    /** 加一张策展图（`sort_no` 服务端追加到末尾）。 */
    @PostMapping("/{concertId}/photos")
    @OpLog(action = OpAction.SHOWCASE_PHOTO_ADD, targetType = "concert")
    public ApiResponse<ShowcasePhotoRow> addPhoto(@PathVariable long concertId,
                                                  @RequestBody @Valid AddPhotoRequest request,
                                                  CurrentUser operator) {
        return ApiResponse.ok(showcaseService.addPhoto(concertId, request, operator));
    }

    /** 整批设顺序（按数组顺序即最终顺序；id 不属于这场 → `10001`）。 */
    @PutMapping("/{concertId}/photos/order")
    @OpLog(action = OpAction.SHOWCASE_PHOTO_ORDER, targetType = "concert")
    public ApiResponse<List<ShowcasePhotoRow>> setOrder(@PathVariable long concertId,
                                                        @RequestBody @Valid OrderRequest request,
                                                        CurrentUser operator) {
        return ApiResponse.ok(showcaseService.setOrder(concertId, request, operator));
    }

    /** 改这一张的文案。 */
    @PutMapping("/{concertId}/photos/{photoId}")
    @OpLog(action = OpAction.SHOWCASE_PHOTO_NOTE, targetType = "concert")
    public ApiResponse<Void> setNote(@PathVariable long concertId, @PathVariable long photoId,
                                     @RequestBody @Valid NoteRequest request, CurrentUser operator) {
        showcaseService.setNote(concertId, photoId, request, operator);
        return ApiResponse.ok();
    }

    /** 把这张移出专页（**只删关联行，不删 `file`**）。 */
    @DeleteMapping("/{concertId}/photos/{photoId}")
    @OpLog(action = OpAction.SHOWCASE_PHOTO_DELETE, targetType = "concert")
    public ApiResponse<Void> deletePhoto(@PathVariable long concertId, @PathVariable long photoId,
                                         CurrentUser operator) {
        showcaseService.deletePhoto(concertId, photoId, operator);
        return ApiResponse.ok();
    }
}
