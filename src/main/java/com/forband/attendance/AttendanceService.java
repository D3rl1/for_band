package com.forband.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.attendance.dto.AttendanceDtos.AggregateStats;
import com.forband.attendance.dto.AttendanceDtos.AttendanceMarkRequest;
import com.forband.attendance.dto.AttendanceDtos.AttendanceRow;
import com.forband.attendance.dto.AttendanceDtos.CheckInRequest;
import com.forband.attendance.dto.AttendanceDtos.EventAttendance;
import com.forband.attendance.dto.AttendanceDtos.MemberSummary;
import com.forband.attendance.dto.AttendanceDtos.SessionView;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.common.SectionLookup;
import com.forband.event.Event;
import com.forband.event.EventKind;
import com.forband.event.EventMapper;
import com.forband.event.EventService;
import com.forband.event.EventStatus;
import com.forband.leave.LeaveRequest;
import com.forband.leave.LeaveRequestMapper;
import com.forband.leave.LeaveStatus;
import com.forband.log.OpAction;
import com.forband.log.OperationLogWriter;
import com.forband.member.Member;
import com.forband.member.MemberLookup;
import com.forband.member.MemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考勤门面：签到 / 代签、单场考勤、历次场次。
 * 应到名单与声部回溯委派给 {@link ExpectedRosterResolver}，统计口径委派给 {@link AttendanceStatsService}，
 * 导出委派给 {@link AttendanceCsvExporter}，签到窗口委派给 {@link CheckInWindow}。
 */
@Service
public class AttendanceService {

    private final AttendanceMapper attendanceMapper;
    private final EventMapper eventMapper;
    private final EventService eventService;
    private final MemberMapper memberMapper;
    private final MemberLookup memberLookup;
    private final SectionLookup sectionLookup;
    private final PermissionService permissionService;
    private final OperationLogWriter opLogWriter;
    private final LeaveRequestMapper leaveRequestMapper;
    private final ExpectedRosterResolver rosterResolver;
    private final AttendanceStatsService statsService;
    private final AttendanceCsvExporter csvExporter;
    private final CheckInWindow checkInWindow;

    public AttendanceService(AttendanceMapper attendanceMapper, EventMapper eventMapper,
                             EventService eventService, MemberMapper memberMapper,
                             MemberLookup memberLookup, SectionLookup sectionLookup,
                             PermissionService permissionService, OperationLogWriter opLogWriter,
                             LeaveRequestMapper leaveRequestMapper, ExpectedRosterResolver rosterResolver,
                             AttendanceStatsService statsService, AttendanceCsvExporter csvExporter,
                             CheckInWindow checkInWindow) {
        this.attendanceMapper = attendanceMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
        this.memberMapper = memberMapper;
        this.memberLookup = memberLookup;
        this.sectionLookup = sectionLookup;
        this.permissionService = permissionService;
        this.opLogWriter = opLogWriter;
        this.leaveRequestMapper = leaveRequestMapper;
        this.rosterResolver = rosterResolver;
        this.statsService = statsService;
        this.csvExporter = csvExporter;
        this.checkInWindow = checkInWindow;
    }

    // ---------- 签到 / 代签 ----------

    @Transactional
    public AttendanceRow checkIn(long eventId, CheckInRequest request, CurrentUser requester) {
        Event event = requireEvent(eventId);
        eventService.ensureVisible(event, requester);
        if (EventStatus.CANCELED.equals(event.getStatus())) {
            throw new BusinessException(ErrorCode.EVENT_CANCELED);
        }
        Member target;
        boolean markingForOther = request != null && request.memberId() != null
                && request.memberId() != requester.memberId();
        if (markingForOther) {
            target = memberLookup.requireMember(request.memberId());
            permissionService.checkCanMarkFor(requester);
            // 有生效中请假的人不出现代签（docs/05 §5.3）——前端藏按钮只是体验，这里是闸门
            ensureNoActiveLeave(eventId, target.getId());
        } else {
            target = memberLookup.requireMember(requester.memberId());
        }
        if (!rosterResolver.isExpected(event, target)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该团员不在此活动的应到名单中");
        }
        if (!markingForOther) {
            // 窗口只约束"本人签到"：代签不受限（docs/05 §5.3），否则补录历史场次会被 40003 挡死。
            // ⚠️ 位置不能挪到 isExpected 之前——那会改变错误码优先级（不在应到名单的人也是 40003）。
            checkInWindow.assertOpen(event);
        }

        Attendance attendance = new Attendance();
        attendance.setEventId(eventId);
        attendance.setMemberId(target.getId());
        attendance.setStatus(AttendanceStatus.PRESENT);
        attendance.setMarkedAt(LocalDateTime.now());
        attendance.setMarkedBy(requester.memberId());
        try {
            attendanceMapper.insert(attendance);
        } catch (DuplicateKeyException e) {
            // 幂等：重复签到按"已签到"返回（docs/10 40002），前端当成功提示
            throw new BusinessException(ErrorCode.ALREADY_CHECKED_IN);
        }
        if (markingForOther) {
            opLogWriter.write(requester, OpAction.ATTENDANCE_MARK_FOR, "attendance", target.getId(),
                    null, Map.of("eventId", eventId, "memberId", target.getId(),
                            "memberName", target.getName()));
        }
        return toRow(attendance, target, null);
    }

