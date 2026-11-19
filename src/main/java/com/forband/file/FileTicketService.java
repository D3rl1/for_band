package com.forband.file;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 * 附件的**签名 URL 票据**（`docs/08` §8.5）。
 * <p>
 * 为什么需要它：`GET /api/files/{id}` 只认 `Authorization` **头**，而**移动浏览器没法给"导航/下载"
 * 请求附加请求头** —— 于是手机上点谱子只能靠前端把 blob 塞进 `iframe` / 新标签，而安卓系浏览器
 * （夸克 / 小米 / 微信）**没有内置 PDF 渲染器、也不认 blob 导航**，结果白屏、点了没反应。
 * 解法是让浏览器用**普通 URL**（票据走查询参数 `?t=`）自己去取——这是唯一在目标浏览器上都成立的路。
 * <p>
 * **无状态**：不新增表、不新增 `user_token` 行，票据 = `HMAC-SHA256(密钥, "file:" + id + ":" + 天)`。
 * **按"天"分桶**（`Asia/Shanghai`，与数据源 `serverTimezone` 同源）：同一天内票据值**恒定** ⇒
 * URL 一字不变 ⇒ **浏览器缓存生效，反复打开不重复下载**（用户的明确诉求）。校验时接受"今天 / 昨天"
 * 两个桶，跨过午夜的那几分钟也不会失效。
 * <p>
 * ⚠️ **票据不绑用户**（标准签名 URL 模型）：拿到 URL 的人可在窗口内转发。窗口 ≤ 2 天，且**取票本身
 * 要过 `FileService.requireViewable`**（荣誉团员在那里就被 403）——与 `docs/08` §8.5「这不是 DRM、
 * 是产品层面的约定」同一口径。
 */
@Service
public class FileTicketService {

    private static final Logger log = LoggerFactory.getLogger(FileTicketService.class);

    /** 分桶时区：必须与数据源一致（`application.yml` 的 `serverTimezone=Asia/Shanghai`）。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final byte[] secret;
    private final Clock clock;

    // ⚠️ 有两个构造器 ⇒ 必须标明哪个给 Spring 用，否则它去找无参构造器、启动即炸。
    @Autowired
    public FileTicketService(@Value("${band.file-ticket.secret:}") String configured) {
        this(configured, Clock.system(ZONE));
    }

    /** 供测试用固定钟。⚠️ 生产走上面那个构造器（系统钟）。 */
    FileTicketService(String configured, Clock clock) {
        this.clock = clock;
        if (configured == null || configured.isBlank()) {
            // ⚠️ **绝不能**退回一个写死的明文默认值（那等于人人可伪造签名）。
            //    未配置就用本次启动的临时密钥：安全兜底，代价是重启后旧链接失效——前端每次打开都重新取票，可自愈。
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            this.secret = ephemeral;
            log.warn("band.file-ticket.secret 未配置：本次启动用**临时密钥**签发文件票据，"
                    + "重启后已发出的票据会失效（前端会重新取票，可自愈）。"
                    + "生产请在服务器本地 application-local.yml 配一个固定值（docs/12 §12.6）。");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 签发一对 URL（调用方**必须已经**通过可见性判定）。 */
    public TicketLinks issue(long fileId) {
        String ticket = sign(fileId, epochDay());
        String base = "/api/files/" + fileId + "/view?t=" + ticket;
        LocalDateTime expiresAt = LocalDate.now(clock).plusDays(2).atStartOfDay();
        return new TicketLinks(base + "&dl=0", base + "&dl=1", expiresAt);
    }

    /** 校验票据。**无票 / 伪造 / 跨文件 / 过期一律 401**——绝不当"没票据"放行。 */
    public void verify(long fileId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        long today = epochDay();
        if (matches(fileId, today, ticket) || matches(fileId, today - 1, ticket)) {
            return;
        }
        throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }

    private boolean matches(long fileId, long day, String ticket) {
        byte[] expected = sign(fileId, day).getBytes(StandardCharsets.UTF_8);
        // 常量时间比对，别用 equals（避免按字符提前返回的时序差）
        return MessageDigest.isEqual(expected, ticket.getBytes(StandardCharsets.UTF_8));
    }

    private long epochDay() {
        return clock.instant().atZone(ZONE).toLocalDate().toEpochDay();
    }

    private String sign(long fileId, long epochDay) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(("file:" + fileId + ":" + epochDay).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 签发结果：一对随取随用的 URL + 到期时间（仅信息用）。 */
    public record TicketLinks(String view, String download, LocalDateTime expiresAt) {
    }
}
