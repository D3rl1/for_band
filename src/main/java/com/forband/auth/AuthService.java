package com.forband.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.dto.LoginRequest;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final TokenService tokenService;
    private final LoginThrottle loginThrottle;
    private final PermissionService permissionService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(MemberMapper memberMapper, SectionMapper sectionMapper,
                       TokenService tokenService, LoginThrottle loginThrottle,
                       PermissionService permissionService) {
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.tokenService = tokenService;
        this.loginThrottle = loginThrottle;
        this.permissionService = permissionService;
    }

    public record LoginResponse(String token, LocalDateTime expiresAt, MemberBrief member) {
    }

    public record MemberBrief(long id, String name, String role, Long sectionId, String sectionName) {
    }

    public record MeResponse(long id, String account, String name, String role,
                             Long sectionId, String sectionName, String status,
                             LocalDate joinedAt, String instrument, String avatar) {
    }

    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        loginThrottle.delayIfThrottled(ip, request.account());
        Member member = memberMapper.selectOne(
                new LambdaQueryWrapper<Member>().eq(Member::getAccount, request.account()));
        boolean matches = member != null
                && member.getPasswordHash() != null
                && encoder.matches(request.password(), member.getPasswordHash());
        if (!matches) {
            loginThrottle.recordFailure(ip, request.account());
            throw new BusinessException(ErrorCode.WRONG_CREDENTIALS);
        }
        if (!permissionService.canLogin(member)) {
            tokenService.revokeAllFor(member.getId());
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        LambdaUpdateWrapper<Member> update = new LambdaUpdateWrapper<Member>()
                .eq(Member::getId, member.getId())
                .set(Member::getLastLoginAt, LocalDateTime.now());
        if (member.getInitialPassword() != null) {
            update.set(Member::getInitialPassword, null);
        }
        memberMapper.update(null, update);

        // 采集来源 = 这一次登录请求（docs/01 §1.4）：UA 与 IP 随 token 一并落库
        TokenService.IssuedToken issued = tokenService.issue(member.getId(), ip, userAgent);
        return new LoginResponse(issued.raw(), issued.expiresAt(), toBrief(member));
    }

    public MeResponse me(CurrentUser user) {
        Member member = memberMapper.selectById(user.memberId());
        if (member == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return new MeResponse(member.getId(), member.getAccount(), member.getName(),
                member.getRole(), member.getSectionId(), sectionNameOf(member), member.getStatus(),
                member.getJoinedAt(), member.getInstrument(), member.getAvatar());
    }

    public void logout(String rawToken) {
        tokenService.revoke(rawToken);
    }

    // ---------- 会话管理（docs/01 §1.3/§1.4） ----------

    /** 我的登录设备列表行。device=两段式标签；ip=打码后（库里存完整值，只在这里遮）。 */
    public record SessionView(long id, String device, String ip,
                              LocalDateTime issuedAt, LocalDateTime lastSeenAt, boolean current) {
    }

    /** 有效会话列表：过期/已撤销行是登录流水不删、但不进列表；不分页，新签发的在前。 */
    public java.util.List<SessionView> sessions(CurrentUser user, String rawToken) {
        UserToken current = tokenService.resolveToken(rawToken);
        return tokenService.listActive(user.memberId()).stream()
                .map(t -> new SessionView(t.getId(),
                        UserAgentLabel.parse(t.getUserAgent()),
                        maskIp(t.getIp()),
                        t.getIssuedAt(), t.getLastSeenAt(),
                        current != null && t.getId().equals(current.getId())))
                .toList();
    }

    /** 踢单条：非本人 → 403/30002（越权样板 SECURITY.md A2）；不存在/已失效 → 幂等成功（docs/10 §10.4）。 */
    public void revokeSession(CurrentUser user, long sessionId) {
        UserToken row = tokenService.findById(sessionId);
        if (row == null) {
            return;
        }
        if (row.getMemberId() != user.memberId()) {
            throw new BusinessException(ErrorCode.NOT_SELF);
        }
        tokenService.revokeById(sessionId, user.memberId());
    }

    /** 踢掉其他所有设备，当前这条始终保留（docs/10 §10.4：自己把自己踢下线是荒唐的）。 */
    public void revokeOthers(CurrentUser user, String rawToken) {
        UserToken current = tokenService.resolveToken(rawToken);
        if (current == null) {
            return;
        }
        tokenService.revokeOthers(user.memberId(), current.getId());
    }

    /** IP 打码只发生在展示：IPv4 抹掉最后一段（192.168.1.*），IPv6 只留前三组（SECURITY.md B9）。 */
    private static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        if (ip.contains(":")) {
            String[] groups = ip.split(":");
            return String.join(":", java.util.Arrays.copyOfRange(groups, 0, Math.min(3, groups.length))) + "::";
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot < 0 ? ip : ip.substring(0, lastDot + 1) + "*";
    }

    /** 改密码成功 → 该用户全部 token 立即失效（docs/01，含当前 token）。 */
    public void changePassword(CurrentUser user, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        Member member = memberMapper.selectById(user.memberId());
        if (member == null || !encoder.matches(oldPassword, member.getPasswordHash())) {
            throw new BusinessException(ErrorCode.WRONG_OLD_PASSWORD);
        }
        memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                .eq(Member::getId, member.getId())
                .set(Member::getPasswordHash, encoder.encode(newPassword)));
        tokenService.revokeAllFor(member.getId());
    }

    private MemberBrief toBrief(Member member) {
        return new MemberBrief(member.getId(), member.getName(), member.getRole(),
                member.getSectionId(), sectionNameOf(member));
    }

    private String sectionNameOf(Member member) {
        if (member.getSectionId() == null) {
            return null;
        }
        Section section = sectionMapper.selectById(member.getSectionId());
        return section == null ? null : section.getName();
    }
}
