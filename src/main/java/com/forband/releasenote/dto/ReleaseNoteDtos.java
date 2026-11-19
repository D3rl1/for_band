package com.forband.releasenote.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class ReleaseNoteDtos {

    private ReleaseNoteDtos() {
    }

    /** 发布公告（docs/15 §15.5）：标题 ≤64、正文纯文本。一次手写一条 = 一次发布。 */
    public record CreateRequest(@NotBlank(message = "标题不能为空") @Size(max = 64) String title,
                                @NotBlank(message = "正文不能为空") String content) {
    }

    /** 给弹层与历史页共用。**纯文本**，正文里的换行由前端的 CSS 保留。 */
    public record ReleaseNoteView(long id, String title, String content,
                                  LocalDateTime publishedAt, Long publishedBy,
                                  String publishedByName) {
    }
}
