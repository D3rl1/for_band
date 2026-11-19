package com.forband.piece.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class PieceDtos {

    private PieceDtos() {
    }

    /** 新建 / 修改曲目（docs/06 §6.3）。`status` 取值由服务端校验在四态白名单内。 */
    public record PieceRequest(@NotBlank(message = "曲名不能为空") @Size(max = 64) String title,
                               @Size(max = 64) String composer,
                               @Size(max = 64) String arranger,
                               @Size(max = 32) String category,
                               @Min(1) Integer difficulty,
                               Integer durationSec,
                               String status,
                               String note) {
    }

    /** 挂附件：先走 `POST /api/files` 拿到 `fileId`，再拿它来这里关联（docs/08 §8.5 的统一机制）。 */
    public record AttachmentRequest(Long fileId, @NotBlank String kind) {
    }

    /** 附件视图。**不含磁盘名**；`originalName` 只用于展示，取内容仍走 `GET /api/files/{id}` 的鉴权口。 */
    public record AttachmentView(long id, String kind, long fileId, String originalName,
                                 String contentType, long sizeBytes, String uploadedByName,
                                 LocalDateTime uploadedAt) {
    }

    /** 列表行：只要元数据（附件走详情页看，避免列表里 N+1 查询）。 */
    public record PieceView(long id, String title, String composer, String arranger, String category,
                            Integer difficulty, Integer durationSec, String status, String statusLabel,
                            String note, LocalDateTime updatedAt) {
    }

    /** 详情：元数据 + 附件列表。 */
    public record PieceDetailView(PieceView piece, List<AttachmentView> attachments) {
    }

    /** 活动关联曲目（`PUT /api/events/{id}/pieces` 的入参）。 */
    public record EventPiecesRequest(@NotNull(message = "pieceIds 不能为空") List<Long> pieceIds) {
    }

    /** 活动关联曲目的一行（给活动详情页用）。 */
    public record EventPieceView(long pieceId, String title, String composer, String status,
                                 String statusLabel, Integer orderNo, String note) {
    }
}
