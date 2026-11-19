package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.auth.TokenService;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.SectionLookup;
import com.forband.log.OperationLogRecorder;
import com.forband.member.dto.MemberCreateRequest;
import com.forband.member.dto.MemberCreateResponse;
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.member.dto.MemberView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 账号生命周期：建号 / 改号 / 改密 / 踢线，以及角色状态机与声部长唯一性等写侧规则。
 * 事务边界留在门面 {@link MemberService} 的同名方法上（从那里进入本类的调用都在同一事务里）。
 */
@Service
public class AccountLifecycleService {

    /** 初始密码字母表：排除易混淆的 0/O、1/l/I（docs/01）。 */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    /**
     * 可经建号 / 编辑接口授予的角色白名单——**不含 `ADMIN`**（`docs/01` §1.3：管理员账号只能由
     * `AdminSeeder` 播种，系统内没有人能修改 / 制造它）。`HONORARY` 在列，但建号另有禁令（见 {@link #create}）。
     */
    private static final Set<String> EDITABLE_ROLES = Set.of(
            Role.MEMBER.name(), Role.SECTION_LEADER.name(), Role.CONDUCTOR.name(),
            Role.LEADER.name(), Role.HONORARY.name());

    private final MemberMapper memberMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final SectionLookup sectionLookup;
    private final MemberLookup memberLookup;
    private final TokenService tokenService;
    private final PermissionService permissionService;
    private final MemberStatusHistoryService statusHistoryService;
    private final MeProfileService meProfileService;
    private final MemberViewAssembler viewAssembler;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public AccountLifecycleService(MemberMapper memberMapper, MemberRoleHistoryMapper historyMapper,
                                   SectionLookup sectionLookup, MemberLookup memberLookup,
                                   TokenService tokenService, PermissionService permissionService,
                                   MemberStatusHistoryService statusHistoryService, MeProfileService meProfileService,
                                   MemberViewAssembler viewAssembler, PasswordEncoder encoder) {
        this.memberMapper = memberMapper;
        this.historyMapper = historyMapper;
        this.sectionLookup = sectionLookup;
        this.memberLookup = memberLookup;
        this.tokenService = tokenService;
        this.permissionService = permissionService;
        this.statusHistoryService = statusHistoryService;
        this.meProfileService = meProfileService;
        this.viewAssembler = viewAssembler;
        this.encoder = encoder;
    }

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
            sectionLookup.requireSection(request.sectionId());
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
        statusHistoryService.record(member.getId(), MemberStatus.ACTIVE, MemberStatus.ACTIVE,
                member.getJoinedAt(), operator.memberId());
        return new MemberCreateResponse(member.getId(), member.getName(), member.getAccount(),
                sectionLookup.sectionNameOf(member.getSectionId()), role, initialPassword);
    }

    public MemberView update(long id, MemberUpdateRequest request, CurrentUser operator) {
        memberLookup.requireWritableMember(id);
        Member member = memberMapper.selectForUpdate(id);
        OperationLogRecorder.putBefore(snapshotOf(member));

        // 清空声部的唯一途径是显式标记（docs/03 §3.4 边界②）：`sectionId == null` 在本接口一律当"未提供"
        // （`updateById` 忽略 null 字段），否则弹窗里"选无声部"会被静默忽略、点了没反应。
        boolean clearSection = Boolean.TRUE.equals(request.clearSection());
        // 乐器同理（#217）：不带 instrument = 不改；要清空必须带 clearInstrument，否则 null 会被忽略
        boolean clearInstrument = Boolean.TRUE.equals(request.clearInstrument());
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
            sectionLookup.requireSection(request.sectionId());
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
        upd.setInstrument(clearInstrument ? null : request.instrument());
        upd.setPhone(request.phone());
        upd.setNote(request.note());
        upd.setJoinedAt(request.joinedAt());
        // 个人页四列：管理员也能改（docs/03 §3.4「谁能改什么」）。avatar 同样要过白名单。
        if (request.avatar() != null) {
            // 校验的是**目标成员**的资格（#266）：管理员自己的发放资格不能替别人过关。
            meProfileService.validateAvatar(request.avatar(), id);
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
        if (clearInstrument && member.getInstrument() != null) {
            // 乐器同样要显式 set null，否则"清空后保存"看起来成功、原值还在（#217）
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, id)
                    .set(Member::getInstrument, null));
        }

        if (statusChanged) {
            statusHistoryService.record(id, member.getStatus(), request.status(),
                    LocalDate.now(MemberStatusHistoryService.ZONE), operator.memberId());
            applyStatusSideEffects(member, request.status());
        }
        if (roleChanged || sectionChanged) {
            String newRole = roleChanged ? request.role() : member.getRole();
            closeOpenHistory(id, LocalDate.now());
            writeHistory(id, newRole, effectiveSectionId, LocalDate.now(), operator.memberId());
        }
        Member updated = memberLookup.requireMember(id);
        OperationLogRecorder.putAfter(snapshotOf(updated));
        return viewAssembler.toView(updated, operator,
                // ⚠️ 不能直接 List.of(...)：无声部的人（指挥 / 老师）该项为 null，List.of 会 NPE → 500
                sectionLookup.sectionNamesOf(updated.getSectionId() == null
                        ? List.of() : List.of(updated.getSectionId())));
    }

    public String resetPassword(long id) {
        memberLookup.requireWritableMember(id);
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
     * 走这里而不是让控制器直调 {@link TokenService}，是为了同过 {@link MemberLookup#requireWritableMember} 那道闸门
     * ——`is_system = 1` 的账号连"踢下线"也不能碰。
     */
    public void revokeSessions(long id) {
        memberLookup.requireWritableMember(id);
        tokenService.revokeAllFor(id);
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
        if (!permissionService.canLogin(memberLookup.requireMember(before.getId()))) {
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
                .in(Member::getStatus, MemberStatus.ACTIVE, MemberStatus.PAUSED)
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

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
