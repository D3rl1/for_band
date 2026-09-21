package com.forband.attendance;

import com.forband.attendance.dto.AttendanceDtos.AggregateStats;
import com.forband.attendance.dto.AttendanceDtos.AttendanceMarkRequest;
import com.forband.attendance.dto.AttendanceDtos.AttendanceRow;
import com.forband.attendance.dto.AttendanceDtos.CheckInRequest;
import com.forband.attendance.dto.AttendanceDtos.EventAttendance;
import com.forband.attendance.dto.AttendanceDtos.MemberSummary;
import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequireRole
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /** 无 body = 本人签到；带 {"memberId":N} = 代签（权限在服务层校验）。 */
    @PostMapping("/api/events/{id}/attendance")
    public ApiResponse<AttendanceRow> checkIn(@PathVariable long id,
                                              @RequestBody(required = false) @Valid CheckInRequest request,
                                              CurrentUser requester) {
        return ApiResponse.ok(attendanceService.checkIn(id, request, requester));
    }

    @GetMapping("/api/events/{id}/attendance")
    public ApiResponse<EventAttendance> eventAttendance(@PathVariable long id, CurrentUser viewer) {
        return ApiResponse.ok(attendanceService.eventAttendance(id, viewer));
    }

    @PutMapping("/api/attendance/{id}")
    @RequireRole({Role.ADMIN, Role.LEADER})
    public ApiResponse<AttendanceRow> changeStatus(@PathVariable long id,
                                                   @RequestBody @Valid AttendanceMarkRequest request,
                                                   CurrentUser operator) {
        return ApiResponse.ok(attendanceService.changeStatus(id, request, operator));
    }

    @GetMapping("/api/members/{id}/attendance-summary")
    public ApiResponse<MemberSummary> memberSummary(@PathVariable long id,
                                                    @RequestParam(required = false) LocalDate from,
                                                    @RequestParam(required = false) LocalDate to,
                                                    CurrentUser viewer) {
        return ApiResponse.ok(attendanceService.memberSummary(id, from, to, viewer));
    }

    @GetMapping("/api/attendance/stats")
    @RequireRole({Role.ADMIN, Role.LEADER, Role.CONDUCTOR, Role.SECTION_LEADER})
    public ApiResponse<AggregateStats> stats(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             CurrentUser viewer) {
        return ApiResponse.ok(attendanceService.aggregate(from, to, viewer));
    }

    @GetMapping("/api/attendance/export")
    @RequireRole({Role.ADMIN, Role.LEADER})
    public ResponseEntity<String> export(@RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to,
                                         @RequestParam(required = false) String kind) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendance.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(attendanceService.exportCsv(from, to, kind));
    }
}
