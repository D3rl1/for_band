package com.forband.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.dto.LoginRequest;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberStatus;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final TokenService tokenService;
    private final LoginThrottle loginThrottle;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(MemberMapper memberMapper, SectionMapper sectionMapper,
                       TokenService tokenService, LoginThrottle loginThrottle) {
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.tokenService = tokenService;
        this.loginThrottle = loginThrottle;
    }

    public record LoginResponse(String token, LocalDateTime expiresAt, MemberBrief member) {
    }

    public record MemberBrief(long id, String name, String role, Long sectionId, String sectionName) {
    }

    public record MeResponse(long id, String account, String name, String role,
                             Long sectionId, String sectionName, String status) {
    }

    public LoginResponse login(LoginRequest request, String ip) {
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
        if (!MemberStatus.ACTIVE.equals(member.getStatus())) {
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

        TokenService.IssuedToken issued = tokenService.issue(member.getId());
        return new LoginResponse(issued.raw(), issued.expiresAt(), toBrief(member));
    }

    public MeResponse me(CurrentUser user) {
        Member member = memberMapper.selectById(user.memberId());
        if (member == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return new MeResponse(member.getId(), member.getAccount(), member.getName(),
                member.getRole(), member.getSectionId(), sectionNameOf(member), member.getStatus());
    }

    public void logout(String rawToken) {
        tokenService.revoke(rawToken);
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
