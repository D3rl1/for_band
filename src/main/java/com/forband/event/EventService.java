package com.forband.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.attendance.Attendance;
import com.forband.attendance.AttendanceMapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.event.dto.EventBatchRequest;
import com.forband.event.dto.EventCreateRequest;
import com.forband.event.dto.EventUpdateRequest;
import com.forband.event.dto.EventView;
import com.forband.log.OpLogRecorder;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventMapper eventMapper;
    private final SectionMapper sectionMapper;
    private final MemberMapper memberMapper;
    private final AttendanceMapper attendanceMapper;
    private final PermissionService permissionService;

    public EventService(EventMapper eventMapper, SectionMapper sectionMapper,
                        MemberMapper memberMapper, AttendanceMapper attendanceMapper,
                        PermissionService permissionService) {
        this.eventMapper = eventMapper;
        this.sectionMapper = sectionMapper;
        this.memberMapper = memberMapper;
        this.attendanceMapper = attendanceMapper;
        this.permissionService = permissionService;
    }

    // ---------- 查询 ----------

    /** 可见性在查询层过滤（docs/04）：团员只看非声部课 + 本声部声部课。 */
    public PageResult<EventView> list(String kind, LocalDate from, LocalDate to,
                                      int page, int size, CurrentUser viewer) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(kind != null && !kind.isBlank(), Event::getKind, kind)
                .ge(from != null, Event::getDate, from)
                .le(to != null, Event::getDate, to);
        if (viewer.role() == Role.MEMBER) {
            wrapper.and(w -> w.ne(Event::getKind, EventKind.SECTION_CLASS)
                    .or(o -> o.eq(Event::getKind, EventKind.SECTION_CLASS)
                            .eq(Event::getSectionId, viewer.sectionId())));
        }
        wrapper.orderByAsc(Event::getDate).orderByAsc(Event::getStartTime);
        Page<Event> result = eventMapper.selectPage(Page.of(page, size), wrapper);

        Map<Long, Section> sections = loadSections(
                result.getRecords().stream().map(Event::getSectionId).toList());
        Map<Long, Member> members = loadMembers(result.getRecords().stream()
                .flatMap(e -> nonNullIds(e.getCreatedBy(), e.getUpdatedBy()).stream())
                .distinct().toList());
        List<EventView> views = result.getRecords().stream()
                .map(e -> toView(e, sections, members)).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    public EventView get(long id, CurrentUser viewer) {
        Event event = requireEvent(id);
        ensureVisible(event, viewer);
        Map<Long, Section> sections = loadSections(nonNullIds(event.getSectionId()));
        Map<Long, Member> members = loadMembers(
                nonNullIds(event.getCreatedBy(), event.getUpdatedBy()));
        Attendance mine = attendanceMapper.selectOne(new LambdaQueryWrapper<Attendance>()
                .eq(Attendance::getEventId, id)
                .eq(Attendance::getMemberId, viewer.memberId()));
        EventView view = toView(event, sections, members);
        return new EventView(view.id(), view.kind(), view.title(), view.date(), view.startTime(),
                view.endTime(), view.location(), view.sectionId(), view.sectionName(),
                view.content(), view.batchId(), view.status(), view.createdBy(),
                view.createdByName(), view.updatedBy(), view.updatedByName(),
                view.createdAt(), view.updatedAt(),
                mine == null ? null : mine.getStatus());
    }

    // ---------- 写操作 ----------

    @Transactional
    public EventView create(EventCreateRequest request, CurrentUser operator) {
        checkCreatePermission(operator, request.kind(), request.sectionId());
        validateKindAndSection(request.kind(), request.sectionId());

        validateTimeRange(request.startTime(), request.endTime());

        Event event = new Event();
        applyCreate(event, request.kind(), request.title(), request.date(), request.startTime(),
                request.endTime(), request.location(), request.sectionId(), request.content());
        event.setCreatedBy(operator.memberId());
        event.setStatus(EventStatus.NORMAL);
        eventMapper.insert(event);
        return get(event.getId(), operator);
    }

    /**
     * 活动时间校验（docs/13 不变式 12、docs/04「时间校验」）。
     * <p>
     * 两个字段都是 {@code time}（无日期），所以 {@code start < end} **同时**意味着"不跨天"——
     * 跨午夜的活动在这里会被直接拒掉。这是有意的：签到窗口 = {@code end + 2h}，
     * 跨天会算到第二天去，而民乐团没有跨天场景。
     * <p>
     * ⚠️ 三条路径都要校验：单场创建 / 单场修改 / 批量生成。
     * 曾经只在批量里拦，单场那两条路径能直接打接口绕过去。
     */
    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少开始或结束时间");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始时间晚于结束时间");
        }
        if (start.equals(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始时间与结束时间不能相同");
        }
    }

    @Transactional
    public List<EventView> batchCreate(EventBatchRequest request, CurrentUser operator) {
        checkCreatePermission(operator, request.kind(), request.sectionId());
        validateKindAndSection(request.kind(), request.sectionId());
        if (request.startDate().isAfter(request.endDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期晚于结束日期");
        }
        validateTimeRange(request.startTime(), request.endTime());
        List<DayOfWeek> weekdays = request.weekdays().stream()
                .map(d -> DayOfWeek.of(((d - 1) % 7) + 1))
                .distinct().toList();

        String batchId = UUID.randomUUID().toString();
        List<Event> created = new ArrayList<>();
        for (LocalDate date = request.startDate(); !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            if (!weekdays.contains(date.getDayOfWeek())) {
                continue;
            }
            Event event = new Event();
            applyCreate(event, request.kind(), request.title(), date, request.startTime(),
                    request.endTime(), request.location(), request.sectionId(), request.content());
            event.setCreatedBy(operator.memberId());
            event.setStatus(EventStatus.NORMAL);
            event.setBatchId(batchId);
            eventMapper.insert(event);
            created.add(event);
        }
        if (created.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期范围内没有匹配星期几的日期");
        }
        Map<Long, Section> sections = loadSections(nonNullIds(
                created.stream().map(Event::getSectionId).toArray(Long[]::new)));
        Map<Long, Member> members = loadMembers(List.of(operator.memberId()));
        return created.stream().map(e -> toView(e, sections, members)).toList();
    }

    @Transactional
    public EventView update(long id, EventUpdateRequest request, CurrentUser operator) {
        Event event = requireEvent(id);
        OpLogRecorder.putBefore(Map.of(
                "title", event.getTitle(),
                "date", event.getDate().toString(),
                "startTime", event.getStartTime().toString(),
                "endTime", event.getEndTime().toString(),
                "status", event.getStatus()));
        checkEditPermission(operator, event);

        validateTimeRange(request.startTime(), request.endTime());

        Event upd = new Event();
        upd.setId(id);
        upd.setTitle(request.title());
        upd.setDate(request.date());
        upd.setStartTime(request.startTime());
        upd.setEndTime(request.endTime());
        upd.setLocation(request.location());
        upd.setContent(request.content());
        if (request.sectionId() != null) {
            if (!EventKind.isSectionClass(event.getKind())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅声部课可指定声部");
            }
            if (sectionMapper.selectById(request.sectionId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "声部不存在");
            }
            if (operator.role() == Role.SECTION_LEADER) {
                permissionService.checkSameSection(operator, request.sectionId());
            }
            upd.setSectionId(request.sectionId());
        }
        upd.setUpdatedBy(operator.memberId());
        eventMapper.updateById(upd);
        return get(id, operator);
    }

    @Transactional
    public EventView cancel(long id, CurrentUser operator) {
        Event event = requireEvent(id);
        if (EventStatus.CANCELED.equals(event.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_DECIDED, "活动已取消");
        }
        checkEditPermission(operator, event);
        eventMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Event>()
                .eq(Event::getId, id)
                .set(Event::getStatus, EventStatus.CANCELED)
                .set(Event::getUpdatedBy, operator.memberId())
                .set(Event::getUpdatedAt, java.time.LocalDateTime.now()));
        return get(id, operator);
    }

    // ---------- 内部 ----------

    private void checkCreatePermission(CurrentUser operator, String kind, Long sectionId) {
        if (EventKind.isSectionClass(kind)) {
            permissionService.checkRole(operator, Role.ADMIN, Role.SECTION_LEADER);
            permissionService.checkSameSection(operator, sectionId);
        } else {
            permissionService.checkRole(operator, Role.ADMIN, Role.LEADER, Role.CONDUCTOR);
        }
    }

    /** 声部课可由声部长（本声部）/ 团长 / 管理员编辑；其余活动仅管理员/团长/指挥（docs/02）。 */
    private void checkEditPermission(CurrentUser operator, Event event) {
        if (EventKind.isSectionClass(event.getKind())) {
            permissionService.checkRole(operator, Role.ADMIN, Role.LEADER, Role.SECTION_LEADER);
            if (operator.role() == Role.SECTION_LEADER) {
                permissionService.checkSameSection(operator, event.getSectionId());
            }
        } else {
            permissionService.checkRole(operator, Role.ADMIN, Role.LEADER, Role.CONDUCTOR);
        }
    }

    /** 不变式 5：section_id 非空 当且仅当 kind = SECTION_CLASS。 */
    private void validateKindAndSection(String kind, Long sectionId) {
        boolean knownKind = EventKind.SECTION_CLASS.equals(kind) || EventKind.REHEARSAL.equals(kind)
                || EventKind.DRESS_REHEARSAL.equals(kind);
        if (!knownKind) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知活动类型：" + kind);
        }
        if (EventKind.isSectionClass(kind) != (sectionId != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    EventKind.isSectionClass(kind) ? "声部课必须指定声部" : "只有声部课可指定声部");
        }
    }

    private void applyCreate(Event event, String kind, String title, LocalDate date,
                             java.time.LocalTime startTime, java.time.LocalTime endTime,
                             String location, Long sectionId, String content) {
        event.setKind(kind);
        event.setTitle(title);
        event.setDate(date);
        event.setStartTime(startTime);
        event.setEndTime(endTime);
        event.setLocation(location);
        event.setSectionId(sectionId);
        event.setContent(content);
    }

    /** 团员只能看到非声部课 + 本声部声部课；签到等场景复用此判定。 */
    public void ensureVisible(Event event, CurrentUser viewer) {
        if (viewer.role() != Role.MEMBER) {
            return;
        }
        boolean ownSectionClass = EventKind.isSectionClass(event.getKind())
                && viewer.sectionId() != null
                && viewer.sectionId().equals(event.getSectionId());
        if (EventKind.isSectionClass(event.getKind()) && !ownSectionClass) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private Event requireEvent(long id) {
        Event event = eventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return event;
    }

    private EventView toView(Event event, Map<Long, Section> sections, Map<Long, Member> members) {
        Section section = event.getSectionId() == null ? null : sections.get(event.getSectionId());
        Member creator = members.get(event.getCreatedBy());
        Member updater = event.getUpdatedBy() == null ? null : members.get(event.getUpdatedBy());
        return EventView.of(event,
                section == null ? null : section.getName(),
                creator == null ? null : creator.getName(),
                updater == null ? null : updater.getName());
    }

    private List<Long> nonNullIds(Long... ids) {
        return Arrays.stream(ids).filter(java.util.Objects::nonNull).toList();
    }

    private Map<Long, Section> loadSections(List<Long> sectionIds) {
        List<Long> distinct = sectionIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Function.identity()));
    }

    private Map<Long, Member> loadMembers(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
    }
}
