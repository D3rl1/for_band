package com.forband.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败节流（docs/01）的纯单测——本类**不继承 IntegrationTestBase**：`LoginThrottle` 是纯 Java 类，
 * `@Component` 只是个注解，没有 Spring 依赖。
 * <p>
 * 时间用可注入的时钟拨，别 sleep：窗口是 10 分钟。
 */
class LoginThrottleTests {

    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LoginThrottle throttle = new LoginThrottle(now::get);

    @Test
    void below_threshold_does_not_delay() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("10.0.0.1", "zhang3");
        }
        long start = System.nanoTime();
        throttle.delayIfThrottled("10.0.0.1", "zhang3");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // 4 次还没到阈值 5 → 不该有那 3 秒延迟（阈值本身由下一条用例守住语义）
        assertTrue(elapsedMs < 500, "没到阈值不该延迟，实际 " + elapsedMs + "ms");
    }

    @Test
    void stale_key_is_reclaimed_after_window() throws Exception {
        throttle.recordFailure("10.0.0.1", "zhang3");
        assertEquals(1, keyCount(), "刚记一次失败应当有一个 key");

        now.addAndGet(WINDOW_MS + 1);          // 把时钟拨出 10 分钟窗口
        throttle.recordFailure("10.0.0.2", "lisi");   // 任何一次新失败都会顺带扫一遍旧 key

        Map<?, ?> failures = failuresOf();
        assertEquals(1, failures.size(), "过期的 key 必须被回收，否则见一个新组合就永久多一个 key");
        assertTrue(failures.containsKey("10.0.0.2|lisi"), "新 key 要在");
    }

    @Test
    void fresh_key_is_kept() throws Exception {
        throttle.recordFailure("10.0.0.1", "zhang3");
        throttle.recordFailure("10.0.0.2", "lisi");   // 同一窗口内，另一个 key 的失败不该清掉别人

        assertEquals(2, keyCount(), "窗口内的 key 不能被误清");
    }

    private int keyCount() throws Exception {
        return failuresOf().size();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> failuresOf() throws Exception {
        Field field = LoginThrottle.class.getDeclaredField("failures");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(throttle);
    }
}
