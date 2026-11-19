package com.forband.sectionnote;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import com.forband.sectionnote.dto.SectionNoteDtos.CreateRequest;
import com.forband.sectionnote.dto.SectionNoteDtos.NoteView;
import com.forband.sectionnote.dto.SectionNoteDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上任声部长的留言（docs/03 §3.5）。
 * 这里只管"**登录**"这一层（`@RequireRole` 不带值 = 任何已登录角色）；
 * **读 / 写 / 改 / 删**那四级的细粒度判定全在 {@link SectionNoteService}。
 * ⚠️ **没有 DELETE**：谁都不能删（留痕性质）—— 刻意不提供这个能力。
 */
@RestController
@RequestMapping("/api/sections/{id}/notes")
@RequireRole
public class SectionNoteController {

    private final SectionNoteService sectionNoteService;

    public SectionNoteController(SectionNoteService sectionNoteService) {
        this.sectionNoteService = sectionNoteService;
    }

    @GetMapping
    public ApiResponse<PageResult<NoteView>> list(@PathVariable long id,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "50") int size,
                                                  CurrentUser user) {
        return ApiResponse.ok(sectionNoteService.page(id, page, size, user));
    }

    @PostMapping
    @OpLog(action = OpAction.SECTION_NOTE_WRITE, targetType = "section_note")
    public ApiResponse<NoteView> create(@PathVariable long id,
                                        @RequestBody @Valid CreateRequest request,
                                        CurrentUser user) {
        return ApiResponse.ok(sectionNoteService.create(id, request.body(), user));
    }

    /**
     * 改自己写的那条。
     * ⚠️ **`noteId` 故意排在 `sectionId` 前面**：`OpLogAspect` 取"**第一个 `Long` 入参**"当 `targetId`
     * （`@AfterReturning` 的既有实现），按 `(id, noteId)` 的书写顺序会把日志的目标记成**声部 id**。
     * 参数顺序不影响 Spring 的按名绑定，但影响这条日志记的是谁。
     */
    @PutMapping("/{noteId}")
    @OpLog(action = OpAction.SECTION_NOTE_WRITE, targetType = "section_note")
    public ApiResponse<NoteView> update(@PathVariable long noteId, @PathVariable long id,
                                        @RequestBody @Valid UpdateRequest request,
                                        CurrentUser user) {
        return ApiResponse.ok(sectionNoteService.update(id, noteId, request.body(), user));
    }
}