    @Transactional
    public AttendanceRow changeStatus(long attendanceId, AttendanceMarkRequest request,
                                      CurrentUser operator) {
        String newStatus = request.status();
        // 四态（DECISIONS D33）：只能改**已有**记录；ABSENT 同样是改判，绝不通过本接口补一行
        if (newStatus == null || !Set.of(AttendanceStatus.PRESENT, AttendanceStatus.PARTIAL,
                AttendanceStatus.LEAVE, AttendanceStatus.ABSENT).contains(newStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "状态只能为 PRESENT / PARTIAL / LEAVE / ABSENT");
        }
        Attendance attendance = attendanceMapper.selectById(attendanceId);
        if (attendance == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "考勤记录不存在");
        }
        String beforeStatus = attendance.getStatus();   // 留痕要"改前 → 改后"两个都给（docs/11 §11.2）
        attendance.setStatus(newStatus);
        attendanceMapper.updateById(attendance);
        opLogWriter.write(operator, OpAction.ATTENDANCE_SET_STATUS, "attendance", attendanceId,
                Map.of("status", beforeStatus),
                Map.of("eventId", attendance.getEventId(),
                        "memberId", attendance.getMemberId(), "status", request.status()));
        Member member = memberMapper.selectById(attendance.getMemberId());
        return toRow(attendance, member, null);
    }

    // ---------- 单场考勤（角色收窄） ----------

    public EventAttendance eventAttendance(long eventId, CurrentUser viewer) {
        Event event = requireEvent(eventId);
        eventService.ensureVisible(event, viewer);

        List<Attendance> records = attendanceMapper.selectList(new LambdaQueryWrapper<Attendance>()
                .eq(Attendance::getEventId, eventId));
        Map<Long, Attendance> byMember = records.stream()
                .collect(Collectors.toMap(Attendance::getMemberId, Function.identity()));

        List<Member> expected = rosterResolver.expectedMembers(event);
        Map<Long, String> leaveStatuses = leaveStatusesOf(eventId);
        // 荣誉团员与普通团员同样只看本人行（docs/02 §2.3：他人考勤不可见）。
        // ⚠️ 白名单判在 PermissionService（#223）：新角色默认拒绝，别再写「非 MEMBER」的反向推导。
        boolean canSeeAll = permissionService.canViewAllAttendance(viewer);
        List<AttendanceRow> rows = expected.stream()
                .filter(m -> canSeeAll || m.getId() == viewer.memberId()
                        || (viewer.role() == Role.SECTION_LEADER
                            && viewer.sectionId() != null
                            && viewer.sectionId().equals(m.getSectionId())))
                .map(m -> {
                    Attendance record = byMember.get(m.getId());
                    if (record == null) {
                        return new AttendanceRow(null, m.getId(), m.getName(), m.getAvatar(),
                                m.getAvatarFrame(), sectionLookup.sectionNameOf(m.getSectionId()),
                                null, null, null, null, leaveStatuses.get(m.getId()));
                    }
                    return toRow(record, m, leaveStatuses.get(m.getId()));
                })
                .toList();

        AttendanceStatsService.Summary summary = statsService.summarize(expected.size(),
                expected.stream().map(m -> byMember.get(m.getId())).filter(Objects::nonNull)
                        .filter(r -> AttendanceStatus.isPresent(r.getStatus())).count(),
                expected.stream().map(m -> byMember.get(m.getId())).filter(Objects::nonNull)
                        .filter(r -> AttendanceStatus.LEAVE.equals(r.getStatus())).count());
        return new EventAttendance(event.getId(), event.getTitle(), event.getDate(), event.getKind(),
                event.getStatus(), rows, summary.expected(), summary.present(), summary.leave(),
                summary.absent(), summary.rate());
    }

    // ---------- 个人汇总 / 聚合（委派） ----------

    public MemberSummary memberSummary(long memberId, LocalDate from, LocalDate to, CurrentUser viewer) {
        return statsService.memberSummary(memberId, from, to, viewer);
    }

    public AggregateStats aggregate(LocalDate from, LocalDate to, CurrentUser viewer) {
        return statsService.aggregate(from, to, viewer);
    }

    /**
     * 「历次场次」列表（`docs/05` §5.4 的主视图改版，`交接单 #110`）：**每行一场**，按时间倒序。
     * <p>
     * ⚠️ **不新算一套**：每行的四个数就是 {@link #eventAttendance} 的口径
     * （{@code expectedMembers} + {@code summarize}）——「**应到场次的计算口径一个字不动**」是规格的硬要求。
     * <p>
     * ⚠️ **按角色裁剪**（照 {@code canViewPhone} 的先例，判在 service、**不拆成两个接口**）：
     * 管理层拿得到每场的四个数；**普通团员 / 荣誉团员拿到的五个数是 `null`**，
     * 只拿 {@code mine}（我在这场是出勤 / 请假 / 缺勤 / 没轮到我）。
     */
    public PageResult<SessionView> sessions(int page, int size, CurrentUser viewer) {
        boolean canSeeAll = permissionService.canViewAllAttendance(viewer);
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, EventStatus.NORMAL)      // 取消掉的活动不进统计（口径同 aggregate）
                .orderByDesc(Event::getDate)
                .orderByDesc(Event::getId);
        // 声部课按声部收窄：**声部长与团员都只该看到本声部的声部课**（团级活动大家都看得到）。
        // ⚠️ 这一步**必须写进 SQL**：放到 Java 里过滤会让分页算错（第 1 页可能凭空少几行）。
        if (viewer.role() == Role.SECTION_LEADER || !canSeeAll) {
            if (viewer.sectionId() == null) {
                wrapper.ne(Event::getKind, EventKind.SECTION_CLASS);
            } else {
                wrapper.and(w -> w.ne(Event::getKind, EventKind.SECTION_CLASS)
                        .or().eq(Event::getSectionId, viewer.sectionId()));
            }
        }
        Page<Event> result = eventMapper.selectPage(Page.of(page, size), wrapper);

        Member me = memberMapper.selectById(viewer.memberId());
        Map<Long, String> myStatuses = attendanceMapper.selectList(
                        new LambdaQueryWrapper<Attendance>().eq(Attendance::getMemberId, viewer.memberId()))
                .stream().collect(Collectors.toMap(Attendance::getEventId, Attendance::getStatus, (a, b) -> a));

        List<SessionView> views = result.getRecords().stream().map(event -> {
            String mine = rosterResolver.mineAt(event, me, myStatuses.get(event.getId()));
            if (!canSeeAll) {
                return new SessionView(event.getId(), event.getTitle(), event.getDate(), event.getKind(),
                        null, null, null, null, null, mine);
            }
            List<Member> expected = rosterResolver.expectedMembers(event);
            Set<Long> expectedIds = expected.stream().map(Member::getId).collect(Collectors.toSet());
            List<Attendance> records = attendanceMapper.selectList(
                    new LambdaQueryWrapper<Attendance>().eq(Attendance::getEventId, event.getId()))
                    .stream().filter(r -> expectedIds.contains(r.getMemberId())).toList();
            AttendanceStatsService.Summary summary = statsService.summarize(expected.size(),
                    records.stream().filter(r -> AttendanceStatus.isPresent(r.getStatus())).count(),
                    records.stream().filter(r -> AttendanceStatus.LEAVE.equals(r.getStatus())).count());
            return new SessionView(event.getId(), event.getTitle(), event.getDate(), event.getKind(),
                    summary.expected(), summary.present(), summary.leave(), summary.absent(),
                    summary.rate(), mine);
        }).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    // ---------- 导出（委派） ----------

    public String exportCsv(LocalDate from, LocalDate to, String kind) {
        return csvExporter.export(from, to, kind);
    }

    // ---------- 内部 ----------

    private void ensureNoActiveLeave(long eventId, long memberId) {
        // "生效中的请假" = PENDING / APPROVED（与 LeaveService.create 的重复申请判定同一口径）。
        // 已驳回 / 已撤回不算——他该来，要能补签（docs/05 §5.3）。
        Long active = leaveRequestMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getEventId, eventId)
                .eq(LeaveRequest::getMemberId, memberId)
                .in(LeaveRequest::getStatus, LeaveStatus.PENDING, LeaveStatus.APPROVED));
        if (active != null && active > 0) {
            throw new BusinessException(ErrorCode.ALREADY_ON_LEAVE);
        }
    }

    /**
     * 一场活动里每个人的请假状态（docs/05 §5.6：出参必须区分得出"申请中"）。
     * 按 id 升序取、后者覆盖 → 落到**最新那条**：同一人同一场最多只有一条生效中的申请
     * （`create` 在已有生效中申请时会拦），所以"最新"就是"当下有效"的那个状态。
     */
    private Map<Long, String> leaveStatusesOf(long eventId) {
        return leaveRequestMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                        .eq(LeaveRequest::getEventId, eventId)
                        .orderByAsc(LeaveRequest::getId))
                .stream()
                .collect(Collectors.toMap(LeaveRequest::getMemberId, LeaveRequest::getStatus, (a, b) -> b));
    }

    private AttendanceRow toRow(Attendance record, Member member, String leaveStatus) {
        String markedByName = null;
        if (record.getMarkedBy() != null && !record.getMarkedBy().equals(member.getId())) {
            Member marker = memberMapper.selectById(record.getMarkedBy());
            markedByName = marker == null ? null : marker.getName();
        }
        return new AttendanceRow(record.getId(), member.getId(), member.getName(), member.getAvatar(),
                member.getAvatarFrame(), sectionLookup.sectionNameOf(member.getSectionId()),
                record.getStatus(), record.getMarkedAt(), record.getMarkedBy(), markedByName, leaveStatus);
    }

    private Event requireEvent(long id) {
        Event event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return event;
    }
}
