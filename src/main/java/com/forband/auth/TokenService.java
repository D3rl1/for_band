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

    /** 签发会话行：随 token 落库登录时的 UA 与 IP（docs/01 §1.4，采集来源=这一次登录请求）。 */
    public IssuedToken issue(long memberId, String ip, String userAgent) {
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
        if (userAgent != null) {
            token.setUserAgent(userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent);
        }
        token.setIp(ip);
        tokenMapper.insert(token);
        return new IssuedToken(raw, expiresAt);
    }

    /** 取整行有效会话（docs/01 §1.4：当前设备按"本次请求携带的那条 token"判定，就靠它）。 */
    public UserToken resolveToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return tokenMapper.selectOne(new LambdaQueryWrapper<UserToken>()
                .eq(UserToken::getTokenHash, sha256Hex(rawToken))
                .isNull(UserToken::getRevokedAt)
                .gt(UserToken::getExpiresAt, LocalDateTime.now()));
    }

    /** 本人有效会话，新签发的在前（docs/10 §10.4 默认排序；不分页——一个账号设备数是个位数）。 */
    public java.util.List<UserToken> listActive(long memberId) {
        return tokenMapper.selectList(new LambdaQueryWrapper<UserToken>()
                .eq(UserToken::getMemberId, memberId)
                .isNull(UserToken::getRevokedAt)
                .gt(UserToken::getExpiresAt, LocalDateTime.now())
                .orderByDesc(UserToken::getId));
    }

    /** 按 id 取行（任意状态）——踢单条前判断"存在但非本人"要用，别的不许用。 */
    public UserToken findById(long id) {
        return tokenMapper.selectById(id);
    }

    /** 踢单条（带归属条件）；行不存在/已失效/非本人 → 无匹配行，天然幂等（docs/10 §10.4）。 */
    public void revokeById(long id, long memberId) {
        tokenMapper.update(null, new LambdaUpdateWrapper<UserToken>()
                .eq(UserToken::getId, id)
                .eq(UserToken::getMemberId, memberId)
                .isNull(UserToken::getRevokedAt)
                .set(UserToken::getRevokedAt, LocalDateTime.now()));
    }

    /** 踢掉其他所有设备，保留 keepId 那条（docs/10 §10.4：当前这条始终保留）。 */
    public void revokeOthers(long memberId, long keepId) {
        tokenMapper.update(null, new LambdaUpdateWrapper<UserToken>()
                .eq(UserToken::getMemberId, memberId)
                .ne(UserToken::getId, keepId)
                .isNull(UserToken::getRevokedAt)
                .set(UserToken::getRevokedAt, LocalDateTime.now()));
    }

    /**
     * last_seen_at 节流写（docs/13 §13.3④）：NULL 立即写（登录后第一个 API 请求就会填上），
     * 此后距上次超过 1 小时才写一次，避免每个请求多一次 UPDATE。
     */
    public void touchLastSeen(UserToken token) {
        LocalDateTime now = LocalDateTime.now();
        if (token.getLastSeenAt() != null && token.getLastSeenAt().isAfter(now.minusHours(1))) {
            return;
        }
        tokenMapper.update(null, new LambdaUpdateWrapper<UserToken>()
                .eq(UserToken::getId, token.getId())
                .set(UserToken::getLastSeenAt, now));
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
