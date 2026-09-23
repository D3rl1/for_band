package com.forband.releasenote;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.common.PageResult;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.releasenote.dto.ReleaseNoteDtos.CreateRequest;
import com.forband.releasenote.dto.ReleaseNoteDtos.ReleaseNoteView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 更新公告（docs/15 §15.5）。看：所有登录角色（含荣誉团员，有意例外）；发布：仅管理员。 */
@Service
public class ReleaseNoteService {

    private final ReleaseNoteMapper releaseNoteMapper;
    private final MemberMapper memberMapper;

    public ReleaseNoteService(ReleaseNoteMapper releaseNoteMapper, MemberMapper memberMapper) {
        this.releaseNoteMapper = releaseNoteMapper;
        this.memberMapper = memberMapper;
    }

    /** 最新一条（给弹层用）。**一条都没有时返回 null**——前端据此"不弹、不占位、不出空层"。
     *  取 1 条走分页插件而不是拼 `.last("LIMIT 1")`（`SECURITY.md` C1 的字面口径，见 §20）。 */
    public ReleaseNoteView latest() {
        List<ReleaseNote> rows = releaseNoteMapper.selectPage(new Page<>(1, 1, false),
                new LambdaQueryWrapper<ReleaseNote>().orderByDesc(ReleaseNote::getId)).getRecords();
        return rows.isEmpty() ? null : toView(rows.get(0), namesOf(rows.get(0)));
    }

    /** 公告历史：分页、`id` 倒序（发布即写当前时间，与 published_at 倒序一致，docs/15 §15.3）。 */
    public PageResult<ReleaseNoteView> page(int page, int size) {
        Page<ReleaseNote> result = releaseNoteMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<ReleaseNote>().orderByDesc(ReleaseNote::getId));
        Map<Long, String> names = namesOf(result.getRecords().stream()
                .map(ReleaseNote::getPublishedBy).filter(Objects::nonNull).distinct().toList());
        List<ReleaseNoteView> views = result.getRecords().stream()
                .map(row -> toView(row, names)).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    @Transactional
    public ReleaseNoteView publish(CreateRequest request, CurrentUser publisher) {
        ReleaseNote row = new ReleaseNote();
        row.setTitle(request.title());
        row.setContent(request.content());
        row.setPublishedAt(LocalDateTime.now());   // 业务时间：显式赋值，不靠自动填充
        row.setPublishedBy(publisher.memberId());
        releaseNoteMapper.insert(row);
        return toView(row, Map.of(publisher.memberId(), publisher.name()));
    }

    private ReleaseNoteView toView(ReleaseNote row, Map<Long, String> names) {
        return new ReleaseNoteView(row.getId(), row.getTitle(), row.getContent(),
                row.getPublishedAt(), row.getPublishedBy(), names.get(row.getPublishedBy()));
    }

    private Map<Long, String> namesOf(ReleaseNote row) {
        return namesOf(row.getPublishedBy() == null ? List.of() : List.of(row.getPublishedBy()));
    }

    private Map<Long, String> namesOf(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
    }
}
