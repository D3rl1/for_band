package com.forband.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.attendance.dto.AttendanceDtos.AggregateStats;
import com.forband.attendance.dto.AttendanceDtos.AttendanceMarkRequest;
import com.forband.attendance.dto.AttendanceDtos.AttendanceRow;
import com.forband.attendance.dto.AttendanceDtos.CheckInRequest;
import com.forband.attendance.dto.AttendanceDtos.EventAttendance;
import com.forband.attendance.dto.AttendanceDtos.KindSummary;
import com.forband.attendance.dto.AttendanceDtos.MemberSummary;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.event.Event;
import com.forband.event.EventKind;
import com.forband.event.EventMapper;
import com.forband.event.EventService;
import com.forband.event.EventStatus;
import com.forband.leave.LeaveRequest;
import com.forband.leave.LeaveRequestMapper;
import com.forband.leave.LeaveStatus;
import com.forband.log.OperationLogWriter;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberRoleHistory;
import com.forband.member.MemberRoleHistoryMapper;
import com.forband.member.MemberStatus;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    /** 签到窗口（docs/04，可用性规则而非判罚规则，D17）：开始前 30 分钟开，结束后 2 小时关。 */
    private static final Duration OPEN_BEFORE = Duration.ofMinutes(30);
    private static final Duration CLOSE_AFTER = Duration.ofHours(2);

    private final AttendanceMapper attendanceMapper;
    private final EventMapper eventMapper;
    private final EventService eventService;
    private final MemberMapper memberMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final SectionMapper sectionMapper;
    private final PermissionService permissionService;
    private final OperationLogWriter opLogWriter;
    private final LeaveRequestMapper leaveRequestMapper;

    public AttendanceService(AttendanceMapper attendanceMapper, EventMapper eventMapper,
                             EventService eventService, MemberMapper memberMapper,
                             MemberRoleHistoryMapper historyMapper,
                             SectionMapper sectionMapper, PermissionService permissionService,
                             OperationLogWriter opLogWriter, LeaveRequestMapper leaveRequestMapper) {
        this.attendanceMapper = attendanceMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
        this.memberMapper = memberMapper;
        this.historyMapper = historyMapper;
        this.sectionMapper = sectionMapper;
        this.permissionService = permissionService;
        this.opLogWriter = opLogWriter;
        this.leaveRequestMapper = leaveRequestMapper;
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
            target = requireMember(request.memberId());
            permissionService.checkCanMarkFor(requester);
            // 有生效中请假的人不出现代签（docs/05 §5.3）——前端藏按钮只是体验，这里是闸门
            ensureNoActiveLeave(eventId, target.getId());
        } else {
            target = requireMember(requester.memberId());
        }
        if (!isExpected(event, target)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该团员不在此活动的应到名单中");
        }
        if (!markingForOther) {
            // 窗口只约束"本人签到"：代签不受限（docs/05 §5.3），否则补录历史场次会被 40003 挡死。
            // ⚠️ 位置不能挪到 isExpected 之前——那会改变错误码优先级（不在应到名单的人也是 40003）。
            ensureInWindow(event);
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
            opLogWriter.write(requester, "ATTENDANCE_MARK_FOR", "attendance", target.getId(),
                    null, Map.of("eventId", eventId, "memberId", target.getId(),
                            "memberName", target.getName()));
        }
        return toRow(attendance, target, requester.memberId(), null);
    }

    @Transactional
    public AttendanceRow changeStatus(long attendanceId, AttendanceMarkRequest request,
                                      CurrentUser operator) {
        if (!AttendanceStatus.PRESENT.equals(request.status())
                && !AttendanceStatus.LEAVE.equals(request.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "状态只能为 PRESENT / LEAVE");
        }
        Attendance attendance = attendanceMapper.selectById(attendanceId);
        if (attendance == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "考勤记录不存在");
        }
        String beforeStatus = attendance.getStatus();   // 留痕要"改前 → 改后"两个都给（docs/11 §11.2）
        attendance.setStatus(request.status());
        attendanceMapper.updateById(attendance);
        opLogWriter.write(operator, "ATTENDANCE_SET_STATUS", "attendance", attendanceId,
                Map.of("status", beforeStatus),
                Map.of("eventId", attendance.getEventId(),
                        "memberId", attendance.getMemberId(), "status", request.status()));
        Member member = memberMapper.selectById(attendance.getMemberId());
        return toRow(attendance, member, null, null);
    }

    // ---------- 单场考勤（角色收窄） ----------

    public EventAttendance eventAttendance(long eventId, CurrentUser viewer) {
        Event event = requireEvent(eventId);
        eventService.ensureVisible(event, viewer);

        List<Attendance> records = attendanceMapper.selectList(new LambdaQueryWrapper<Attendance>()
                .eq(Attendance::getEventId, eventId));
        Map<Long, Attendance> byMember = records.stream()
                .collect(Collectors.toMap(Attendance::getMemberId, Function.identity()));

        List<Member> expected = expectedMembers(event);
        Map<Long, String> leaveStatuses = leaveStatusesOf(eventId);
        // 荣誉团员与普通团员同样只看本人行（docs/02 §2.3：他人考勤不可见）
        boolean canSeeAll = viewer.role() != Role.MEMBER && viewer.role() != Role.HONORARY;
        List<AttendanceRow> rows = expected.stream()
                .filter(m -> canSeeAll || m.getId() == viewer.memberId()
                        || (viewer.role() == Role.SECTION_LEADER
                            && viewer.sectionId() != null
                            && viewer.sectionId().equals(m.getSectionId())))
                .map(m -> {
                    Attendance record = byMember.get(m.getId());
                    if (record == null) {
                        return new AttendanceRow(null, m.getId(), m.getName(), m.getAvatar(),
                                sectionNameOf(m.getSectionId()),
                                null, null, null, null, leaveStatuses.get(m.getId()));
                    }
                    return toRow(record, m, null, leaveStatuses.get(m.getId()));
                })
                .toList();

        Summary summary = summarize(expected.size(),
                records.stream().filter(r -> AttendanceStatus.PRESENT.equals(r.getStatus())).count(),
                records.stream().filter(r -> AttendanceStatus.LEAVE.equals(r.getStatus())).count());
        return new EventAttendance(event.getId(), event.getTitle(), event.getDate(), event.getKind(),
                event.getStatus(), rows, summary.expected, summary.present, summary.leave,
                summary.absent, summary.rate);
    }

    // ---------- 个人汇总 / 聚合 ----------

    public MemberSummary memberSummary(long memberId, LocalDate from, LocalDate to, CurrentUser viewer) {
        // 荣誉团员可看自己的历史出勤汇总（docs/02 §2.3），他人不可
        if (viewer.role() == Role.MEMBER || viewer.role() == Role.HONORARY) {
            permissionService.checkSelf(viewer, memberId);
        } else if (viewer.role() == Role.SECTION_LEADER) {
            Member targetMember = requireMember(memberId);
            if (!Objects.equals(viewer.sectionId(), targetMember.getSectionId())) {
                throw new BusinessException(ErrorCode.NOT_YOUR_SECTION);
            }
        } else {
            permissionService.checkRole(viewer, Role.ADMIN, Role.LEADER, Role.CONDUCTOR);
        }
        Member member = requireMember(memberId);
        KindSummary rehearsal = personalKindSummary(member, EventKind.REHEARSAL, from, to);
        KindSummary sectionClass = personalKindSummary(member, EventKind.SECTION_CLASS, from, to);
        return new MemberSummary(memberId, member.getName(), rehearsal, sectionClass);
    }

    public AggregateStats aggregate(LocalDate from, LocalDate to, CurrentUser viewer) {
        List<Event> events = eventMapper.selectList(new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, EventStatus.NORMAL)
                .ge(from != null, Event::getDate, from)
                .le(to != null, Event::getDate, to));
        boolean sectionLeaderScoped = viewer.role() == Role.SECTION_LEADER;
        events = events.stream()
                .filter(e -> !EventKind.isSectionClass(e.getKind())
                        || !sectionLeaderScoped
                        || Objects.equals(viewer.sectionId(), e.getSectionId()))
                .toList();

        KindSummary rehearsal = aggregateKind(events, EventKind.REHEARSAL);
        KindSummary sectionClass = aggregateKind(events, EventKind.SECTION_CLASS);
        return new AggregateStats(List.of(rehearsal, sectionClass));
    }

    // ---------- 导出 ----------

    public String exportCsv(LocalDate from, LocalDate to, String kind) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, EventStatus.NORMAL)
                .ge(from != null, Event::getDate, from)
                .le(to != null, Event::getDate, to)
                .eq(kind != null && !kind.isBlank(), Event::getKind, kind)
                .orderByAsc(Event::getDate);
        List<Event> events = eventMapper.selectList(wrapper);

        StringBuilder csv = new StringBuilder("\uFEFF日期,类型,活动,团员,声部,状态,签到时间,代签人\r\n");
        for (Event event : events) {
            Map<Long, Attendance> byMember = attendanceMapper.selectList(
                            new LambdaQueryWrapper<Attendance>().eq(Attendance::getEventId, event.getId()))
                    .stream().collect(Collectors.toMap(Attendance::getMemberId, Function.identity()));
            Map<Long, Member> markers = byMember.isEmpty() ? Map.of()
                    : memberMapper.selectBatchIds(byMember.values().stream()
                            .map(Attendance::getMarkedBy).distinct().toList()).stream()
                            .collect(Collectors.toMap(Member::getId, Function.identity()));
            for (Member member : expectedMembers(event)) {
                Attendance record = byMember.get(member.getId());
                String status = record == null ? "缺勤"
                        : AttendanceStatus.LEAVE.equals(record.getStatus()) ? "请假" : "出勤";
                String markedAt = record == null ? "" : record.getMarkedAt().toString();
                String markedBy = record == null || record.getMarkedBy().equals(member.getId()) ? ""
                        : markers.get(record.getMarkedBy()).getName();
                csv.append(escape(event.getDate().toString())).append(',')
                        .append(escape(kindLabel(event.getKind()))).append(',')
                        .append(escape(event.getTitle())).append(',')
                        .append(escape(member.getName())).append(',')
                        .append(escape(sectionNameOf(member.getSectionId()))).append(',')
                        .append(status).append(',')
                        .append(markedAt).append(',')
                        .append(escape(markedBy)).append("\r\n");
            }
        }
        return csv.toString();
    }

    // ---------- 内部 ----------

    private record Summary(int expected, int present, int leave, int absent, double rate) {
    }

    private Summary summarize(int expected, long present, long leave) {
        int absent = expected - (int) present - (int) leave;
        double rate = expected == 0 ? 0 : (double) present / expected;
        return new Summary(expected, (int) present, (int) leave, Math.max(absent, 0), rate);
    }

    /**
     * 应到名单（不变式 3/7/14）：在团、非系统账号、**非指挥**、入退团日期覆盖活动当天。
     * 指挥不参与考勤统计（不进应到名单 ⇒ 不能签到、不进分母），但查看权照旧（docs/02 §2.3）。
     * <p>
     * ⚠️ **声部匹配不能在 SQL 里按 `member.section_id` 预筛**——那只有当前值，
     * 会把换过声部的人直接筛掉，`isExpected` 里的回溯根本没机会跑。
     * 所以这里只按"在团 / 非系统 / 入退团日期"粗筛，声部判定全部交给
     * {@link #sectionAt}（按 `event.date` 回溯历史）。
     * <p>
     * 代价：声部课要把全团 ACTIVE 成员捞出来再过滤。40 人量级可忽略（D4）。
     */
    private List<Member> expectedMembers(Event event) {
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .eq(Member::getIsSystem, false)
                .eq(Member::getStatus, MemberStatus.ACTIVE)
                .ne(Member::getRole, Role.CONDUCTOR.name())
                .and(w -> w.isNull(Member::getJoinedAt).or().le(Member::getJoinedAt, event.getDate()))
                .and(w -> w.isNull(Member::getLeftAt).or().ge(Member::getLeftAt, event.getDate()))
                .orderByAsc(Member::getId);
        return memberMapper.selectList(wrapper).stream()
                .filter(m -> isExpected(event, m))
                .toList();
    }

    private boolean isExpected(Event event, Member member) {
        if (!MemberStatus.ACTIVE.equals(member.getStatus()) || Boolean.TRUE.equals(member.getIsSystem())
                || Role.CONDUCTOR.name().equals(member.getRole())) {
            return false;
        }
        if (EventKind.isSectionClass(event.getKind())
                && !Objects.equals(sectionAt(event.getDate(), member), event.getSectionId())) {
            return false;
        }
        LocalDate date = event.getDate();
        if (member.getJoinedAt() != null && member.getJoinedAt().isAfter(date)) {
            return false;
        }
        return member.getLeftAt() == null || !member.getLeftAt().isBefore(date);
    }

    /**
     * 某人在**某一天**属于哪个声部（docs/13 不变式 7）。
     * <p>
     * 不能直接用 {@code member.sectionId} —— 那只有**当前值**。团员可以换声部，
     * 若按当前声部算历史声部课，**旧声部少算一人、新声部多算一人，两边都错**。
     * 所以按 {@code event.date} 在 {@code member_role_history} 里回溯当时那一段
     * （不变式 8 要求变更角色时必须写历史，所以这里查得到）。
     * <p>
     * 查不到历史记录时退回当前声部：说明这人从没换过声部，"当时"就等于"现在"。
     * <p>
     * 注：这里是逐条查（40 人 × 几十场量级完全无压力，见 D4）。真到几千人再谈批量预取。
     */
    private Long sectionAt(LocalDate date, Member member) {
        MemberRoleHistory h = historyMapper.selectOne(
                new LambdaQueryWrapper<MemberRoleHistory>()
                        .eq(MemberRoleHistory::getMemberId, member.getId())
                        .le(MemberRoleHistory::getFromDate, date)
                        .and(w -> w.isNull(MemberRoleHistory::getToDate)
                                .or().ge(MemberRoleHistory::getToDate, date))
                        .orderByDesc(MemberRoleHistory::getFromDate)
                        .last("LIMIT 1"));
        return h == null ? member.getSectionId() : h.getSectionId();
    }

    private void ensureInWindow(Event event) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = event.getDate().atTime(event.getStartTime());
        LocalDateTime end = event.getDate().atTime(event.getEndTime());
        if (now.isBefore(start.minus(OPEN_BEFORE)) || now.isAfter(end.plus(CLOSE_AFTER))) {
            throw new BusinessException(ErrorCode.NOT_IN_WINDOW);
        }
    }

    /**
     * "生效中的请假" = PENDING / APPROVED（与 `LeaveService.create` 的重复申请判定同一口径）。
     * 已驳回 / 已撤回不算——他该来，要能补签（docs/05 §5.3）。
     */
    private void ensureNoActiveLeave(long eventId, long memberId) {
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

    private KindSummary personalKindSummary(Member member, String kind, LocalDate from, LocalDate to) {
        List<Event> events = eventMapper.selectList(new LambdaQueryWrapper<Event>()
                .eq(Event::getKind, kind)
                .eq(Event::getStatus, EventStatus.NORMAL)
                .ge(from != null, Event::getDate, from)
                .le(to != null, Event::getDate, to));
        int expected = 0;
        int present = 0;
        int leave = 0;
        for (Event event : events) {
            if (!isExpected(event, member)) {
                continue;
            }
            expected++;
            Attendance record = attendanceMapper.selectOne(new LambdaQueryWrapper<Attendance>()
                    .eq(Attendance::getEventId, event.getId())
                    .eq(Attendance::getMemberId, member.getId()));
            if (record != null) {
                if (AttendanceStatus.PRESENT.equals(record.getStatus())) {
                    present++;
                } else {
                    leave++;
                }
            }
        }
        Summary summary = summarize(expected, present, leave);
        return new KindSummary(kind, expected == 0 ? 0 : (int) events.stream()
                        .filter(e -> isExpected(e, member)).count(),
                summary.expected, summary.present, summary.leave, summary.absent, summary.rate);
    }

    private KindSummary aggregateKind(List<Event> events, String kind) {
        List<Event> kindEvents = events.stream().filter(e -> kind.equals(e.getKind())).toList();
        int expected = 0;
        int present = 0;
        int leave = 0;
        for (Event event : kindEvents) {
            List<Attendance> records = attendanceMapper.selectList(
                    new LambdaQueryWrapper<Attendance>().eq(Attendance::getEventId, event.getId()));
            expected += expectedMembers(event).size();
            present += records.stream().filter(r -> AttendanceStatus.PRESENT.equals(r.getStatus())).count();
            leave += records.stream().filter(r -> AttendanceStatus.LEAVE.equals(r.getStatus())).count();
        }
        Summary summary = summarize(expected, present, leave);
        return new KindSummary(kind, kindEvents.size(), summary.expected, summary.present,
                summary.leave, summary.absent, summary.rate);
    }

    private AttendanceRow toRow(Attendance record, Member member, Long selfId, String leaveStatus) {
        String markedByName = null;
        if (record.getMarkedBy() != null && !record.getMarkedBy().equals(member.getId())) {
            Member marker = memberMapper.selectById(record.getMarkedBy());
            markedByName = marker == null ? null : marker.getName();
        }
        return new AttendanceRow(record.getId(), member.getId(), member.getName(), member.getAvatar(),
                sectionNameOf(member.getSectionId()),
                record.getStatus(), record.getMarkedAt(), record.getMarkedBy(), markedByName, leaveStatus);
    }

    private Event requireEvent(long id) {
        Event event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return event;
    }

    private Member requireMember(long id) {
        Member member = memberMapper.selectById(id);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团员不存在");
        }
        return member;
    }

    private String sectionNameOf(Long sectionId) {
        if (sectionId == null) {
            return null;
        }
        Section section = sectionMapper.selectById(sectionId);
        return section == null ? null : section.getName();
    }

    private String kindLabel(String kind) {
        return switch (kind) {
            case EventKind.REHEARSAL -> "排练";
            case EventKind.SECTION_CLASS -> "声部课";
            case EventKind.DRESS_REHEARSAL -> "彩排";
            default -> kind;
        };
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
