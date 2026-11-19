package com.forband.recording;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberStatus;
import com.forband.piece.Piece;
import com.forband.piece.PieceMapper;
import com.forband.recording.dto.RecordingAssignmentDtos.AssignmentView;
import com.forband.recording.dto.RecordingAssignmentDtos.CreateAssignmentRequest;
import com.forband.recording.dto.RecordingAssignmentDtos.CurrentAssignmentView;
import com.forband.recording.dto.RecordingAssignmentDtos.ManageMemberView;
import com.forband.recording.dto.RecordingAssignmentDtos.ManageSectionView;
import com.forband.recording.dto.RecordingAssignmentDtos.ManageView;
import com.forband.recording.dto.RecordingAssignmentDtos.UpdateAssignmentRequest;
import com.forband.recording.dto.RecordingAssignmentDtos.WeekView;
import com.forband.recording.dto.RecordingDtos.RecordingRow;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本周录音作业与录音管理视图（docs/08 §8.3–§8.4）。
 * <p>
 * **周界一律按 Asia/Shanghai 的「周一 00:00」**：录音归属看 `uploaded_at`，不看 `created_at`；
 * 作业归属看 `recording_assignment.week_start`。两者归一化口径一致（{@link #weekStartOf}），
 * 否则前端出现"作业是本周的、录音算到上周"。
 * <p>
 * **不存完成度**：三态在查询时现算——一人本周的录音按 {@code assignment_id} 去重，
 * 与本声部本周作业取交集。冗余状态会被换声部、删录音、改作业弄脏。
 */
@Service
public class RecordingAssignmentService {

    /** 周界时区：全班作息以北京时间为准，服务器时区不参与业务判断。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    static final String MISS = "MISS";
    static final String PARTIAL = "PARTIAL";
    static final String DONE = "DONE";

    private final RecordingAssignmentMapper assignmentMapper;
    private final RecordingMapper recordingMapper;
    private final RecordingCommentMapper commentMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final PieceMapper pieceMapper;
    private final PermissionService permissionService;

    public RecordingAssignmentService(RecordingAssignmentMapper assignmentMapper,
                                      RecordingMapper recordingMapper,
                                      RecordingCommentMapper commentMapper,
                                      MemberMapper memberMapper,
                                      SectionMapper sectionMapper,
                                      PieceMapper pieceMapper,
                                      PermissionService permissionService) {
        this.assignmentMapper = assignmentMapper;
        this.recordingMapper = recordingMapper;
        this.commentMapper = commentMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.pieceMapper = pieceMapper;
        this.permissionService = permissionService;
    }

    public static LocalDate currentWeekStart() {
        return weekStartOf(LocalDate.now(ZONE));
    }

    public static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ------------------------------------------------------------------ 管理视图

    /**
     * 录音管理视图。声部长**强制**只看本声部（不是静默改写，而是越权即拒）。
     * <p>
     * 当前周：有作业的声部携带**全部应录成员**（未交者 `recordings=[]`），前端靠它生成未交名单；
     * 没作业但有录音的声部只携带录音者。历史周：不带作业/完成度，只返回当周有录音的人。
     */
    public ManageView manage(String weekStartRaw, CurrentUser viewer) {
        LocalDate weekStart = parseWeek(weekStartRaw, currentWeekStart());
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean current = weekStart.equals(currentWeekStart());
        Long scope = scopeSection(viewer);

        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = weekStart.plusDays(7).atStartOfDay();
        List<Recording> recordings = recordingMapper.selectList(new LambdaQueryWrapper<Recording>()
                .ge(Recording::getUploadedAt, from)
                .lt(Recording::getUploadedAt, to)
                .eq(scope != null, Recording::getSectionId, scope)
                .orderByDesc(Recording::getUploadedAt)
                .orderByDesc(Recording::getId));

        List<RecordingAssignment> weekAssignments = current
                ? assignmentMapper.selectList(new LambdaQueryWrapper<RecordingAssignment>()
                        .eq(RecordingAssignment::getWeekStart, weekStart)
                        .eq(scope != null, RecordingAssignment::getSectionId, scope)
                        .orderByAsc(RecordingAssignment::getId))
                : List.of();
        List<Member> expected = current ? expectedMembers(weekStart, scope) : List.of();

        // ---- 一次性批量取齐关联对象，避免逐行查（管理页一屏几十条） ----
        Set<Long> memberIds = new HashSet<>();
        Set<Long> sectionIds = new LinkedHashSet<>();
        Set<Long> pieceIds = new HashSet<>();
        Set<Long> assignmentIds = new HashSet<>();
        recordings.forEach(r -> {
            memberIds.add(r.getMemberId());
            if (r.getSectionId() != null) {
                sectionIds.add(r.getSectionId());
            }
            if (r.getPieceId() != null) {
                pieceIds.add(r.getPieceId());
            }
            if (r.getAssignmentId() != null) {
                assignmentIds.add(r.getAssignmentId());
            }
        });
        expected.forEach(m -> memberIds.add(m.getId()));
        weekAssignments.forEach(a -> {
            sectionIds.add(a.getSectionId());
            if (a.getPieceId() != null) {
                pieceIds.add(a.getPieceId());
            }
            assignmentIds.add(a.getId());
        });

        Map<Long, Member> members = membersOf(memberIds);
        Map<Long, Section> sections = sectionsOf(sectionIds);
        Map<Long, Piece> pieces = piecesOf(pieceIds);
        Map<Long, RecordingAssignment> assignments = assignmentsOf(assignmentIds);
        Map<Long, Integer> commentCounts = commentCountsOf(recordings);

        List<AssignmentView> assignmentViews = current ? assignmentViews(weekAssignments, sections, pieces)
                : List.of();

        Map<Long, List<RecordingAssignment>> assignmentsBySection = weekAssignments.stream()
                .collect(Collectors.groupingBy(RecordingAssignment::getSectionId));
        Map<Long, List<Member>> expectedBySection = expected.stream()
                .filter(m -> m.getSectionId() != null)
                .collect(Collectors.groupingBy(Member::getSectionId));
        Map<Long, List<Recording>> recordingsBySection = recordings.stream()
                .filter(r -> r.getSectionId() != null)
                .collect(Collectors.groupingBy(Recording::getSectionId));

        Set<Long> orderedSectionIds = new LinkedHashSet<>();
        weekAssignments.stream().map(RecordingAssignment::getSectionId)
                .sorted().forEach(orderedSectionIds::add);
        recordingsBySection.keySet().stream().sorted().forEach(orderedSectionIds::add);

        List<ManageSectionView> sectionViews = new ArrayList<>();
        for (Long sectionId : orderedSectionIds) {
            Section section = sections.get(sectionId);
            List<Recording> sectionRecordings = recordingsBySection.getOrDefault(sectionId, List.of());
            Set<Long> sectionAssignmentIds = assignmentsBySection.getOrDefault(sectionId, List.of())
                    .stream().map(RecordingAssignment::getId).collect(Collectors.toSet());
            // 只有「本周有作业」的声部才带全部应录成员；没作业的声部只列录音者。
            List<Member> sectionExpected = current && !sectionAssignmentIds.isEmpty()
                    ? expectedBySection.getOrDefault(sectionId, List.of())
                    : List.of();
            sectionViews.add(buildSection(sectionId, section, sectionRecordings, sectionExpected,
                    sectionAssignmentIds, current, members, sections, pieces, assignments, commentCounts));
        }
        // 指挥 / 无固定声部者上传的录音（section_id 为空）：不参与三态，但别让它们在管理页消失。
        if (scope == null) {
            List<Recording> noSection = recordings.stream()
                    .filter(r -> r.getSectionId() == null).toList();
            if (!noSection.isEmpty()) {
                sectionViews.add(buildSection(null, null, noSection, List.of(), Set.of(), false,
                        members, sections, pieces, assignments, commentCounts));
            }
        }
        sectionViews.sort(Comparator.comparingInt(
                (ManageSectionView v) -> sectionOrder(v.sectionId(), sections)));

        return new ManageView(weekStart, weekEnd, current, assignmentViews, sectionViews);
    }

    private ManageSectionView buildSection(Long sectionId, Section section, List<Recording> recordings,
                                           List<Member> expected, Set<Long> assignmentIds,
                                           boolean current, Map<Long, Member> members,
                                           Map<Long, Section> sections, Map<Long, Piece> pieces,
                                           Map<Long, RecordingAssignment> assignments,
                                           Map<Long, Integer> commentCounts) {
        Map<Long, List<Recording>> byMember = recordings.stream()
                .collect(Collectors.groupingBy(Recording::getMemberId, LinkedHashMap::new,
                        Collectors.toList()));
        // 当前周：应录成员全部出现（未交者在未交名单里）；历史周：只出现有录音的人。
        Set<Long> memberIds = new LinkedHashSet<>();
        expected.stream().map(Member::getId).sorted().forEach(memberIds::add);
        byMember.keySet().stream().sorted().forEach(memberIds::add);

        List<ManageMemberView> rows = new ArrayList<>();
        for (Long memberId : memberIds) {
            List<Recording> mine = byMember.getOrDefault(memberId, List.of());
            List<RecordingRow> rowsOfRecordings = mine.stream()
                    .map(r -> toRow(r, members, sections, pieces, assignments, commentCounts))
                    .toList();
            boolean isExpected = expected.stream().anyMatch(m -> Objects.equals(m.getId(), memberId));
            int done = 0;
            int unlinked = (int) mine.stream().filter(r -> r.getAssignmentId() == null).count();
            if (current && isExpected) {
                Set<Long> doneIds = mine.stream().map(Recording::getAssignmentId)
                        .filter(Objects::nonNull)
                        .filter(assignmentIds::contains)
                        .collect(Collectors.toSet());
                done = doneIds.size();
            }
            int total = (current && isExpected) ? assignmentIds.size() : 0;
            String state;
            if (!current || !isExpected || total == 0) {
                state = null;
            } else if (done == 0) {
                state = MISS;
            } else if (done >= total) {
                state = DONE;
            } else {
                state = PARTIAL;
            }
            Member member = members.get(memberId);
            rows.add(new ManageMemberView(memberId,
                    member == null ? "已离团成员" : member.getName(),
                    state, done, total, isExpected ? unlinked : 0, rowsOfRecordings));
        }
        String name = section == null ? (sectionId == null ? "未分声部" : "未知声部") : section.getName();
        return new ManageSectionView(sectionId, name, rows);
    }

    private List<AssignmentView> assignmentViews(List<RecordingAssignment> rows,
                                                 Map<Long, Section> sections,
                                                 Map<Long, Piece> pieces) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> submitted = submissionCounts(rows.stream().map(RecordingAssignment::getId).toList());
        return rows.stream().map(a -> {
            Section section = sections.get(a.getSectionId());
            Piece piece = a.getPieceId() == null ? null : pieces.get(a.getPieceId());
            int n = submitted.getOrDefault(a.getId(), 0);
            return new AssignmentView(a.getId(), a.getSectionId(),
                    section == null ? null : section.getName(), a.getTitle(),
                    a.getPieceId(), piece == null ? null : piece.getTitle(), n, n == 0);
        }).toList();
    }

    // ------------------------------------------------------------------ 历史周

    public List<WeekView> weeks(String beforeRaw, Integer limit, CurrentUser viewer) {
        LocalDate before = parseWeek(beforeRaw, currentWeekStart());
        int size = limit == null ? 12 : Math.max(1, Math.min(52, limit));
        Long scope = scopeSection(viewer);
        return recordingMapper.selectWeekStats(before.atStartOfDay(), scope, size).stream()
                .map(s -> new WeekView(s.getWeekStart(), s.getWeekStart().plusDays(6),
                        s.getTotal() == null ? 0 : s.getTotal(),
                        s.getSectionCount() == null ? 0 : s.getSectionCount().intValue()))
                .toList();
    }

    // ------------------------------------------------------------------ 上传用：本周作业

    /** 上传表单下拉：本人**当前声部**本周的作业；`linked` 标记本人已关联过的（默认选未关联的）。 */
    public List<CurrentAssignmentView> currentAssignments(CurrentUser user) {
        if (user.sectionId() == null) {
            return List.of();
        }
        List<RecordingAssignment> rows = assignmentMapper.selectList(
                new LambdaQueryWrapper<RecordingAssignment>()
                        .eq(RecordingAssignment::getWeekStart, currentWeekStart())
                        .eq(RecordingAssignment::getSectionId, user.sectionId())
                        .orderByAsc(RecordingAssignment::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(RecordingAssignment::getId).toList();
        Set<Long> linked = recordingMapper.selectList(new LambdaQueryWrapper<Recording>()
                        .eq(Recording::getMemberId, user.memberId())
                        .in(Recording::getAssignmentId, ids))
                .stream().map(Recording::getAssignmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Piece> pieces = piecesOf(rows.stream().map(RecordingAssignment::getPieceId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(a -> {
            Piece piece = a.getPieceId() == null ? null : pieces.get(a.getPieceId());
            return new CurrentAssignmentView(a.getId(), a.getTitle(), a.getPieceId(),
                    piece == null ? null : piece.getTitle(), linked.contains(a.getId()));
        }).toList();
    }

    /** 上传时校验并回传作业：只能关联**本周 + 本人当前声部**的作业，否则 422。 */
    public RecordingAssignment requireUsableAssignment(Long assignmentId, CurrentUser uploader) {
        RecordingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "本周作业不存在");
        }
        if (!assignment.getWeekStart().equals(currentWeekStart())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只能关联本周的作业");
        }
        if (uploader.sectionId() == null
                || !uploader.sectionId().equals(assignment.getSectionId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只能关联本声部的作业");
        }
        return assignment;
    }

    public String titleOf(Long assignmentId) {
        if (assignmentId == null) {
            return null;
        }
        RecordingAssignment assignment = assignmentMapper.selectById(assignmentId);
        return assignment == null ? null : assignment.getTitle();
    }

    // ------------------------------------------------------------------ 布置 / 修改 / 删除

    @Transactional
    public AssignmentView create(CreateAssignmentRequest request, CurrentUser user) {
        Section section = sectionMapper.selectById(request.sectionId());
        if (section == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "声部不存在");
        }
        if (user.role() == Role.SECTION_LEADER) {
            permissionService.checkSameSection(user, request.sectionId());
        }
        Piece piece = requirePiece(request.pieceId());

        RecordingAssignment row = new RecordingAssignment();
        row.setWeekStart(currentWeekStart());
        row.setSectionId(request.sectionId());
        row.setTitle(request.title().trim());
        row.setPieceId(request.pieceId());
        row.setCreatedBy(user.memberId());
        assignmentMapper.insert(row);

        return new AssignmentView(row.getId(), section.getId(), section.getName(), row.getTitle(),
                row.getPieceId(), piece == null ? null : piece.getTitle(), 0, true);
    }

    @Transactional
    public AssignmentView update(long id, UpdateAssignmentRequest request, CurrentUser user) {
        RecordingAssignment row = requireEditable(id, user);
        int submitted = submissionCounts(List.of(id)).getOrDefault(id, 0);
        if (submitted > 0 && !Objects.equals(row.getPieceId(), request.pieceId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "已有 " + submitted + " 人提交，只能修改标题");
        }
        Piece piece = requirePiece(request.pieceId());

        row.setTitle(request.title().trim());
        row.setPieceId(request.pieceId());
        assignmentMapper.updateById(row);

        Section section = sectionMapper.selectById(row.getSectionId());
        return new AssignmentView(row.getId(), row.getSectionId(),
                section == null ? null : section.getName(), row.getTitle(), row.getPieceId(),
                piece == null ? null : piece.getTitle(), submitted, submitted == 0);
    }

    @Transactional
    public void delete(long id, CurrentUser user) {
        requireEditable(id, user);
        int submitted = submissionCounts(List.of(id)).getOrDefault(id, 0);
        if (submitted > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "已有 " + submitted + " 人提交，不能删除");
        }
        assignmentMapper.deleteById(id);
    }

    /** 作业必须是**本周**的；声部长还要过"本声部"这一关（指挥 / 团长 / 管理员不受限）。 */
    private RecordingAssignment requireEditable(long id, CurrentUser user) {
        RecordingAssignment row = assignmentMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "作业不存在");
        }
        if (!row.getWeekStart().equals(currentWeekStart())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "历史周不能修改作业");
        }
        if (user.role() == Role.SECTION_LEADER) {
            permissionService.checkSameSection(user, row.getSectionId());
        }
        return row;
    }

    // ------------------------------------------------------------------ 查询小工具

    private List<Member> expectedMembers(LocalDate weekStart, Long scope) {
        return memberMapper.selectList(new LambdaQueryWrapper<Member>()
                .eq(Member::getIsSystem, false)
                .eq(Member::getStatus, MemberStatus.ACTIVE)
                .ne(Member::getRole, Role.CONDUCTOR.name())
                .and(w -> w.isNull(Member::getJoinedAt).or().le(Member::getJoinedAt, weekStart))
                .and(w -> w.isNull(Member::getLeftAt).or().ge(Member::getLeftAt, weekStart))
                .isNotNull(Member::getSectionId)
                .eq(scope != null, Member::getSectionId, scope)
                .orderByAsc(Member::getId));
    }

    /** 声部长只看本声部；本人没有声部就无从"本声部"，直接按越权拒。 */
    private Long scopeSection(CurrentUser viewer) {
        if (viewer.role() != Role.SECTION_LEADER) {
            return null;
        }
        if (viewer.sectionId() == null) {
            throw new BusinessException(ErrorCode.NOT_YOUR_SECTION);
        }
        return viewer.sectionId();
    }

    private LocalDate parseWeek(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return weekStartOf(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期格式应为 YYYY-MM-DD");
        }
    }

    private Piece requirePiece(Long pieceId) {
        if (pieceId == null) {
            return null;
        }
        Piece piece = pieceMapper.selectById(pieceId);
        if (piece == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "曲目不存在");
        }
        return piece;
    }

    private Map<Long, Integer> submissionCounts(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        return recordingMapper.countSubmissions(assignmentIds).stream()
                .collect(Collectors.toMap(RecordingMapper.AssignmentSubmissionCount::getAssignmentId,
                        c -> c.getSubmittedCount() == null ? 0 : c.getSubmittedCount().intValue()));
    }

    private Map<Long, Member> membersOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Member::getId, m -> m));
    }

    private Map<Long, Section> sectionsOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Section::getId, s -> s));
    }

    private Map<Long, Piece> piecesOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return pieceMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Piece::getId, p -> p));
    }

    private Map<Long, RecordingAssignment> assignmentsOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return assignmentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RecordingAssignment::getId, a -> a));
    }

    private Map<Long, Integer> commentCountsOf(List<Recording> recordings) {
        List<Long> ids = recordings.stream().map(Recording::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        commentMapper.countByRecordingIds(ids).forEach(c ->
                counts.put(c.getRecordingId(), c.getTotal() == null ? 0 : c.getTotal().intValue()));
        return counts;
    }

    private RecordingRow toRow(Recording row, Map<Long, Member> members,
                               Map<Long, Section> sections, Map<Long, Piece> pieces,
                               Map<Long, RecordingAssignment> assignments,
                               Map<Long, Integer> commentCounts) {
        Member member = members.get(row.getMemberId());
        Section section = row.getSectionId() == null ? null : sections.get(row.getSectionId());
        Piece piece = row.getPieceId() == null ? null : pieces.get(row.getPieceId());
        RecordingAssignment assignment = row.getAssignmentId() == null ? null
                : assignments.get(row.getAssignmentId());
        return new RecordingRow(row.getId(), row.getMemberId(),
                member == null ? null : member.getName(),
                section == null ? null : section.getName(),
                row.getAssignmentId(), assignment == null ? null : assignment.getTitle(),
                row.getPieceId(), piece == null ? null : piece.getTitle(), row.getTitle(),
                row.getFileId(), row.getDurationSec(), row.getSizeBytes(), row.getUploadedAt(),
                commentCounts.getOrDefault(row.getId(), 0));
    }

    private static int sectionOrder(Long sectionId, Map<Long, Section> sections) {
        Section section = sectionId == null ? null : sections.get(sectionId);
        return section == null || section.getOrderNo() == null ? Integer.MAX_VALUE
                : section.getOrderNo();
    }
}
