package com.forband.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.UserAgentLabel;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.feedback.dto.FeedbackDtos.FeedbackView;
import com.forband.issue.Issue;
import com.forband.issue.IssueMapper;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 历史反馈只读归档（docs/15 §15.3.2）。
 *
 * <p>旧的提交 / 处理写入口已随 #227 退役；这里只保留管理端查询。
 * `MIGRATED` 行额外带 `migratedIssueId`，让归档页能跳到公开的问题讨论。
 */
@Service
public class FeedbackService {

    private static final Set<String> STATUSES = Set.of(
            FeedbackStatus.OPEN, FeedbackStatus.HANDLED, FeedbackStatus.MIGRATED);

    private final FeedbackMapper feedbackMapper;
    private final IssueMapper issueMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;

    public FeedbackService(FeedbackMapper feedbackMapper, IssueMapper issueMapper,
                           MemberMapper memberMapper, SectionMapper sectionMapper) {
        this.feedbackMapper = feedbackMapper;
        this.issueMapper = issueMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
    }

    /** 管理端归档列表：默认只看 `OPEN`；`?status=` 支持 OPEN / HANDLED / MIGRATED。 */
    public PageResult<FeedbackView> page(String status, int page, int size) {
        String filter = status == null || status.isBlank() ? FeedbackStatus.OPEN : status;
        if (!STATUSES.contains(filter)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "状态只能是 OPEN / HANDLED / MIGRATED");
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = size <= 0 ? 20 : Math.min(size, 100);
        Page<Feedback> result = feedbackMapper.selectPage(Page.of(normalizedPage, normalizedSize),
                new LambdaQueryWrapper<Feedback>()
                        .eq(Feedback::getStatus, filter)
                        .orderByDesc(Feedback::getId));

        List<Long> memberIds = result.getRecords().stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getMemberId(), f.getHandledBy()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Member> members = memberIds.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(memberIds).stream()
                        .collect(Collectors.toMap(Member::getId, m -> m));
        Map<Long, String> sectionNames = sectionNamesOf(members.values().stream()
                .map(Member::getSectionId).toList());
        Map<Long, Long> migratedIssueIds = migratedIssueIds(result.getRecords());

        List<FeedbackView> views = result.getRecords().stream().map(f -> {
            Member submitter = members.get(f.getMemberId());
            Member handler = members.get(f.getHandledBy());
            return new FeedbackView(f.getId(), f.getMemberId(),
                    submitter == null ? null : submitter.getName(),
                    submitter == null || submitter.getSectionId() == null ? null
                            : sectionNames.get(submitter.getSectionId()),
                    f.getContent(), f.getPagePath(), UserAgentLabel.parse(f.getUserAgent()),
                    f.getStatus(), f.getHandledBy(),
                    handler == null ? null : handler.getName(),
                    f.getHandledAt(), f.getAdminNote(), f.getCreatedAt(),
                    migratedIssueIds.get(f.getId()));
        }).toList();
        return PageResult.of(views, result.getTotal(), normalizedPage, normalizedSize);
    }

    /** feedback.id → issue.id；只查本页里 MIGRATED 的行。 */
    private Map<Long, Long> migratedIssueIds(List<Feedback> rows) {
        List<Long> ids = rows.stream()
                .filter(f -> FeedbackStatus.MIGRATED.equals(f.getStatus()))
                .map(Feedback::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return issueMapper.selectList(new LambdaQueryWrapper<Issue>()
                        .select(Issue::getId, Issue::getSourceFeedbackId)
                        .in(Issue::getSourceFeedbackId, ids))
                .stream()
                .collect(Collectors.toMap(Issue::getSourceFeedbackId, Issue::getId, (a, b) -> a));
    }

    private Map<Long, String> sectionNamesOf(List<Long> sectionIds) {
        List<Long> distinct = sectionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Section::getName, (a, b) -> a));
    }
}
