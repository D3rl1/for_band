package com.forband.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 不透明 token（DECISIONS D4）：原文只返回一次、绝不落库不入日志；
 * 库里只存 SHA-256 哈希。服务端可随时作废（登出/改密码/停用）。
 */
@Service
public class TokenService {

    public static final Duration TOKEN_TTL = Duration.ofDays(30);

    private final UserTokenMapper tokenMapper;
    private final SecureRandom random = new SecureRandom();

    public TokenService(UserTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    public record IssuedToken(String raw, LocalDateTime expiresAt) {
    }

    public IssuedToken issue(long memberId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(TOKEN_TTL);

        UserToken token = new UserToken();
        token.setTokenHash(sha256Hex(raw));
        token.setMemberId(memberId);
        token.setIssuedAt(now);
        token.setExpiresAt(expiresAt);
        tokenMapper.insert(token);
        return new IssuedToken(raw, expiresAt);
    }

    /** 返回会员 id；token 无效/过期/已吊销返回 null。 */
    public Long resolveMemberId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        UserToken token = tokenMapper.selectOne(new LambdaQueryWrapper<UserToken>()
                .eq(UserToken::getTokenHash, sha256Hex(rawToken))
                .isNull(UserToken::getRevokedAt)
                .gt(UserToken::getExpiresAt, LocalDateTime.now()));
        return token == null ? null : token.getMemberId();
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokenMapper.update(null, new LambdaUpdateWrapper<UserToken>()
                .eq(UserToken::getTokenHash, sha256Hex(rawToken))
                .isNull(UserToken::getRevokedAt)
                .set(UserToken::getRevokedAt, LocalDateTime.now()));
    }

    public void revokeAllFor(long memberId) {
        tokenMapper.update(null, new LambdaUpdateWrapper<UserToken>()
                .eq(UserToken::getMemberId, memberId)
                .isNull(UserToken::getRevokedAt)
                .set(UserToken::getRevokedAt, LocalDateTime.now()));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
