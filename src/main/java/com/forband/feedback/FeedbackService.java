package com.forband.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.UserAgentLabel;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.feedback.dto.FeedbackDtos.CreateRequest;
import com.forband.feedback.dto.FeedbackDtos.FeedbackView;
import com.forband.feedback.dto.FeedbackDtos.HandleRequest;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 反馈（docs/15 §15.4）。没有任何频率限制、不做匿名、不做"回复团员"——40 人内部工具。 */
@Service
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;

    public FeedbackService(FeedbackMapper feedbackMapper, MemberMapper memberMapper,
                           SectionMapper sectionMapper) {
        this.feedbackMapper = feedbackMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
    }

    @Transactional
    public void create(CreateRequest request, String userAgent, CurrentUser submitter) {
        Feedback row = new Feedback();
        row.setMemberId(submitter.memberId());
        row.setContent(request.content());
        row.setPagePath(request.pagePath());
        row.setUserAgent(userAgent);
        row.setStatus(FeedbackStatus.OPEN);
        feedbackMapper.insert(row);
    }

    /** 管理端列表：默认只看 `OPEN`（已处理的不再堆在默认视图里，docs/15 §15.4）。 */
    public PageResult<FeedbackView> page(String status, int page, int size) {
        String filter = status == null || status.isBlank() ? FeedbackStatus.OPEN : status;
        Page<Feedback> result = feedbackMapper.selectPage(Page.of(page, size),
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
                    f.getHandledAt(), f.getAdminNote(), f.getCreatedAt());
        }).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    /**
     * 标记已处理 + 备注。`handled_by` / `handled_at` **本身就是留痕**，
     * 所以这里**不写操作日志**（docs/11 §11.3 明写"处理反馈"不进清单，进了是同一件事记两遍）。
     */
    @Transactional
    public void handle(long id, HandleRequest request, CurrentUser operator) {
        if (request.status() != null && !FeedbackStatus.OPEN.equals(request.status())
                && !FeedbackStatus.HANDLED.equals(request.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "状态只能为 OPEN / HANDLED");
        }
        Feedback row = feedbackMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "反馈不存在");
        }
        String status = request.status() == null ? FeedbackStatus.HANDLED : request.status();
        row.setStatus(status);
        row.setAdminNote(request.adminNote());
        if (FeedbackStatus.HANDLED.equals(status)) {
            row.setHandledBy(operator.memberId());
            row.setHandledAt(LocalDateTime.now());
        } else {
            row.setHandledBy(null);   // 重新打开：把处理痕迹一并撤掉
            row.setHandledAt(null);
        }
        feedbackMapper.updateById(row);
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
