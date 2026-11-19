package com.forband.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.attendance.dto.AttendanceDtos.AggregateStats;
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
import com.forband.event.EventStatus;
import com.forband.member.Member;
import com.forband.member.MemberLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 考勤统计：个人汇总、聚合，以及「应到 / 出勤 / 请假 / 缺勤 / 出勤率」的统一口径。 */
@Service
public class AttendanceStatsService {

    public record Summary(int expected, int present, int leave, int absent, double rate) {
    }

    private final AttendanceMapper attendanceMapper;
    private final EventMapper eventMapper;
    private final ExpectedRosterResolver rosterResolver;
    private final PermissionService permissionService;
    private final MemberLookup memberLookup;

    public AttendanceStatsService(AttendanceMapper attendanceMapper, EventMapper eventMapper,
                                  ExpectedRosterResolver rosterResolver, PermissionService permissionService,
                                  MemberLookup memberLookup) {
        this.attendanceMapper = attendanceMapper;
        this.eventMapper = eventMapper;
        this.rosterResolver = rosterResolver;
        this.permissionService = permissionService;
        this.memberLookup = memberLookup;
    }

    /** 「应到 / 出勤 / 请假 / 缺勤 / 出勤率」的唯一口径（{@code eventAttendance} 与 {@code sessions} 共用）。 */
    public Summary summarize(int expected, long present, long leave) {
        int absent = expected - (int) present - (int) leave;
        double rate = expected == 0 ? 0 : (double) present / expected;
        return new Summary(expected, (int) present, (int) leave, Math.max(absent, 0), rate);
    }

    public MemberSummary memberSummary(long memberId, LocalDate from, LocalDate to, CurrentUser viewer) {
        // 荣誉团员可看自己的历史出勤汇总（docs/02 §2.3），他人不可。
        // ⚠️ 先判声部长的「本声部」收窄，再判全团白名单；**其余角色（含未来新角色）只能看自己**（#223）。
        if (viewer.role() == Role.SECTION_LEADER) {
            Member targetMember = memberLookup.requireMember(memberId);
            if (!Objects.equals(viewer.sectionId(), targetMember.getSectionId())) {
                throw new BusinessException(ErrorCode.NOT_YOUR_SECTION);
            }
        } else if (!permissionService.canViewAllAttendance(viewer)) {
            permissionService.checkSelf(viewer, memberId);
        }
        Member member = memberLookup.requireMember(memberId);
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
            if (!rosterResolver.isExpected(event, member)) {
                continue;
            }
            expected++;
            Attendance record = attendanceMapper.selectOne(new LambdaQueryWrapper<Attendance>()
                    .eq(Attendance::getEventId, event.getId())
                    .eq(Attendance::getMemberId, member.getId()));
            if (record != null) {
                // 四态（DECISIONS D33）：PARTIAL 计出勤；ABSENT 不加入任一项，仍留在 expected 里,
                // 由 summarize 的守恒式推导为缺勤（不能把 ABSENT 当成请假——旧实现就是 else → leave）
                if (AttendanceStatus.isPresent(record.getStatus())) {
                    present++;
                } else if (AttendanceStatus.LEAVE.equals(record.getStatus())) {
                    leave++;
                }
            }
        }
        Summary summary = summarize(expected, present, leave);
        return new KindSummary(kind, expected == 0 ? 0 : (int) events.stream()
                        .filter(e -> rosterResolver.isExpected(e, member)).count(),
                summary.expected, summary.present, summary.leave, summary.absent, summary.rate);
    }

    private KindSummary aggregateKind(List<Event> events, String kind) {
        List<Event> kindEvents = events.stream().filter(e -> kind.equals(e.getKind())).toList();
        int expected = 0;
        int present = 0;
        int leave = 0;
        for (Event event : kindEvents) {
            java.util.Set<Long> expectedIds = rosterResolver.expectedMembers(event).stream()
                    .map(Member::getId).collect(java.util.stream.Collectors.toSet());
            List<Attendance> records = attendanceMapper.selectList(
                    new LambdaQueryWrapper<Attendance>().eq(Attendance::getEventId, event.getId()))
                    .stream().filter(r -> expectedIds.contains(r.getMemberId())).toList();
            expected += expectedIds.size();
            present += records.stream().filter(r -> AttendanceStatus.isPresent(r.getStatus())).count();
            leave += records.stream().filter(r -> AttendanceStatus.LEAVE.equals(r.getStatus())).count();
        }
        Summary summary = summarize(expected, present, leave);
        return new KindSummary(kind, kindEvents.size(), summary.expected, summary.present,
                summary.leave, summary.absent, summary.rate);
    }
}
