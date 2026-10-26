package com.forband.issue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.issue.dto.IssueDtos.ActivityView;
import com.forband.issue.dto.IssueDtos.CommentRequest;
import com.forband.issue.dto.IssueDtos.CreateRequest;
import com.forband.issue.dto.IssueDtos.IssueCommentView;
import com.forband.issue.dto.IssueDtos.IssueDetail;
import com.forband.issue.dto.IssueDtos.IssueListItem;
import com.forband.issue.dto.IssueDtos.ReactionView;
import com.forband.issue.dto.IssueDtos.StatusRequest;
import com.forband.issue.dto.IssueDtos.UpdateRequest;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 问题讨论公开栏（docs/15 §15.3–§15.4）。用户内容只做纯文本存取，不解析 HTML / Markdown。 */
@Service
public class IssueService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IssueMapper issueMapper;
    private final IssueCommentMapper commentMapper;
    private final IssueReactionMapper reactionMapper;
    private final MemberMapper memberMapper;
    private final PermissionService permissionService;

    public IssueService(IssueMapper issueMapper, IssueCommentMapper commentMapper,
                        IssueReactionMapper reactionMapper, MemberMapper memberMapper,
                        PermissionService permissionService) {
        this.issueMapper = issueMapper;
        this.commentMapper = commentMapper;
        this.reactionMapper = reactionMapper;
        this.memberMapper = memberMapper;
        this.permissionService = permissionService;
    }

    public PageResult<IssueListItem> list(String status, boolean mine, int page, int size,
                                          CurrentUser me) {
        String filter = null;
        if (status != null && !status.isBlank()) {
            IssueStatus parsed = IssueStatus.parse(status);
            if (parsed == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "状态筛选只能是 PENDING / PROCESSING / FIXED / REJECTED");
            }
            filter = parsed.name();
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<Issue> result = issueMapper.selectPage(Page.of(normalizedPage, normalizedSize),
                new LambdaQueryWrapper<Issue>()
                        .eq(filter != null, Issue::getStatus, filter)
                        .eq(mine, Issue::getMemberId, me.memberId())
                        .orderByDesc(Issue::getUpdatedAt)
                        .orderByDesc(Issue::getId));

        List<Issue> rows = result.getRecords();
        List<Long> ids = rows.stream().map(Issue::getId).toList();
        Map<Long, Long> commentCounts = countMap(ids.isEmpty() ? List.of()
                : commentMapper.countUserByIssueIds(ids));
        Map<Long, Long> reactionCounts = countMap(ids.isEmpty() ? List.of()
                : reactionMapper.countsByIssueIds(ids));
        Map<Long, Member> members = membersById(rows.stream().map(Issue::getMemberId).toList());

        List<IssueListItem> views = rows.stream().map(row -> {
            Member author = members.get(row.getMemberId());
            return new IssueListItem(row.getId(), row.getTitle(), row.getMemberId(),
                    author == null ? null : author.getName(), row.getStatus(),
                    commentCounts.getOrDefault(row.getId(), 0L),
                    reactionCounts.getOrDefault(row.getId(), 0L),
                    row.getUpdatedAt(), row.getCreatedAt());
        }).toList();
        return PageResult.of(views, result.getTotal(), normalizedPage, normalizedSize);
    }

    public IssueDetail detail(long id, CurrentUser me) {
        Issue row = requireIssue(id);
        List<IssueComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<IssueComment>()
                        .eq(IssueComment::getIssueId, id)
                        .orderByAsc(IssueComment::getCreatedAt)
                        .orderByAsc(IssueComment::getId));

        List<Long> memberIds = new ArrayList<>();
        memberIds.add(row.getMemberId());
        memberIds.add(row.getStatusChangedBy());
        comments.forEach(c -> memberIds.add(c.getMemberId()));
        Map<Long, Member> members = membersById(memberIds);

        boolean issueEditable = canEditIssue(row, me);
        List<IssueCommentView> commentViews = comments.stream()
                .map(c -> toCommentView(c, members, me))
                .toList();
        long commentCount = comments.stream()
                .filter(c -> IssueComment.KIND_USER.equals(c.getKind()))
                .count();
        long reactionCount = reactionMapper.countByIssue(id);
        boolean reactedByMe = reactionMapper.countByIssueAndMember(id, me.memberId()) > 0;
        Member author = members.get(row.getMemberId());
        Member statusChanger = members.get(row.getStatusChangedBy());

        return new IssueDetail(row.getId(), row.getTitle(), row.getContent(), row.getMemberId(),
                author == null ? null : author.getName(), row.getStatus(),
                row.getDeviceInfo(), row.getPagePath(),
                statusChanger == null ? null : statusChanger.getName(),
                row.getStatusChangedAt(), row.getEditedAt(), row.getCreatedAt(), row.getUpdatedAt(),
                commentCount, reactionCount, reactedByMe, issueEditable, commentViews);
    }

    @Transactional
    public long create(CreateRequest request, String userAgent, CurrentUser me) {
        String title = required(request.title(), 50, "标题");
        String content = required(request.content(), 2000, "正文");
        String deviceInfo = required(request.deviceInfo(), 100, "运行环境");
        String pagePath = optional(request.pagePath(), 255, "页面路径");

        Issue row = new Issue();
        row.setMemberId(me.memberId());
        row.setTitle(title);
        row.setContent(content);
        row.setDeviceInfo(deviceInfo);
        row.setPagePath(pagePath);
        row.setUserAgent(clip(userAgent, 255));
        row.setStatus(IssueStatus.PENDING.name());
        issueMapper.insert(row);
        return row.getId();
    }

    /** 作者编辑：只写 edited_at，**不碰 updated_at**（docs/15 §15.4）。 */
    public void update(long id, UpdateRequest request, CurrentUser me) {
        Issue row = requireIssue(id);
        permissionService.checkSelf(me, row.getMemberId());
        String title = required(request.title(), 50, "标题");
        String content = required(request.content(), 2000, "正文");
        String deviceInfo = required(request.deviceInfo(), 100, "运行环境");

        issueMapper.update(null, new LambdaUpdateWrapper<Issue>()
                .eq(Issue::getId, id)
                .set(Issue::getTitle, title)
                .set(Issue::getContent, content)
                .set(Issue::getDeviceInfo, deviceInfo)
                .set(Issue::getEditedAt, LocalDateTime.now()));
    }

    @Transactional
    public long comment(long id, CommentRequest request, CurrentUser me) {
        requireIssue(id);
        String content = required(request.content(), 1000, "回帖");
        LocalDateTime now = LocalDateTime.now();

        IssueComment row = new IssueComment();
        row.setIssueId(id);
        row.setMemberId(me.memberId());
        row.setKind(IssueComment.KIND_USER);
        row.setContent(content);
        row.setCreatedAt(now);
        commentMapper.insert(row);

        issueMapper.update(null, new LambdaUpdateWrapper<Issue>()
                .eq(Issue::getId, id)
                .set(Issue::getUpdatedAt, now));
        return row.getId();
    }

    /** 改自己的 USER 回帖：系统行不可编辑；不更新 issue.updated_at。 */
    public void updateComment(long issueId, long commentId, CommentRequest request, CurrentUser me) {
        IssueComment row = commentMapper.selectById(commentId);
        if (row == null || !Objects.equals(row.getIssueId(), issueId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "回帖不存在");
        }
        if (IssueComment.KIND_SYSTEM.equals(row.getKind())) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "系统回帖不可编辑");
        }
        if (row.getMemberId() == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "系统回帖不可编辑");
        }
        permissionService.checkSelf(me, row.getMemberId());
        String content = required(request.content(), 1000, "回帖");

        commentMapper.update(null, new LambdaUpdateWrapper<IssueComment>()
                .eq(IssueComment::getId, commentId)
                .set(IssueComment::getContent, content)
                .set(IssueComment::getEditedAt, LocalDateTime.now()));
    }

    /**
     * 改状态：仅 ADMIN（控制器方法级注解）。同状态 = 成功空操作；
     * 真正的变更在同一事务里更新主表并写一条 SYSTEM 回帖（docs/13 不变式 18）。
     */
    @Transactional
    public void changeStatus(long id, StatusRequest request, CurrentUser me) {
        Issue row = requireIssue(id);
        IssueStatus target = IssueStatus.parse(request.status());
        if (target == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "状态只能是 PENDING / PROCESSING / FIXED / REJECTED");
        }
        if (target.name().equals(row.getStatus())) {
            return;
        }
        String reason = optional(request.reason(), 255, "理由");
        if (target == IssueStatus.REJECTED && reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不处理时必须填写理由");
        }
        String content = reason == null ? "状态改为「" + target.label() + "」" : reason;
        LocalDateTime now = LocalDateTime.now();

        issueMapper.update(null, new LambdaUpdateWrapper<Issue>()
                .eq(Issue::getId, id)
                .set(Issue::getStatus, target.name())
                .set(Issue::getStatusChangedBy, me.memberId())
                .set(Issue::getStatusChangedAt, now)
                .set(Issue::getUpdatedAt, now));

        IssueComment system = new IssueComment();
        system.setIssueId(id);
        system.setMemberId(null);
        system.setKind(IssueComment.KIND_SYSTEM);
        system.setContent(content);
        system.setOldStatus(row.getStatus());
        system.setNewStatus(target.name());
        system.setCreatedAt(now);
        commentMapper.insert(system);
    }

    /** 作者点自己的 +1 = 成功空操作、不落行；重复点由主键保证幂等。 */
    public ReactionView react(long id, CurrentUser me) {
        Issue row = requireIssue(id);
        if (!Objects.equals(row.getMemberId(), me.memberId())) {
            reactionMapper.insertIgnore(id, me.memberId(), LocalDateTime.now());
        }
        return reactionView(id, me);
    }

    public ReactionView unreact(long id, CurrentUser me) {
        requireIssue(id);
        reactionMapper.delete(id, me.memberId());
        return reactionView(id, me);
    }

    public ActivityView activity() {
        return new ActivityView(issueMapper.latestUpdatedAt());
    }

    // ---------- 内部 ----------

    private Issue requireIssue(long id) {
        Issue row = issueMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问题不存在");
        }
        return row;
    }

    private ReactionView reactionView(long id, CurrentUser me) {
        return new ReactionView(reactionMapper.countByIssue(id),
                reactionMapper.countByIssueAndMember(id, me.memberId()) > 0);
    }

    private boolean canEditIssue(Issue row, CurrentUser me) {
        return me.role() != Role.HONORARY && Objects.equals(row.getMemberId(), me.memberId());
    }

    private boolean canEditComment(IssueComment row, CurrentUser me) {
        return me.role() != Role.HONORARY
                && IssueComment.KIND_USER.equals(row.getKind())
                && Objects.equals(row.getMemberId(), me.memberId());
    }

    private IssueCommentView toCommentView(IssueComment row, Map<Long, Member> members,
                                           CurrentUser me) {
        Member member = members.get(row.getMemberId());
        return new IssueCommentView(row.getId(), row.getKind(), row.getMemberId(),
                member == null ? null : member.getName(), row.getContent(),
                row.getOldStatus(), row.getNewStatus(), row.getCreatedAt(), row.getEditedAt(),
                canEditComment(row, me));
    }

    private Map<Long, Member> membersById(Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Member::getId, m -> m));
    }

    private Map<Long, Long> countMap(List<IssueCount> counts) {
        return counts.stream().collect(Collectors.toMap(IssueCount::getIssueId,
                IssueCount::getTotal, (a, b) -> a));
    }

    private String required(String raw, int max, String label) {
        String value = raw == null ? "" : raw.trim();
        int length = value.codePointCount(0, value.length());
        if (length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        if (length > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "最多 " + max + " 字");
        }
        return value;
    }

    private String optional(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.codePointCount(0, value.length()) > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "最多 " + max + " 字");
        }
        return value;
    }

    /** 请求头里的 UA 只用于排查，截到列宽即可；不校验、不解析、不下发。 */
    private String clip(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
