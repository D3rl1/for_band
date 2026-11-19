package com.forband.sectionnote;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberRoleHistory;
import com.forband.member.MemberRoleHistoryMapper;
import com.forband.sectionnote.dto.SectionNoteDtos.NoteView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 上任声部长的留言（docs/03 §3.5 / docs/13 §13.3㉕）。
 * <p>
 * ⭐ **四级权限全部在这里收口**（控制器只负责"登录"这一层）：
 * <ul>
 *   <li><b>读</b> = 该声部的**历任与现任**声部长（**含已卸任** —— 判据是 `member_role_history` 里
 *       "有任意一条 `SECTION_LEADER`"，`toDate` 有值也算）。普通团员看不到。</li>
 *   <li><b>写</b> = 该声部**此刻**的声部长（`toDate` 为空）。</li>
 *   <li><b>改</b> = **仅作者本人** —— 与"现在还是不是声部长"无关（卸任后仍是那条的作者）。</li>
 *   <li><b>删</b> = **谁都不能**（留痕性质）⇒ 本类**没有任何删除方法**，接口也不提供。</li>
 * </ul>
 * ⚠️ 「读」含已卸任这条最容易写成"只看在任"，是本功能的核心口径，别顺手收窄。
 */
@Service
public class SectionNoteService {

    private final SectionNoteMapper noteMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final MemberMapper memberMapper;

    public SectionNoteService(SectionNoteMapper noteMapper, MemberRoleHistoryMapper historyMapper,
                              MemberMapper memberMapper) {
        this.noteMapper = noteMapper;
        this.historyMapper = historyMapper;
        this.memberMapper = memberMapper;
    }

    /** 该声部的留言，按 `created_at` **正序**（历届叠着看，越早的越在上面）。 */
    public PageResult<NoteView> page(long sectionId, int page, int size, CurrentUser user) {
        requireReader(user, sectionId);
        Page<SectionNote> result = noteMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<SectionNote>()
                        .eq(SectionNote::getSectionId, sectionId)
                        .orderByAsc(SectionNote::getCreatedAt)
                        .orderByAsc(SectionNote::getId));
        Map<Long, String> names = namesOf(result.getRecords().stream()
                .map(SectionNote::getAuthorId).filter(Objects::nonNull).distinct().toList());
        List<NoteView> views = result.getRecords().stream()
                .map(row -> toView(row, names, user.memberId())).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    @Transactional
    public NoteView create(long sectionId, String body, CurrentUser user) {
        requireCurrentLeader(user, sectionId);
        SectionNote row = new SectionNote();
        row.setSectionId(sectionId);
        row.setAuthorId(user.memberId());
        row.setBody(body);
        noteMapper.insert(row);
        return toView(row, Map.of(user.memberId(), user.name()), user.memberId());
    }

    @Transactional
    public NoteView update(long sectionId, long noteId, String body, CurrentUser user) {
        SectionNote row = noteMapper.selectById(noteId);
        if (row == null || !Objects.equals(row.getSectionId(), sectionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "这条留言不存在");
        }
        // 改 = **仅作者本人**（卸任之后仍然是作者 ⇒ 这条判据与"现在是不是声部长"无关）
        if (!Objects.equals(row.getAuthorId(), user.memberId())) {
            throw new BusinessException(ErrorCode.NOT_SELF);
        }
        row.setBody(body);
        noteMapper.updateById(row);          // updated_at 由 MetaObjectHandler 填
        return toView(row, namesOf(List.of(row.getAuthorId())), user.memberId());
    }

    /** 「我刚上任、还没读过上一任的留言」那一条（docs/03 §3.5）。
     *  判据：① 我此刻是该声部的声部长；② 该声部**`created_at` 最新且 `author_id ≠ 我`** 的那条存在。
     *  "我读没读过"由前端记（`localStorage.band_seen_section_note`），**后端只负责返回这一条**。
     *  ⇒ 不满足就 `null`（前端据此"不弹"）。 */
    public NoteView pending(CurrentUser user) {
        Long sectionId = user.sectionId();
        if (sectionId == null || !leaderNow(user.memberId(), sectionId)) {
            return null;
        }
        List<SectionNote> rows = noteMapper.selectPage(new Page<>(1, 1, false),
                new LambdaQueryWrapper<SectionNote>()
                        .eq(SectionNote::getSectionId, sectionId)
                        .ne(SectionNote::getAuthorId, user.memberId())
                        .orderByDesc(SectionNote::getCreatedAt)
                        .orderByDesc(SectionNote::getId)).getRecords();
        if (rows.isEmpty()) {
            return null;
        }
        SectionNote row = rows.get(0);
        return toView(row, namesOf(List.of(row.getAuthorId())), user.memberId());
    }

    // ---------- 权限判据（全在这一处，别散出去） ----------

    private void requireReader(CurrentUser user, long sectionId) {
        if (!everLeader(user.memberId(), sectionId)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "只有历任与现任声部长能看到这些留言");
        }
    }

    private void requireCurrentLeader(CurrentUser user, long sectionId) {
        if (!leaderNow(user.memberId(), sectionId)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "只有该声部此刻的声部长能留言");
        }
    }

    /** 当过该声部的声部长吗——**含已卸任**（`toDate` 有值也算）。"读"的判据。 */
    private boolean everLeader(long memberId, long sectionId) {
        Long n = historyMapper.selectCount(new LambdaQueryWrapper<MemberRoleHistory>()
                .eq(MemberRoleHistory::getMemberId, memberId)
                .eq(MemberRoleHistory::getSectionId, sectionId)
                .eq(MemberRoleHistory::getRole, Role.SECTION_LEADER.name()));
        return n != null && n > 0;
    }

    /** 此刻在任吗（`to_date` 为空）。"写"与"要不要弹提示"的判据。 */
    private boolean leaderNow(long memberId, long sectionId) {
        Long n = historyMapper.selectCount(new LambdaQueryWrapper<MemberRoleHistory>()
                .eq(MemberRoleHistory::getMemberId, memberId)
                .eq(MemberRoleHistory::getSectionId, sectionId)
                .eq(MemberRoleHistory::getRole, Role.SECTION_LEADER.name())
                .isNull(MemberRoleHistory::getToDate));
        return n != null && n > 0;
    }

    // ---------- 组装 ----------

    private NoteView toView(SectionNote row, Map<Long, String> names, long meId) {
        return new NoteView(row.getId(), row.getBody(), row.getAuthorId(),
                names.get(row.getAuthorId()), row.getCreatedAt(), row.getUpdatedAt(),
                Objects.equals(row.getAuthorId(), meId));
    }

    private Map<Long, String> namesOf(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectBatchIds(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
    }
}
