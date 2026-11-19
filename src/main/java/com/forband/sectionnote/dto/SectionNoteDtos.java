package com.forband.sectionnote.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public final class SectionNoteDtos {

    private SectionNoteDtos() {
    }

    /** 留一条（docs/03 §3.5）：正文纯文本、保留换行；空/纯空白被 `@NotBlank` 拦下（10001）。 */
    public record CreateRequest(@NotBlank(message = "留言不能为空") String body) {
    }

    /** 改自己写的那条（只有作者本人能走到这里）。 */
    public record UpdateRequest(@NotBlank(message = "留言不能为空") String body) {
    }

    /** **历届留言共用**（浮层与"新部长上任"那一次弹层是同一份内容）。
     *  `mine = 是不是我写的`——前端据此决定给不给「改」（**谁都给不了「删」**）。 */
    public record NoteView(long id, String body, Long authorId, String authorName,
                           LocalDateTime createdAt, LocalDateTime updatedAt, boolean mine) {
    }
}
