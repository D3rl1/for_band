package com.forband.recording.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class RecordingDtos {

    private RecordingDtos() {
    }

    /**
     * 上传录音（docs/08 §8.3）：先 `POST /api/files` 拿 `fileId`，再拿着它来这里。
     * ⚠️ **刻意没有 `memberId` / `sectionId` 字段**——归属一律取登录身份（`SECURITY.md` A2 的 IDOR 防线）。
     */
    public record CreateRequest(Long fileId, Long pieceId, @Size(max = 64) String title,
                                Integer durationSec) {
    }

    /** 点评：双向（声部长留言、团员追问），**能看见这条录音就能说话**（docs/08 §8.4）。 */
    public record CommentRequest(@NotBlank(message = "内容不能为空") @Size(max = 1000) String content) {
    }

    public record CommentView(long id, long authorId, String authorName, String content,
                              LocalDateTime createdAt) {
    }

    /** 列表行：**不含磁盘名**；听声音走 `GET /api/files/{fileId}` 的鉴权口。 */
    public record RecordingRow(long id, long memberId, String memberName, String sectionName,
                               Long pieceId, String pieceTitle, String title, long fileId,
                               Integer durationSec, Long sizeBytes, LocalDateTime uploadedAt,
                               int commentCount) {
    }

    /** 详情：录音 + 点评（**平铺按时间**，不做父子嵌套）。 */
    public record RecordingDetailView(RecordingRow recording, List<CommentView> comments) {
    }
}
