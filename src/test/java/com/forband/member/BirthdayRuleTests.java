package com.forband.member;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生日日期判定（`docs/14` §14.6.4「生日通告」）的**纯单测**——**不继承 `IntegrationTestBase`**，
 * 不起 Spring（照 `LoginThrottleTests` 的先例）。
 * <p>
 * ⚠️ **为什么必须单独单测**：「2 月 29 日生日的人在平年按 2-28 通告」这条**只在特定的运行日才成立**，
 * 而集成测试跑在哪天是固定的、改不了 ⇒ 靠它永远覆盖不到那一支。
 * 所以规则被抽成纯函数 `BirthdayService.isBirthdayToday(birthday, today)`，把"今天"喂进去测。
 */
class BirthdayRuleTests {

    @Test
    void a_normal_date_matches_only_its_own_day() {
        LocalDate may6 = LocalDate.of(1999, 5, 6);
        assertTrue(BirthdayService.isBirthdayToday(may6, LocalDate.of(2026, 5, 6)));
        assertFalse(BirthdayService.isBirthdayToday(may6, LocalDate.of(2026, 5, 7)), "次日不算");
        assertFalse(BirthdayService.isBirthdayToday(may6, LocalDate.of(2026, 4, 6)), "同日的别的月不算");
    }

    @Test
    void feb29_fires_on_feb29_of_a_leap_year() {
        LocalDate feb29 = LocalDate.of(2000, 2, 29);
        assertTrue(BirthdayService.isBirthdayToday(feb29, LocalDate.of(2024, 2, 29)));
    }

    @Test
    void feb29_falls_back_to_feb28_in_a_common_year() {
        LocalDate feb29 = LocalDate.of(2000, 2, 29);
        assertTrue(BirthdayService.isBirthdayToday(feb29, LocalDate.of(2026, 2, 28)),
                "平年的 2-28 要通告——否则他们四年才有一次");
    }

    @Test
    void feb29_does_not_fire_on_feb28_of_a_leap_year() {
        // ⚠️ 这条最容易写错：闰年他们有真正的 2-29 可过，2-28 不该提前通告
        LocalDate feb29 = LocalDate.of(2000, 2, 29);
        assertFalse(BirthdayService.isBirthdayToday(feb29, LocalDate.of(2024, 2, 28)));
    }

    @Test
    void feb28_fires_on_feb28_in_both_kinds_of_year() {
        LocalDate feb28 = LocalDate.of(1999, 2, 28);
        assertTrue(BirthdayService.isBirthdayToday(feb28, LocalDate.of(2026, 2, 28)), "平年");
        assertTrue(BirthdayService.isBirthdayToday(feb28, LocalDate.of(2024, 2, 28)), "闰年");
    }

    @Test
    void null_birthday_is_never_a_birthday() {
        assertFalse(BirthdayService.isBirthdayToday(null, LocalDate.of(2026, 5, 6)));
    }
}
