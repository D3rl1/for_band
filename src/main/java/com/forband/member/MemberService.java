package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.forband.member.dto.MemberUpdateRequest;
import com.forband.member.dto.MemberView;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MemberService {

    /** 初始密码字母表：排除易混淆的 0/O、1/l/I（docs/01）。 */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final MemberMapper memberMapper;
    private final MemberRoleHistoryMapper historyMapper;
    private final SectionMapper sectionMapper;
    private final TokenService tokenService;
    private final PermissionService permissionService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public MemberService(MemberMapper memberMapper, MemberRoleHistoryMapper historyMapper,
                         SectionMapper sectionMapper, TokenService tokenService,
                         PermissionService permissionService) {
        this.memberMapper = memberMapper;
        this.historyMapper = historyMapper;
        this.sectionMapper = sectionMapper;
        this.tokenService = tokenService;
        this.permissionService = permissionService;
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
        memberMapper.insert(member);

        writeHistory(member.getId(), role, member.getSectionId(), member.getJoinedAt(), operator.memberId());
        return new MemberCreateResponse(member.getId(), member.getName(), member.getAccount(),
                sectionNameOf(member.getSectionId()), role, initialPassword);
    }

    @Transactional
    public MemberView update(long id, MemberUpdateRequest request, CurrentUser operator) {
        Member member = requireMember(id);
        OpLogRecorder.putBefore(Map.of(
                "role", member.getRole(),
                "sectionId", member.getSectionId(),
                "status", member.getStatus()));

        boolean roleChanged = request.role() != null && !request.role().equals(member.getRole());
        boolean sectionChanged = request.sectionId() != null
                && !request.sectionId().equals(member.getSectionId());
        boolean statusChanged = request.status() != null && !request.status().equals(member.getStatus());

        if (request.role() != null) {
            validateRole(request.role());
        }
        if (request.sectionId() != null) {
            requireSection(request.sectionId());
        }
        boolean staysSectionLeader = Role.SECTION_LEADER.name().equals(
                roleChanged ? request.role() : member.getRole());
        if (staysSectionLeader && (sectionChanged || roleChanged)) {
            checkSectionLeaderUnique(request.sectionId() != null ? request.sectionId() : member.getSectionId(), id);
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
        memberMapper.updateById(upd);

        if (statusChanged) {
            applyStatusSideEffects(member, request.status());
        }
        if (roleChanged || sectionChanged) {
            String newRole = roleChanged ? request.role() : member.getRole();
            Long newSectionId = request.sectionId() != null ? request.sectionId() : member.getSectionId();
            closeOpenHistory(id, LocalDate.now());
            writeHistory(id, newRole, newSectionId, LocalDate.now(), operator.memberId());
        }
        Member updated = requireMember(id);
        return toView(updated, permissionService.canViewPhone(operator),
                sectionNamesOf(List.of(updated.getSectionId())));
    }

    @Transactional
    public String resetPassword(long id) {
        Member member = requireMember(id);
        String newPassword = randomPassword();
        memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                .eq(Member::getId, id)
                .set(Member::getPasswordHash, encoder.encode(newPassword))
                .set(Member::getInitialPassword, newPassword)
                .set(Member::getUpdatedAt, LocalDateTime.now()));
        tokenService.revokeAllFor(id);
        return newPassword;
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

    public PageResult<MemberView> list(String status, Long sectionId, int page, int size, CurrentUser viewer) {
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .eq(Member::getIsSystem, false)
                .eq(status != null && !status.isBlank(), Member::getStatus, status)
                .eq(sectionId != null, Member::getSectionId, sectionId)
                .orderByAsc(Member::getSectionId)
                .orderByAsc(Member::getId);
        Page<Member> result = memberMapper.selectPage(Page.of(page, size), wrapper);
        boolean canViewPhone = permissionService.canViewPhone(viewer);
        Map<Long, String> sectionNames = sectionNamesOf(
                result.getRecords().stream().map(Member::getSectionId).toList());
        List<MemberView> views = result.getRecords().stream()
                .map(member -> toView(member, canViewPhone, sectionNames))
                .toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    public MemberView get(long id, CurrentUser viewer) {
        Member member = requireMember(id);
        if (Boolean.TRUE.equals(member.getIsSystem())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toView(member, permissionService.canViewPhone(viewer),
                sectionNamesOf(List.of(member.getSectionId())));
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

    private MemberView toView(Member member, boolean canViewPhone, Map<Long, String> sectionNames) {
        return new MemberView(member.getId(), member.getName(), member.getStudentNo(),
                member.getSectionId(),
                member.getSectionId() == null ? null : sectionNames.get(member.getSectionId()),
                member.getInstrument(),
                canViewPhone ? member.getPhone() : null,
                member.getRole(), member.getStatus(), member.getAccount(),
                member.getInitialPassword() == null,
                member.getJoinedAt(), member.getLeftAt(), member.getLastLoginAt(), member.getNote());
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
