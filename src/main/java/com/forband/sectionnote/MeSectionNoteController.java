package com.forband.sectionnote;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.sectionnote.dto.SectionNoteDtos.NoteView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我刚上任、还没读过上一任的留言」那一条（docs/03 §3.5）。
 * 单独一个控制器是为了**保住 `/api/me` 这个 URL 前缀**、又**不把依赖倒灌进 `member` 包**
 * （`member` ↔ `sectionnote` 循环依赖不值得为一条接口去开）。
 */
@RestController
@RequestMapping("/api/me")
@RequireRole
public class MeSectionNoteController {

    private final SectionNoteService sectionNoteService;

    public MeSectionNoteController(SectionNoteService sectionNoteService) {
        this.sectionNoteService = sectionNoteService;
    }

    /** **不满足就 `data: null`**（前端据此"不弹"）——不是错误，所以回 200。 */
    @GetMapping("/section-note-pending")
    public ApiResponse<NoteView> pending(CurrentUser user) {
        return ApiResponse.ok(sectionNoteService.pending(user));
    }
}
