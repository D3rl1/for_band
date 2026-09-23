package com.forband.event;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.event.dto.EventBatchRequest;
import com.forband.event.dto.EventCreateRequest;
import com.forband.event.dto.EventUpdateRequest;
import com.forband.event.dto.EventView;
import com.forband.log.OpLog;
import com.forband.piece.PieceService;
import com.forband.piece.dto.PieceDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequireRole
public class EventController {

    private final EventService eventService;
    private final PieceService pieceService;

    public EventController(EventService eventService, PieceService pieceService) {
        this.eventService = eventService;
        this.pieceService = pieceService;
    }

    @GetMapping
    public ApiResponse<PageResult<EventView>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser viewer) {
        return ApiResponse.ok(eventService.list(kind, from, to, page, size, viewer));
    }

    @GetMapping("/{id}")
    public ApiResponse<EventView> get(@PathVariable long id, CurrentUser viewer) {
        return ApiResponse.ok(eventService.get(id, viewer));
    }

    @PostMapping
    @OpLog(action = "EVENT_CREATE", targetType = "event")
    public ApiResponse<EventView> create(@RequestBody @Valid EventCreateRequest request,
                                         CurrentUser operator) {
        return ApiResponse.ok(eventService.create(request, operator));
    }

    @PostMapping("/batch")
    @OpLog(action = "EVENT_BATCH_CREATE", targetType = "event")
    public ApiResponse<List<EventView>> batchCreate(@RequestBody @Valid EventBatchRequest request,
                                                    CurrentUser operator) {
        return ApiResponse.ok(eventService.batchCreate(request, operator));
    }

    @PutMapping("/{id}")
    @OpLog(action = "EVENT_UPDATE", targetType = "event")
    public ApiResponse<EventView> update(@PathVariable long id,
                                         @RequestBody @Valid EventUpdateRequest request,
                                         CurrentUser operator) {
        return ApiResponse.ok(eventService.update(id, request, operator));
    }

    @PostMapping("/{id}/cancel")
    @OpLog(action = "EVENT_CANCEL", targetType = "event")
    public ApiResponse<EventView> cancel(@PathVariable long id, CurrentUser operator) {
        return ApiResponse.ok(eventService.cancel(id, operator));
    }

    /**
     * 这场活动练哪些曲（docs/04 §4.5，期 3 起）——**整体替换**子列表，
     * 与演出曲目单（`PUT /api/concerts/{id}/program`）同一套做法。
     */
    @PutMapping("/{id}/pieces")
    @OpLog(action = "EVENT_SET_PIECES", targetType = "event")
    public ApiResponse<List<PieceDtos.EventPieceView>> setPieces(
            @PathVariable long id,
            @RequestBody PieceDtos.EventPiecesRequest request,
            CurrentUser operator) {
        return ApiResponse.ok(pieceService.setEventPieces(id, request.pieceIds(), operator));
    }

    /** 看这场活动练了哪些曲（登录即可，可见性跟活动本身走）。 */
    @GetMapping("/{id}/pieces")
    public ApiResponse<List<PieceDtos.EventPieceView>> pieces(@PathVariable long id, CurrentUser viewer) {
        // 复用活动的可见性判定：团员看不到别声部的声部课，它的曲目单自然也看不到
        eventService.get(id, viewer);
        return ApiResponse.ok(pieceService.piecesOfEvent(id));
    }
}
