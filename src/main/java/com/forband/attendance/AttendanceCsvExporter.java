package com.forband.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.common.CsvWriter;
import com.forband.common.SectionLookup;
import com.forband.event.Event;
import com.forband.event.EventKind;
import com.forband.event.EventMapper;
import com.forband.event.EventStatus;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 考勤明细导出：每场活动 × 每个应到团员一行。 */
@Component
public class AttendanceCsvExporter {

    private final AttendanceMapper attendanceMapper;
    private final EventMapper eventMapper;
    private final MemberMapper memberMapper;
    private final SectionLookup sectionLookup;
    private final ExpectedRosterResolver rosterResolver;

    public AttendanceCsvExporter(AttendanceMapper attendanceMapper, EventMapper eventMapper,
                                 MemberMapper memberMapper, SectionLookup sectionLookup,
                                 ExpectedRosterResolver rosterResolver) {
        this.attendanceMapper = attendanceMapper;
        this.eventMapper = eventMapper;
        this.memberMapper = memberMapper;
        this.sectionLookup = sectionLookup;
        this.rosterResolver = rosterResolver;
    }

    public String export(LocalDate from, LocalDate to, String kind) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, EventStatus.NORMAL)
                .ge(from != null, Event::getDate, from)
                .le(to != null, Event::getDate, to)
                .eq(kind != null && !kind.isBlank(), Event::getKind, kind)
                .orderByAsc(Event::getDate);
        List<Event> events = eventMapper.selectList(wrapper);

        StringBuilder csv = new StringBuilder(CsvWriter.BOM + "日期,类型,活动,团员,声部,状态,签到时间,代签人\r\n");
        for (Event event : events) {
            Map<Long, Attendance> byMember = attendanceMapper.selectList(
                            new LambdaQueryWrapper<Attendance>().eq(Attendance::getEventId, event.getId()))
                    .stream().collect(Collectors.toMap(Attendance::getMemberId, Function.identity()));
            Map<Long, Member> markers = byMember.isEmpty() ? Map.of()
                    : memberMapper.selectBatchIds(byMember.values().stream()
                            .map(Attendance::getMarkedBy).distinct().toList()).stream()
                            .collect(Collectors.toMap(Member::getId, Function.identity()));
            for (Member member : rosterResolver.expectedMembers(event)) {
                Attendance record = byMember.get(member.getId());
                String status = record == null ? "缺勤" : statusLabel(record.getStatus());
                String markedAt = record == null ? "" : record.getMarkedAt().toString();
                String markedBy = record == null || record.getMarkedBy().equals(member.getId()) ? ""
                        : markers.get(record.getMarkedBy()).getName();
                csv.append(CsvWriter.escape(event.getDate().toString())).append(',')
                        .append(CsvWriter.escape(kindLabel(event.getKind()))).append(',')
                        .append(CsvWriter.escape(event.getTitle())).append(',')
                        .append(CsvWriter.escape(member.getName())).append(',')
                        .append(CsvWriter.escape(sectionLookup.sectionNameOf(member.getSectionId()))).append(',')
                        .append(status).append(',')
                        .append(markedAt).append(',')
                        .append(CsvWriter.escape(markedBy)).append("\r\n");
            }
        }
        return csv.toString();
    }

    /**
     * 四态中文名（DECISIONS D33）。**不要有"其它 ⇒ 出勤"的兜底**：未知值宁可原样输出，
     * 也不能把一条读不懂的记录悄悄算成到场；null/空白按缺勤处理。
     */
    private String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "缺勤";
        }
        return switch (status) {
            case AttendanceStatus.PRESENT -> "出勤";
            case AttendanceStatus.PARTIAL -> "遗憾离场";
            case AttendanceStatus.LEAVE -> "请假";
            case AttendanceStatus.ABSENT -> "缺勤";
            default -> status;
        };
    }

    private String kindLabel(String kind) {
        return switch (kind) {
            case EventKind.REHEARSAL -> "排练";
            case EventKind.SECTION_CLASS -> "声部课";
            case EventKind.DRESS_REHEARSAL -> "彩排";
            default -> kind;
        };
    }
}
