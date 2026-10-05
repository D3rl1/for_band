package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.common.SectionLookup;
import com.forband.member.dto.MemberCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberProfileView;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.member.dto.MemberView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 团员名册 / 账号的门面。写侧（建号 / 改号 / 改密 / 踢线）委派给 {@link AccountLifecycleService}，
 * 名册排序与视图装配委派给 {@link RosterOrderer} / {@link MemberViewAssembler}，
 * CSV 导出委派给 {@link AccountCsvExporter}。
 * <p>
 * ⚠️ **事务边界留在本门面的 {@code create/update/resetPassword} 上**：注解不搬进被委派的类，
 * 否则事务范围与提交时机都会变。
 */
@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final AccountLifecycleService accountLifecycleService;
    private final AccountCsvExporter accountCsvExporter;
    private final RosterOrderer rosterOrderer;
    private final MemberViewAssembler viewAssembler;
    private final MemberLookup memberLookup;
    private final SectionLookup sectionLookup;

    public MemberService(MemberMapper memberMapper, AccountLifecycleService accountLifecycleService,
                         AccountCsvExporter accountCsvExporter, RosterOrderer rosterOrderer,
                         MemberViewAssembler viewAssembler, MemberLookup memberLookup,
                         SectionLookup sectionLookup) {
        this.memberMapper = memberMapper;
        this.accountLifecycleService = accountLifecycleService;
        this.accountCsvExporter = accountCsvExporter;
        this.rosterOrderer = rosterOrderer;
        this.viewAssembler = viewAssembler;
        this.memberLookup = memberLookup;
        this.sectionLookup = sectionLookup;
    }

    // ---------- 管理员操作 ----------

    @Transactional
    public MemberCreateResponse create(MemberCreateRequest request, CurrentUser operator) {
        return accountLifecycleService.create(request, operator);
    }

    @Transactional
    public MemberView update(long id, MemberUpdateRequest request, CurrentUser operator) {
        return accountLifecycleService.update(id, request, operator);
    }

    @Transactional
    public String resetPassword(long id) {
        return accountLifecycleService.resetPassword(id);
    }

    public void revokeSessions(long id) {
        accountLifecycleService.revokeSessions(id);
    }

    public String exportAccountsCsv() {
        return accountCsvExporter.export();
    }

    // ---------- 名册 ----------

    /**
     * 名册（`docs/03` §3.4，排序口径由用户 2026-09-30 拍板）。
     * <p>
     * ⚠️ **次序不在 SQL 里排**：`member` 表上没有 `section.order_no`、现实现也没有 JOIN `section`——
     * 规格的"实现注意"给了这条路：「JOIN **或先查 `section` 做映射**」。所以这里
     * **取全集 → Java 里排 → 自己切片**。副作用是好的：「排序是权威的、分页必须建立在同一个排序上」
     * 这条**天然成立**（全集排完再切页 ⇒ 第 2 页不重不漏）。名册是几十人，全量取不必担心。
     * <p>
     * ⚠️ **退团（`LEFT`）默认不显示**：只有显式给了 `status`（例如 `status=LEFT`，规格要求保留这个能力）
     * 或调用方明确要 `includeLeft=true`（管理端名册 / 演出参演名单要看到退团的人）时才带上。
     */
    public PageResult<MemberView> list(String status, Long sectionId, boolean includeLeft,
                                       int page, int size, CurrentUser viewer) {
        boolean filteredByStatus = status != null && !status.isBlank();
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .eq(Member::getIsSystem, false)                       // 系统账号不是团员（docs/13 不变式 3）
                .eq(filteredByStatus, Member::getStatus, status)
                .eq(sectionId != null, Member::getSectionId, sectionId)
                // 显式给了 status 就**以 status 为准**；没给才轮到"默认不含 LEFT"
                .ne((!filteredByStatus) && !includeLeft, Member::getStatus, MemberStatus.LEFT)
                .orderByAsc(Member::getId);                           // 稳定兜底，真正的次序见 rosterOrderer
        List<Member> all = new java.util.ArrayList<>(memberMapper.selectList(wrapper));
        all.sort(rosterOrderer.order());
        int from = Math.max(0, (page - 1) * size);
        List<Member> slice = from >= all.size()
                ? List.of() : all.subList(from, Math.min(all.size(), from + size));
        Map<Long, String> sectionNames = sectionLookup.sectionNamesOf(
                slice.stream().map(Member::getSectionId).toList());
        List<MemberView> views = slice.stream()
                .map(member -> viewAssembler.toView(member, viewer, sectionNames))
                .toList();
        return PageResult.of(views, all.size(), page, size);
    }

    public MemberView get(long id, CurrentUser viewer) {
        Member member = memberLookup.requireMember(id);
        if (Boolean.TRUE.equals(member.getIsSystem())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return viewAssembler.toView(member, viewer, sectionLookup.sectionNamesOfOne(member.getSectionId()));
    }

    /** 个人页的读口子（docs/03 §3.3）：只读自己，不裁剪——本人的资料就是给本人看的。 */
    public MemberProfileView profileOf(long memberId) {
        Member member = memberLookup.requireMember(memberId);
        return new MemberProfileView(member.getId(), member.getName(), member.getAccount(),
                member.getRole(), sectionLookup.sectionNameOf(member.getSectionId()), member.getInstrument(),
                member.getStudentNo(), member.getJoinedAt(), member.getAvatar(), member.getIntro(),
                member.getContact(), member.getBirthday());
    }
}
