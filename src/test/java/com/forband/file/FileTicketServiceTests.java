package com.forband.file;

import com.forband.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileTicketService} 的**跨天分桶**与密钥兜底——这些用真钟跑不了，必须固定钟。
 * 分桶时区 `Asia/Shanghai`：同一个"北京日"里票据值恒定，跨午夜给一天宽限（今天 / 昨天两个桶都收）。
 */
class FileTicketServiceTests {

    private static final ZoneId ZONE = FileTicketService.ZONE;

    /** 北京时间 = UTC+8：`T02:00:00Z` 即北京 10:00。 */
    private static FileTicketService at(String utcInstant) {
        return new FileTicketService("unit-secret", Clock.fixed(Instant.parse(utcInstant), ZONE));
    }

    private static String ticketOf(long fileId, String viewUrl) {
        return viewUrl.substring(viewUrl.indexOf("t=") + 2, viewUrl.indexOf("&dl="));
    }

    @Test
    void stable_within_a_day_then_accepted_through_the_next_day_then_rejected() {
        String ticket = ticketOf(7, at("2026-10-07T02:00:00Z").issue(7).view());

        at("2026-10-07T02:00:00Z").verify(7, ticket);   // 当天 → 过
        at("2026-10-08T02:00:00Z").verify(7, ticket);   // 次日 → 仍过（收 today-1）
        assertThrows(BusinessException.class,
                () -> at("2026-10-09T02:00:00Z").verify(7, ticket));   // 前天 → 401
    }

    @Test
    void the_same_day_ticket_value_never_changes() {
        // 同一天里不同时刻取票，值必须一致（否则 URL 变动，浏览器缓存吃不到、反复打开就重复下载）
        String morning = ticketOf(7, at("2026-10-07T01:00:00Z").issue(7).view());
        String night = ticketOf(7, at("2026-10-07T15:59:00Z").issue(7).view());   // 北京 23:59
        assertEquals(morning, night);
    }

    @Test
    void midnight_boundary_gets_a_grace_period() {
        // 北京 23:59 签的票，跨到次日 00:01 仍可用（就是"收 yesterday 桶"要挡的那个瞬间）
        String ticket = ticketOf(7, at("2026-10-07T15:59:00Z").issue(7).view());
        at("2026-10-07T16:01:00Z").verify(7, ticket);   // 北京 2026-10-08 00:01 → 过
    }

    @Test
    void expires_at_is_two_days_out() {
        assertEquals(LocalDateTime.of(2026, 10, 9, 0, 0),
                at("2026-10-07T02:00:00Z").issue(1).expiresAt());
    }

    @Test
    void null_blank_or_forged_ticket_is_rejected() {
        FileTicketService s = at("2026-10-07T02:00:00Z");
        assertThrows(BusinessException.class, () -> s.verify(1, null));
        assertThrows(BusinessException.class, () -> s.verify(1, ""));
        assertThrows(BusinessException.class, () -> s.verify(1, "   "));
        assertThrows(BusinessException.class, () -> s.verify(1, "forged"));
    }

    @Test
    void a_ticket_for_one_file_is_rejected_for_another() {
        FileTicketService s = at("2026-10-07T02:00:00Z");
        String ticket = ticketOf(1, s.issue(1).view());
        assertThrows(BusinessException.class, () -> s.verify(2, ticket));
    }

    @Test
    void a_different_secret_cannot_forge_a_ticket() {
        String ticket = ticketOf(1, at("2026-10-07T02:00:00Z").issue(1).view());
        FileTicketService otherSecret = new FileTicketService(
                "another-secret", Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), ZONE));
        assertThrows(BusinessException.class, () -> otherSecret.verify(1, ticket));
    }

    @Test
    void empty_secret_falls_back_to_a_random_per_instance_key() {
        // ⚠️ 证明"未配置时绝不退回写死的明文默认值"：两个空密钥实例签出的票**互不相同、也互不认**，
        //    因为它们各自是随机生成的临时密钥（配置了固定密钥的实例才会一致）。
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), ZONE);
        FileTicketService a = new FileTicketService("", clock);
        FileTicketService b = new FileTicketService("", clock);
        String ticketA = ticketOf(1, a.issue(1).view());
        String ticketB = ticketOf(1, b.issue(1).view());
        assertNotEquals(ticketA, ticketB);
        assertThrows(BusinessException.class, () -> b.verify(1, ticketA));
        a.verify(1, ticketA);   // 自己签的自己认
    }
}
