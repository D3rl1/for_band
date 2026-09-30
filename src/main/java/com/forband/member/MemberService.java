package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.auth.TokenService;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.log.OpLogRecorder;
import com.forband.member.dto.MemberCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberProfileView;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.member.dto.MemberView;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MemberService {

    /** 初始密码字母表：排除易混淆的 0/O、1/l/I（docs/01）。 */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    /** 「对外只到月-日 / 只到年份」用的两个格式（本人与管理员走 `ISO_LOCAL_DATE` 全值）。 */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter YEAR_ONLY = DateTimeFormatter.ofPattern("yyyy");

    /**
     * 可经建号 / 编辑接口授予的角色白名单——**不含 `ADMIN`**（`docs/01` §1.3：管理员账号只能由
     * `AdminSeeder` 播种，系统内没有人能修改 / 制造它）。`HONORARY` 在列，但建号另有禁令（见 {@link #create}）。
     */
    private static final Set<String> EDITABLE_ROLES = Set.of(
            Role.MEMBER.name(), Role.SECTION_LEADER.name(), Role.CONDUCTOR.name(),
            Role.LEADER.name(), Role.HONORARY.name());

    private final MemberMapper memberMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final SectionMapper sectionMapper;
    private final TokenService tokenService;
    private final PermissionService permissionService;
    private final MeProfileService meProfileService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public MemberService(MemberMapper memberMapper, MemberRoleHistoryMapper historyMapper,
                         SectionMapper sectionMapper, TokenService tokenService,
                         PermissionService permissionService, MeProfileService meProfileService) {
        this.memberMapper = memberMapper;
        this.historyMapper = historyMapper;
        this.sectionMapper = sectionMapper;
        this.tokenService = tokenService;
        this.permissionService = permissionService;
        this.meProfileService = meProfileService;
    }

    // ---------- 管理员操作 ----------

    @Transactional
    public MemberCreateResponse create(MemberCreateRequest request, CurrentUser operator) {
        Long exists = memberMapper.selectCount(
                new LambdaQueryWrapper<Member>().eq(Member::getAccount, request.account()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_DUPLICATE);
        }
        String role = request.role() == null ? Role.MEMBER.name() : request.role();
        validateRole(role);
        if (Role.HONORARY.name().equals(role)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "荣誉团员只能授予已毕业成员，走「改角色」入口");
        }
        if (request.sectionId() != null) {
            requireSection(request.sectionId());
        }
        if (Role.SECTION_LEADER.name().equals(role)) {
            checkSectionLeaderUnique(request.sectionId(), null);
        }

        String initialPassword = randomPassword();
        Member member = new Member();
        member.setName(request.name());
        member.setAccount(request.account());
        member.setSectionId(request.sectionId());
        member.setRole(role);
        member.setStatus(MemberStatus.ACTIVE);
        member.setPasswordHash(encoder.encode(initialPassword));
        member.setInitialPassword(initialPassword);
        member.setIsSystem(false);
        member.setStudentNo(request.studentNo());
        member.setInstrument(request.instrument());
        member.setPhone(request.phone());
        member.setNote(request.note());
        member.setJoinedAt(request.joinedAt() == null ? LocalDate.now() : request.joinedAt());
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 并发建号：两人同时过了上面的查重，唯一键（uk_account）兜底 → 409，不能冒成 500
            throw new BusinessException(ErrorCode.ACCOUNT_DUPLICATE);
        }

        writeHistory(member.getId(), role, member.getSectionId(), member.getJoinedAt(), operator.memberId());
        return new MemberCreateResponse(member.getId(), member.getName(), member.getAccount(),
                sectionNameOf(member.getSectionId()), role, initialPassword);
    }

    @Transactional
    public MemberView update(long id, MemberUpdateRequest request, CurrentUser operator) {
        Member member = requireWritableMember(id);
        OpLogRecorder.putBefore(snapshotOf(member));

        // 清空声部的唯一途径是显式标记（docs/03 §3.4 边界②）：`sectionId == null` 在本接口一律当"未提供"
        // （`updateById` 忽略 null 字段），否则弹窗里"选无声部"会被静默忽略、点了没反应。
        boolean clearSection = Boolean.TRUE.equals(request.clearSection());
        Long effectiveSectionId = clearSection ? null
                : (request.sectionId() != null ? request.sectionId() : member.getSectionId());
        String effectiveRole = request.role() != null ? request.role() : member.getRole();
        String effectiveStatus = request.status() != null ? request.status() : member.getStatus();

        boolean roleChanged = request.role() != null && !request.role().equals(member.getRole());
        boolean sectionChanged = !Objects.equals(effectiveSectionId, member.getSectionId());
        boolean statusChanged = request.status() != null && !request.status().equals(member.getStatus());

        if (request.role() != null) {
            validateRole(request.role());
        }
        if (request.sectionId() != null && !clearSection) {
            requireSection(request.sectionId());
        }
        // 边界①（docs/03 §3.4）：选了声部长却没有声部 ⇒ 拒绝（能造出"没有声部的声部长"）。
        // 拦在后端、前端不预判；`checkSectionLeaderUnique` 遇 null 直接 return，拦不住它。
        if (Role.SECTION_LEADER.name().equals(effectiveRole) && effectiveSectionId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "声部长必须指定声部");
        }
        boolean staysSectionLeader = Role.SECTION_LEADER.name().equals(effectiveRole);
        if (staysSectionLeader && (sectionChanged || roleChanged)) {
            checkSectionLeaderUnique(effectiveSectionId, id);
        }

        // 边界③（docs/03 §3.4）：当前是荣誉团员时，角色只能"撤销为团员"（续期不动角色）。
        // 直接改成团长 / 指挥 / 声部长会绕过 honorary_until 的有效期校验，
        // 并留下"已毕业的人挂着团长头衔"这种自相矛盾的记录（member_role_history 里还会多一段假任期）。
        if (Role.HONORARY.name().equals(member.getRole()) && roleChanged
                && !Role.MEMBER.name().equals(request.role())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "荣誉团员的角色只能撤销为团员（不能改成团长 / 指挥 / 声部长）");
        }

        // 荣誉团员规则（docs/03 §3.4 + docs/13 不变式 13，双向）：结果是荣誉团员就必须保持
        // status=GRADUATED——授予那一刻要校验，授予之后把状态改成 ACTIVE 同样要拦
        // （放行的话 canLogin 第一步就通过，honorary_until 当场失效＝永久可登录，且能签到）。
        // 撤销荣誉走"角色改回 MEMBER"（下面会显式清 honorary_until）。
        boolean honorary = Role.HONORARY.name().equals(effectiveRole);
        if (honorary && !MemberStatus.GRADUATED.equals(effectiveStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "荣誉团员的状态必须保持 GRADUATED（撤销荣誉请把角色改回 MEMBER）");
        }
        // 授予（角色变更）必须带登录有效期，fail-closed；续期（角色不变只改日期）走同一入口。
        if (honorary && roleChanged && request.honoraryUntil() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "授予荣誉团员必须填写登录有效期");
        }

        Member upd = new Member();
        upd.setId(id);
        upd.setName(request.name());
        upd.setSectionId(request.sectionId());
        upd.setRole(request.role());
        upd.setStatus(request.status());
        upd.setStudentNo(request.studentNo());
        upd.setInstrument(request.instrument());
        upd.setPhone(request.phone());
        upd.setNote(request.note());
        upd.setJoinedAt(request.joinedAt());
        // 个人页四列：管理员也能改（docs/03 §3.4「谁能改什么」）。avatar 同样要过白名单。
        if (request.avatar() != null) {
            meProfileService.validateAvatar(request.avatar());
        }
        upd.setAvatar(request.avatar());
        upd.setIntro(request.intro());
        upd.setContact(request.contact());
        upd.setBirthday(request.birthday());
        if (honorary && request.honoraryUntil() != null) {
            upd.setHonoraryUntil(request.honoraryUntil());
        }
        memberMapper.updateById(upd);
        if (!honorary && member.getHonoraryUntil() != null) {
            // updateById 忽略 null 字段，清列必须显式 set（docs/13：非荣誉团员本列恒为 NULL）
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, id)
                    .set(Member::getHonoraryUntil, null));
        }
        if (clearSection && member.getSectionId() != null) {
            // 同上：updateById 忽略 null ⇒"清空声部"必须显式 set，否则选了没反应（docs/03 §3.4 边界②）
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, id)
                    .set(Member::getSectionId, null));
        }

        if (statusChanged) {
            applyStatusSideEffects(member, request.status());
        }
        if (roleChanged || sectionChanged) {
            String newRole = roleChanged ? request.role() : member.getRole();
            closeOpenHistory(id, LocalDate.now());
            writeHistory(id, newRole, effectiveSectionId, LocalDate.now(), operator.memberId());
        }
        Member updated = requireMember(id);
        OpLogRecorder.putAfter(snapshotOf(updated));
        return toView(updated, operator,
                // ⚠️ 不能直接 List.of(...)：无声部的人（指挥 / 老师）该项为 null，List.of 会 NPE → 500
                sectionNamesOf(updated.getSectionId() == null
                        ? List.of() : List.of(updated.getSectionId())));
    }

    /** 留痕快照（docs/11 §11.2：只存变化的关键字段）。用 LinkedHashMap 而不是 Map.of——
     * sectionId / joinedAt 都可为 null，而 Map.of 遇 null 会 NPE。 */
    private static Map<String, Object> snapshotOf(Member member) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("role", member.getRole());
        snapshot.put("sectionId", member.getSectionId());
        snapshot.put("status", member.getStatus());
        snapshot.put("joinedAt", member.getJoinedAt() == null ? null : member.getJoinedAt().toString());
        return snapshot;
    }

    @Transactional
    public String resetPassword(long id) {
        requireWritableMember(id);
        String newPassword = randomPassword();
        memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                .eq(Member::getId, id)
                .set(Member::getPasswordHash, encoder.encode(newPassword))
                .set(Member::getInitialPassword, newPassword)
                .set(Member::getUpdatedAt, LocalDateTime.now()));
        tokenService.revokeAllFor(id);
        return newPassword;
    }

    /**
     * 踢某人下线：作废其全部 token，但不动账号状态（`docs/01` §1.3；可重新登录）。
     * 走这里而不是让控制器直调 {@link TokenService}，是为了同过 {@link #requireWritableMember} 那道闸门
     * ——`is_system = 1` 的账号连"踢下线"也不能碰。
     */
    public void revokeSessions(long id) {
        requireWritableMember(id);
        tokenService.revokeAllFor(id);
    }

    /** 全部账号的激活状态表：未激活显示初始密码，已激活显示「已激活」（docs/01）。 */
    public String exportAccountsCsv() {
        List<Member> members = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                .isNotNull(Member::getAccount)
                .orderByAsc(Member::getSectionId));
        Map<Long, String> sectionNames = sectionNamesOf(members.stream()
                .map(Member::getSectionId).toList());
        StringBuilder csv = new StringBuilder("\uFEFF姓名,声部,账号,初始密码,状态\r\n");
        for (Member member : members) {
            String section = member.getSectionId() == null ? "" : sectionNames.getOrDefault(member.getSectionId(), "");
            String password = member.getInitialPassword() == null ? "已激活" : member.getInitialPassword();
            csv.append(escape(member.getName())).append(',')
                    .append(escape(section)).append(',')
                    .append(escape(member.getAccount())).append(',')
                    .append(escape(password)).append(',')
                    .append(escape(member.getStatus())).append("\r\n");
        }
        return csv.toString();
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
                .orderByAsc(Member::getId);                           // 稳定兜底，真正的次序见 rosterOrder()
        List<Member> all = new java.util.ArrayList<>(memberMapper.selectList(wrapper));
        all.sort(rosterOrder());
        int from = Math.max(0, (page - 1) * size);
        List<Member> slice = from >= all.size()
                ? List.of() : all.subList(from, Math.min(all.size(), from + size));
        Map<Long, String> sectionNames = sectionNamesOf(
                slice.stream().map(Member::getSectionId).toList());
        List<MemberView> views = slice.stream()
                .map(member -> toView(member, viewer, sectionNames))
                .toList();
        return PageResult.of(views, all.size(), page, size);
    }

    /** `docs/03` §3.4 的次序：**在团 → 非在团（整块沉底）→ 荣誉**，组内再 指挥 → 团长 → 声部 `order_no`
     *  → 声部长 → 团员 → id。⚠️ 声部那一档按 **`section.order_no`**（不是 id，两者顺序不保证一致）。 */
    private java.util.Comparator<Member> rosterOrder() {
        Map<Long, Integer> sectionOrder = new java.util.HashMap<>();
        int n = 0;
        for (Section s : sectionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Section>()
                        .orderByAsc("order_no"))) {
            sectionOrder.put(s.getId(), n++);
        }
        return java.util.Comparator
                .comparingInt(MemberService::rosterGroup)
                .thenComparingInt(MemberService::rosterRole)
                .thenComparingInt((Member m) -> sectionRank(m, sectionOrder))
                .thenComparingInt((Member m) -> Role.SECTION_LEADER.name().equals(m.getRole()) ? 0 : 1)
                .thenComparingLong(Member::getId);
    }

    /** 0 = 在团且非荣誉；1 = 非在团（休团 / 毕业等）且非荣誉 —— **整块**沉到最后，不混在声部里；
     *  2 = 荣誉团员 —— 前端在它前面插一节「历届成员」。 */
    private static int rosterGroup(Member m) {
        if (Role.HONORARY.name().equals(m.getRole())) {
            return 2;
        }
        return MemberStatus.ACTIVE.equals(m.getStatus()) ? 0 : 1;
    }

    private static int rosterRole(Member m) {
        if (Role.CONDUCTOR.name().equals(m.getRole())) {
            return 0;
        }
        return Role.LEADER.name().equals(m.getRole()) ? 1 : 2;
    }

    /** 没有声部的（指挥 / 团长 / 未分配）排在最后——它们本来就靠 `rosterRole` 站到了前面。 */
    private static int sectionRank(Member m, Map<Long, Integer> sectionOrder) {
        Integer rank = m.getSectionId() == null ? null : sectionOrder.get(m.getSectionId());
        return rank == null ? Integer.MAX_VALUE : rank;
    }

    public MemberView get(long id, CurrentUser viewer) {
        Member member = requireMember(id);
        if (Boolean.TRUE.equals(member.getIsSystem())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toView(member, viewer, sectionNamesOfOne(member.getSectionId()));
    }

    /** 个人页的读口子（docs/03 §3.3）：只读自己，不裁剪——本人的资料就是给本人看的。 */
    public MemberProfileView profileOf(long memberId) {
        Member member = requireMember(memberId);
        return new MemberProfileView(member.getId(), member.getName(), member.getAccount(),
                member.getRole(), sectionNameOf(member.getSectionId()), member.getInstrument(),
                member.getStudentNo(), member.getJoinedAt(), member.getAvatar(), member.getIntro(),
                member.getContact(), member.getBirthday());
    }

    // ---------- 内部 ----------

    private void applyStatusSideEffects(Member before, String newStatus) {
        if (MemberStatus.LEFT.equals(newStatus) || MemberStatus.GRADUATED.equals(newStatus)) {
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, before.getId())
                    .set(Member::getLeftAt, LocalDate.now())
                    .set(Member::getUpdatedAt, LocalDateTime.now()));
        } else if (MemberStatus.ACTIVE.equals(newStatus) && before.getLeftAt() != null) {
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, before.getId())
                    .set(Member::getLeftAt, null)
                    .set(Member::getUpdatedAt, LocalDateTime.now()));
        }
        if (!MemberStatus.ACTIVE.equals(newStatus)) {
            tokenService.revokeAllFor(before.getId());
        }
    }

    /** 硬规则（docs/13 不变式 8）：变更角色/声部必须同时写历史。 */
    private void closeOpenHistory(long memberId, LocalDate toDate) {
        historyMapper.update(null, new LambdaUpdateWrapper<MemberRoleHistory>()
                .eq(MemberRoleHistory::getMemberId, memberId)
                .isNull(MemberRoleHistory::getToDate)
                .set(MemberRoleHistory::getToDate, toDate));
    }

    private void writeHistory(long memberId, String role, Long sectionId,
                              LocalDate fromDate, Long createdBy) {
        MemberRoleHistory history = new MemberRoleHistory();
        history.setMemberId(memberId);
        history.setRole(role);
        history.setSectionId(sectionId);
        history.setFromDate(fromDate);
        history.setCreatedBy(createdBy);
        historyMapper.insert(history);
    }

    /** 「每个声部最多一个声部长」在应用层校验（DECISIONS D11）。只数在团的。 */
    private void checkSectionLeaderUnique(Long sectionId, Long excludeMemberId) {
        if (sectionId == null) {
            return;
        }
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .eq(Member::getRole, Role.SECTION_LEADER.name())
                .eq(Member::getSectionId, sectionId)
                .eq(Member::getStatus, MemberStatus.ACTIVE)
                .eq(Member::getIsSystem, false);
        if (excludeMemberId != null) {
            wrapper.ne(Member::getId, excludeMemberId);
        }
        Long count = memberMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.SECTION_LEADER_EXISTS);
        }
    }

    private void validateRole(String role) {
        try {
            Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知角色：" + role);
        }
        // ⚠️ 枚举合法 ≠ 取值白名单：`Role.valueOf` 会放行 ADMIN，而本接口一旦能写 role=ADMIN，
        // 就能在界面之外造出第二个管理员——`docs/01` §1.3 的"管理员只有一个、由播种产生"当场失效，
        // 且 `#80` 的写侧闸门（只认 `is_system = 1`）管不到这个新管理员。
        if (!EDITABLE_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能把角色设为：" + role);
        }
    }

    private void requireSection(Long sectionId) {
        if (sectionMapper.selectById(sectionId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "声部不存在");
        }
    }

    private Member requireMember(long id) {
        Member member = memberMapper.selectById(id);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团员不存在");
        }
        return member;
    }

    /**
     * 写侧闸门（`docs/01` §1.3「管理员账号的写侧闸门」）：`is_system = 1` 的账号**任何写操作一律 404**，
     * 沿用同处 {@link #get} 的先例——**不承认它存在**（回 403 等于承认它存在，多一处可探测的信息）。
     * <p>
     * 为什么必须有：`update` 原来只调 {@code requireMember}（只查存在性），拿管理员账号调
     * `PUT /api/admin/members/{id}` 能把它降成团员 ⇒ **系统里再也没有管理员**，
     * 而唯一的出路 `reset-admin-password.sh` **只改密码、不改角色**，救不回来。
     */
    private Member requireWritableMember(long id) {
        Member member = requireMember(id);
        if (Boolean.TRUE.equals(member.getIsSystem())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return member;
    }

    /**
     * 名册视图的**唯一生产点**：可见性全在这里裁（`docs/03` §3.4 那张「`MemberView` 各字段的可见性表」）。
     * <p>
     * ⚠️ **两处口径是用户 2026-09-25 当场改的，与那张表相反**（见 `交接单.md` `#42` 的移交事项）：
     * `contact` **对全团可见**（"填了就说明不担心"）、`account` **维持可见**（"就是姓名拼音"）。
     */
    private MemberView toView(Member member, CurrentUser viewer, Map<Long, String> sectionNames) {
        boolean isAdmin = viewer.role() == Role.ADMIN;
        // 本人看自己 = 看自己的资料，姓名 / 学号 / 生日都给全值
        boolean fullValue = isAdmin || viewer.memberId() == member.getId();
        return new MemberView(member.getId(), member.getName(),
                // 学号与生日同级隐私（T17）：本人 + 管理员
                fullValue ? member.getStudentNo() : null,
                member.getSectionId(),
                member.getSectionId() == null ? null : sectionNames.get(member.getSectionId()),
                member.getInstrument(),
                permissionService.canViewPhone(viewer) ? member.getPhone() : null,
                member.getRole(), member.getStatus(), member.getAccount(),
                // activated（= 还在用初始密码）/ 荣誉有效期 / 最后登录：账号级信息，仅管理员
                // （界面上也只有管理端用得到；给别人 null，别用 false 冒充"未激活"）
                isAdmin ? Boolean.valueOf(member.getInitialPassword() == null) : null,
                dateText(member.getJoinedAt(), fullValue),
                dateText(member.getLeftAt(), fullValue),
                isAdmin ? member.getHonoraryUntil() : null,
                isAdmin ? member.getLastLoginAt() : null,
                isAdmin ? member.getNote() : null,
                member.getAvatar(), member.getIntro(), member.getContact(),
                birthdayText(member.getBirthday(), fullValue));
    }

    /**
     * 生日：本人 / 管理员看全值，**别人只到「月-日」、不显示年份**（`docs/03` §3.4 + `docs/13` §13.3②；
     * 用户 2026-09-25 拍板"按规格实现"，此前实现比规格更保守、对外一律 null，见 `交接单.md` `#8`）。
     */
    private static String birthdayText(LocalDate birthday, boolean fullValue) {
        if (birthday == null) {
            return null;
        }
        return birthday.format(fullValue ? DateTimeFormatter.ISO_LOCAL_DATE : MONTH_DAY);
    }

    /**
     * 入团 / 退团时间：本人与管理员看全值（管理端的日期输入要完整值才能编辑），**别人只到年份**（`docs/03` §3.4）。
     */
    private static String dateText(LocalDate date, boolean fullValue) {
        if (date == null) {
            return null;
        }
        return date.format(fullValue ? DateTimeFormatter.ISO_LOCAL_DATE : YEAR_ONLY);
    }

    private String sectionNameOf(Long sectionId) {
        if (sectionId == null) {
            return null;
        }
        Section section = sectionMapper.selectById(sectionId);
        return section == null ? null : section.getName();
    }

    private Map<Long, String> sectionNamesOf(List<Long> sectionIds) {
        List<Long> distinct = sectionIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Section::getName, (a, b) -> a));
    }

    /**
     * 单条版。⚠️ **不能用 `List.of(声部 id)`**：团长 / 指挥 / 未分配声部的团员 `section_id` 是 null，
     * 而 `List.of` 不收 null、会当场 NPE（看这些人的主页 = 500）。上面那个批量版是从流里取的，天然过滤了 null。
     */
    private Map<Long, String> sectionNamesOfOne(Long sectionId) {
        return sectionId == null ? Map.of() : sectionNamesOf(List.of(sectionId));
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
