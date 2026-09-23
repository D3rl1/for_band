package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.concert.dto.ConcertDtos.CastRequest;
import com.forband.concert.dto.ConcertDtos.CastRow;
import com.forband.concert.dto.ConcertDtos.ConcertDetailView;
import com.forband.concert.dto.ConcertDtos.ConcertRequest;
import com.forband.concert.dto.ConcertDtos.ConcertView;
import com.forband.concert.dto.ConcertDtos.PhotoRequest;
import com.forband.concert.dto.ConcertDtos.PhotoRow;
import com.forband.concert.dto.ConcertDtos.ProgramRequest;
import com.forband.concert.dto.ConcertDtos.ProgramRow;
import com.forband.log.OpLog;
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
 * 演出档案（docs/07 §7.3）。看＝登录即可（**全团可见，含历史演出**）；
 * 增删改 / 曲目单 / 参演名单＝管理员 / 团长 / 指挥；**照片上传是全员**。
 * 留痕按 `docs/11` §11.3：**改演出档案**、**删照片** 要留（§7.5 另点明"复盘被修改要留痕"）；
 * 新建演出与导出节目单不在清单 → 不写。
 */
@RestController
@RequestMapping("/api/concerts")
@RequireRole
public class ConcertController {

    private final ConcertService concertService;
    private final ProgramSheet programSheet;

    public ConcertController(ConcertService concertService, ProgramSheet programSheet) {
        this.concertService = concertService;
        this.programSheet = programSheet;
    }

    @GetMapping
    public ApiResponse<PageResult<ConcertView>> list(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(concertService.page(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConcertDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(concertService.detail(id));
    }

    @PostMapping
    public ApiResponse<ConcertView> create(@RequestBody @Valid ConcertRequest request,
                                           CurrentUser operator) {
        return ApiResponse.ok(concertService.create(request, operator));
    }

    @PutMapping("/{id}")
    @OpLog(action = "CONCERT_UPDATE", targetType = "concert")
    public ApiResponse<ConcertView> update(@PathVariable long id,
                                           @RequestBody @Valid ConcertRequest request,
                                           CurrentUser operator) {
        return ApiResponse.ok(concertService.update(id, request, operator));
    }

    /** 曲目单：**整体替换**，顺序即演出顺序（曲目从曲目库选，不自由填写）。 */
    @PutMapping("/{id}/program")
    @OpLog(action = "CONCERT_UPDATE", targetType = "concert")
    public ApiResponse<List<ProgramRow>> setProgram(@PathVariable long id,
                                                    @RequestBody ProgramRequest request,
                                                    CurrentUser operator) {
        return ApiResponse.ok(concertService.setProgram(id, request, operator));
    }

    /** 参演名单：**整体替换**（可含已毕业 / 休团 / 荣誉团员——"毕业了回团参演"很常见）。 */
    @PutMapping("/{id}/cast")
    @OpLog(action = "CONCERT_UPDATE", targetType = "concert")
    public ApiResponse<List<CastRow>> setCast(@PathVariable long id,
                                              @RequestBody CastRequest request,
                                              CurrentUser operator) {
        return ApiResponse.ok(concertService.setCast(id, request, operator));
    }

    /** **登录全员都能传照片**——演出当天拍照的往往是普通团员，限制上传只会让照片永远集不起来。 */
    @PostMapping("/{id}/photos")
    public ApiResponse<PhotoRow> addPhoto(@PathVariable long id,
                                          @RequestBody PhotoRequest request,
                                          CurrentUser uploader) {
        return ApiResponse.ok(concertService.addPhoto(id, request, uploader));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    @OpLog(action = "CONCERT_PHOTO_DELETE", targetType = "concert")
    public ApiResponse<Void> deletePhoto(@PathVariable long id, @PathVariable long photoId,
                                         CurrentUser operator) {
        concertService.deletePhoto(id, photoId, operator);
        return ApiResponse.ok();
    }

    /**
     * 可打印节目单（docs/07 §7.3/§7.4，待定项 T6）：**返回一张完整 HTML**，用于现场分发或发群。
     * ⚠️ 规格没给字段清单 → 按打印场景取：演出名 / 日期 / 地点 / 主办 + 曲目单（序号·曲名·作曲·指挥）；
     * 已记进 `移交事项-2026-09-23.md` 请规格补口径。
     */
    @GetMapping(value = "/{id}/program.html", produces = "text/html;charset=UTF-8")
    public String programHtml(@PathVariable long id, CurrentUser viewer) {
        return programSheet.render(id, viewer);
    }
}
