package com.forband.leave;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.attendance.Attendance;
import com.forband.attendance.AttendanceMapper;
import com.forband.attendance.AttendanceStatus;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.event.Event;
import com.forband.event.EventKind;
import com.forband.event.EventMapper;
import com.forband.event.EventStatus;
import com.forband.leave.dto.LeaveDtos.ApprovalView;
import com.forband.leave.dto.LeaveDtos.CreateRequest;
import com.forband.leave.dto.LeaveDtos.DecideRequest;
import com.forband.leave.dto.LeaveDtos.RequestView;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LeaveService {

    private final LeaveRequestMapper requestMapper;
    private final LeaveApprovalMapper approvalMapper;
    private final EventMapper eventMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final AttendanceMapper attendanceMapper;
    private final PermissionService permissionService;

    public LeaveService(LeaveRequestMapper requestMapper, LeaveApprovalMapper approvalMapper,
                        EventMapper eventMapper, MemberMapper memberMapper,
                        SectionMapper sectionMapper, AttendanceMapper attendanceMapper,
                        PermissionService permissionService) {
        this.requestMapper = requestMapper;
        this.approvalMapper = approvalMapper;
        this.eventMapper = eventMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.attendanceMapper = attendanceMapper;
        this.permissionService = permissionService;
    }

    // ---------- 申请 ----------

    @Transactional
    public RequestView create(long eventId, CreateRequest request, CurrentUser applicant) {
        Event event = requireEvent(eventId);
        if (EventStatus.CANCELED.equals(event.getStatus())) {
            throw new BusinessException(ErrorCode.EVENT_CANCELED);
        }
        ensureEventNotStarted(event, "活动已开始，无法申请");

        Long duplicate = requestMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getEventId, eventId)
                .eq(LeaveRequest::getMemberId, applicant.memberId())
                .in(LeaveRequest::getStatus, LeaveStatus.PENDING, LeaveStatus.APPROVED));
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(ErrorCode.LEAVE_DUPLICATE);
        }

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setEventId(eventId);
        leaveRequest.setMemberId(applicant.memberId());
        leaveRequest.setReason(request.reason());
        leaveRequest.setStatus(LeaveStatus.PENDING);
        leaveRequest.setAppliedAt(LocalDateTime.now());
        requestMapper.insert(leaveRequest);

        List<String> requiredRoles = EventKind.isSectionClass(event.getKind())
                ? List.of(Role.SECTION_LEADER.name())
                : List.of(Role.SECTION_LEADER.name(), Role.LEADER.name());
        for (String requiredRole : requiredRoles) {
            LeaveApproval approval = new LeaveApproval();
            approval.setLeaveRequestId(leaveRequest.getId());
            approval.setRequiredRole(requiredRole);
            approval.setDecision(LeaveStatus.PENDING);
            approvalMapper.insert(approval);
        }
        return toView(leaveRequest, event, applicant);
    }

    // ---------- 审批决定 ----------

    @Transactional
    public RequestView decide(long approvalId, DecideRequest request, CurrentUser decider) {
        if (!LeaveStatus.APPROVED.equals(request.decision())
                && !LeaveStatus.REJECTED.equals(request.decision())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "决定只能为 APPROVED / REJECTED");
        }
        LeaveApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待签记录不存在");
        }
        LeaveRequest leaveRequest = requireRequest(approval.getLeaveRequestId());
        if (!LeaveStatus.PENDING.equals(approval.getDecision())
                || !LeaveStatus.PENDING.equals(leaveRequest.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_DECIDED);
        }
        Member requester = memberMapper.selectById(leaveRequest.getMemberId());
        permissionService.checkCanDecideApproval(decider,
                Role.valueOf(requester.getRole()), requester.getSectionId(),
                approval.getRequiredRole());

        approval.setDecision(request.decision());
        approval.setApproverId(decider.memberId());
        approval.setDecidedAt(LocalDateTime.now());
        approval.setNote(request.note());
        approvalMapper.updateById(approval);

        List<LeaveApproval> all = approvalsOf(leaveRequest.getId());
        boolean anyRejected = all.stream()
                .anyMatch(a -> LeaveStatus.REJECTED.equals(a.getDecision()));
        boolean allApproved = all.stream()
                .allMatch(a -> LeaveStatus.APPROVED.equals(a.getDecision()));

        if (anyRejected) {
            leaveRequest.setStatus(LeaveStatus.REJECTED);
            requestMapper.updateById(leaveRequest);
        } else if (allApproved) {
            leaveRequest.setStatus(LeaveStatus.APPROVED);
            requestMapper.updateById(leaveRequest);
            writeLeaveAttendance(leaveRequest, decider);
        }
        return toView(leaveRequest, requireEvent(leaveRequest.getEventId()), decider);
    }

    /** 通过后的动作（docs/05）：写 attendance(LEAVE, marked_by=最终审批人)，统计只读一张表。 */
    private void writeLeaveAttendance(LeaveRequest leaveRequest, CurrentUser finalApprover) {
        Attendance existing = attendanceMapper.selectOne(new LambdaQueryWrapper<Attendance>()
                .eq(Attendance::getEventId, leaveRequest.getEventId())
                .eq(Attendance::getMemberId, leaveRequest.getMemberId()));
        if (existing == null) {
            Attendance attendance = new Attendance();
            attendance.setEventId(leaveRequest.getEventId());
            attendance.setMemberId(leaveRequest.getMemberId());
            attendance.setStatus(AttendanceStatus.LEAVE);
            attendance.setMarkedAt(LocalDateTime.now());
            attendance.setMarkedBy(finalApprover.memberId());
            try {
                attendanceMapper.insert(attendance);
            } catch (DuplicateKeyException e) {
                // 并发审批同时通过：唯一约束兜底，转为更新
                attendanceMapper.update(null, new LambdaUpdateWrapper<Attendance>()
                        .eq(Attendance::getEventId, leaveRequest.getEventId())
                        .eq(Attendance::getMemberId, leaveRequest.getMemberId())
                        .set(Attendance::getStatus, AttendanceStatus.LEAVE)
                        .set(Attendance::getMarkedBy, finalApprover.memberId()));
            }
        } else {
            existing.setStatus(AttendanceStatus.LEAVE);
            existing.setMarkedBy(finalApprover.memberId());
            attendanceMapper.updateById(existing);
        }
    }

    // ---------- 撤回 ----------

    @Transactional
    public RequestView cancel(long requestId, CurrentUser applicant) {
        LeaveRequest leaveRequest = requireRequest(requestId);
        permissionService.checkSelf(applicant, leaveRequest.getMemberId());
        if (!LeaveStatus.PENDING.equals(leaveRequest.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_DECIDED, "该申请已处理，无法撤回");
        }
        Event event = requireEvent(leaveRequest.getEventId());
        ensureEventNotStarted(event, "活动已开始，无法撤回");
        leaveRequest.setStatus(LeaveStatus.CANCELED);
        requestMapper.updateById(leaveRequest);
        return toView(leaveRequest, event, applicant);
    }

    // ---------- 列表 ----------

    /** scope=mine 我的申请；needsMe 有我能签的待签行；all 角色范围内全部（docs/05：待我审批的 / 我提交的）。 */
    public PageResult<RequestView> list(String scope, String status, Long eventId,
                                        int page, int size, CurrentUser viewer) {
        LambdaQueryWrapper<LeaveRequest> wrapper = new LambdaQueryWrapper<LeaveRequest>()
                .eq(status != null && !status.isBlank(), LeaveRequest::getStatus, status)
                .eq(eventId != null, LeaveRequest::getEventId, eventId)
                .orderByDesc(LeaveRequest::getId);

        boolean forceMine = viewer.role() == Role.MEMBER || viewer.role() == Role.CONDUCTOR;
        if (forceMine || "mine".equals(scope)) {
            wrapper.eq(LeaveRequest::getMemberId, viewer.memberId());
        } else if (viewer.role() == Role.SECTION_LEADER) {
            // 本声部成员的申请 + 自己的
            List<Long> visibleMemberIds = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                            .eq(Member::getIsSystem, false)
                            .and(w -> w.eq(Member::getSectionId, viewer.sectionId())
                                    .or().eq(Member::getId, viewer.memberId())))
                    .stream().map(Member::getId).toList();
            wrapper.in(LeaveRequest::getMemberId, visibleMemberIds);
        }

        Page<LeaveRequest> result = requestMapper.selectPage(Page.of(page, size), wrapper);
        List<RequestView> views = result.getRecords().stream()
                .map(r -> toView(r, eventMapper.selectById(r.getEventId()), viewer))
                .toList();
        long total = result.getTotal();
        if ("needsMe".equals(scope) && !forceMine) {
            views = views.stream().filter(v ->
                    v.overdue() || v.approvals().stream().anyMatch(ApprovalView::iCanDecide)).toList();
            total = views.size();
        }
        return PageResult.of(views, total, page, size);
    }

    // ---------- 内部 ----------

    private void ensureEventNotStarted(Event event, String message) {
        LocalDateTime start = event.getDate().atTime(event.getStartTime());
        if (!LocalDateTime.now().isBefore(start)) {
            throw new BusinessException(ErrorCode.EVENT_STARTED, message);
        }
    }

    private RequestView toView(LeaveRequest leaveRequest, Event event, CurrentUser viewer) {
        Member requester = memberMapper.selectById(leaveRequest.getMemberId());
        List<LeaveApproval> approvals = approvalsOf(leaveRequest.getId());
        Map<Long, Member> approvers = loadMembers(approvals.stream()
                .map(LeaveApproval::getApproverId).filter(Objects::nonNull).toList());

        LocalDateTime now = LocalDateTime.now();
        boolean overdue = LeaveStatus.PENDING.equals(leaveRequest.getStatus())
                && event != null
                && event.getDate().atTime(event.getEndTime()).isBefore(now);

        List<ApprovalView> approvalViews = approvals.stream()
                .map(a -> new ApprovalView(a.getId(), a.getRequiredRole(), a.getApproverId(),
                        a.getApproverId() == null ? null
                                : approvers.get(a.getApproverId()).getName(),
                        a.getDecision(), a.getDecidedAt(), a.getNote(),
                        viewer != null && LeaveStatus.PENDING.equals(a.getDecision())
                                && LeaveStatus.PENDING.equals(leaveRequest.getStatus())
                                && requester != null
                                && permissionService.canDecideApproval(viewer,
                                        Role.valueOf(requester.getRole()), requester.getSectionId(),
                                        a.getRequiredRole())))
                .toList();

        return new RequestView(leaveRequest.getId(), leaveRequest.getEventId(),
                event == null ? null : event.getTitle(),
                event == null ? null : event.getKind(),
                event == null ? null : event.getDate().toString(),
                event == null ? null : event.getStartTime().toString(),
                event == null ? null : event.getStatus(),
                leaveRequest.getMemberId(),
                requester == null ? null : requester.getName(),
                requester == null ? null : sectionNameOf(requester.getSectionId()),
                leaveRequest.getReason(), leaveRequest.getStatus(), leaveRequest.getAppliedAt(),
                overdue, approvalViews);
    }

    private List<LeaveApproval> approvalsOf(long requestId) {
        return approvalMapper.selectList(new LambdaQueryWrapper<LeaveApproval>()
                .eq(LeaveApproval::getLeaveRequestId, requestId)
                .orderByAsc(LeaveApproval::getId));
    }

    private Map<Long, Member> loadMembers(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
    }

    private Event requireEvent(long id) {
        Event event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return event;
    }

    private LeaveRequest requireRequest(long id) {
        LeaveRequest request = requestMapper.selectById(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "请假申请不存在");
        }
        return request;
    }

    private String sectionNameOf(Long sectionId) {
        if (sectionId == null) {
            return null;
        }
        Section section = sectionMapper.selectById(sectionId);
        return section == null ? null : section.getName();
    }
}
