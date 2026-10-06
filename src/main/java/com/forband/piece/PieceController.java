package com.forband.piece;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import com.forband.piece.dto.PieceDtos.AttachmentRequest;
import com.forband.piece.dto.PieceDtos.AttachmentView;
import com.forband.piece.dto.PieceDtos.PieceDetailView;
import com.forband.piece.dto.PieceDtos.PieceRequest;
import com.forband.piece.dto.PieceDtos.PieceView;
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

/**
 * 曲目库（docs/06 §6.3）。**看**＝登录即可（含荣誉团员，但附件下载由文件接口按角色挡住）；
 * **增删改**＝管理员 / 团长 / 指挥（服务层校验）。
 * 留痕按 `docs/11` §11.3：**改曲目**、**删附件** 要留；新建曲目不在清单里 → 不写。
 */
@RestController
@RequestMapping("/api/pieces")
@RequireRole
public class PieceController {

    private final PieceService pieceService;

    public PieceController(PieceService pieceService) {
        this.pieceService = pieceService;
    }

    @GetMapping
    public ApiResponse<PageResult<PieceView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(pieceService.page(status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PieceDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(pieceService.detail(id));
    }

    @PostMapping
    public ApiResponse<PieceView> create(@RequestBody @Valid PieceRequest request, CurrentUser operator) {
        return ApiResponse.ok(pieceService.create(request, operator));
    }

    @PutMapping("/{id}")
    @OpLog(action = OpAction.PIECE_UPDATE, targetType = "piece")
    public ApiResponse<PieceView> update(@PathVariable long id,
                                         @RequestBody @Valid PieceRequest request,
                                         CurrentUser operator) {
        return ApiResponse.ok(pieceService.update(id, request, operator));
    }

    /** 挂附件：先 `POST /api/files` 拿 `fileId`，再拿着它来这里关联（docs/08 §8.5）。 */
    @PostMapping("/{id}/attachments")
    public ApiResponse<AttachmentView> attach(@PathVariable long id,
                                              @RequestBody @Valid AttachmentRequest request,
                                              CurrentUser operator) {
        return ApiResponse.ok(pieceService.attach(id, request.fileId(), request.kind(), operator));
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @OpLog(action = OpAction.PIECE_ATTACHMENT_DELETE, targetType = "piece")
    public ApiResponse<Void> detach(@PathVariable long id, @PathVariable long attachmentId,
                                    CurrentUser operator) {
        pieceService.detach(id, attachmentId, operator);
        return ApiResponse.ok();
    }
}
