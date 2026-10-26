package com.forband.issue.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class IssueDtos {

    private IssueDtos() {
    }

    /** 发问题：UA 不在入参里，服务端从请求头取（docs/15 §15.3.1）。 */
    public record CreateRequest(String title, String content, String deviceInfo, String pagePath) {
    }

    /** 作者编辑：只改标题 / 正文 / 设备；不顶帖。 */
    public record UpdateRequest(String title, String content, String deviceInfo) {
    }

    public record CommentRequest(String content) {
    }

    /** 改状态：status 四档之一；REJECTED 必须带 reason。 */
    public record StatusRequest(String status, String reason) {
    }

    public record IssueListItem(long id, String title, long authorId, String authorName,
                                String status, long commentCount, long reactionCount,
                                LocalDateTime lastActivityAt, LocalDateTime createdAt) {
    }

    public record IssueCommentView(long id, String kind, Long memberId, String memberName,
                                   String content, String oldStatus, String newStatus,
                                   LocalDateTime createdAt, LocalDateTime editedAt, boolean canEdit) {
    }

    public record IssueDetail(long id, String title, String content, long authorId, String authorName,
                              String status, String deviceInfo, String pagePath,
                              String statusChangedByName, LocalDateTime statusChangedAt,
                              LocalDateTime editedAt, LocalDateTime createdAt,
                              LocalDateTime lastActivityAt, long commentCount, long reactionCount,
                              boolean reactedByMe, boolean canEdit, List<IssueCommentView> comments) {
    }

    public record ReactionView(long reactionCount, boolean reactedByMe) {
    }

    public record ActivityView(LocalDateTime latestUpdatedAt) {
    }
}
