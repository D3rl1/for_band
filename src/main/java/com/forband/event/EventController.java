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

    public EventController(EventService eventService) {
        this.eventService = eventService;
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
}
