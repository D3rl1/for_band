package com.forband.leave;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.common.PageResult;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.leave.dto.LeaveDtos.RequestView;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireRole
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping("/api/events/{id}/leave-requests")
    @OpLog(action = OpAction.LEAVE_APPLY, targetType = "leave_request")
    public ApiResponse<RequestView> create(@PathVariable long id,
                                           @RequestBody(required = false) @Valid CreateRequest request,
                                           CurrentUser applicant) {
        return ApiResponse.ok(leaveService.create(id,
                request == null ? new CreateRequest(null) : request, applicant));
    }

    @PostMapping("/api/leave-requests/{id}/cancel")
    @OpLog(action = OpAction.LEAVE_CANCEL, targetType = "leave_request")
    public ApiResponse<RequestView> cancel(@PathVariable long id, CurrentUser applicant) {
        return ApiResponse.ok(leaveService.cancel(id, applicant));
    }

    @GetMapping("/api/leave-requests")
    public ApiResponse<PageResult<RequestView>> list(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            CurrentUser viewer) {
        return ApiResponse.ok(leaveService.list(scope, status, eventId, page, size, viewer));
    }

    @PostMapping("/api/leave-approvals/{id}/decide")
    @OpLog(action = OpAction.LEAVE_DECIDE, targetType = "leave_approval")
    public ApiResponse<RequestView> decide(@PathVariable long id,
                                           @RequestBody @Valid DecideRequest request,
                                           CurrentUser decider) {
        return ApiResponse.ok(leaveService.decide(id, request, decider));
    }
}
