package com.forband.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 登录失败节流（docs/01）：按 ip+账号 组合计数——不按账号单独计数，
 * 防止恶意锁别人的号。10 分钟内失败 ≥5 次 → 响应延迟约 3 秒并继续计数，永不锁号。
 * 内存实现，重启即清零——40 人量级可接受。
 * <p>
 * ⚠️ **key 必须跟着过期回收**：只清 deque 里的旧时间戳、不删 key 的话，
 * 每见一个新的 `ip|account` 组合就永久多一个 key（配合"客户端可伪造 X-Real-IP"就是无上限灌 key）。
 */
@Component
public class LoginThrottle {

    private static final int THRESHOLD = 5;
    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();
    private static final long DELAY_MS = 3000;

    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    /** 包可见：只为让"窗口过期后 key 会被回收"这件事可测（否则得 sleep 十分钟）。 */
    LoginThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    public void delayIfThrottled(String ip, String account) {
        Deque<Long> deque = failures.get(key(ip, account));
        if (deque == null) {
            return;
        }
        synchronized (deque) {
            prune(deque);
            if (deque.size() >= THRESHOLD) {
                try {
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void recordFailure(String ip, String account) {
        evictStale();
        Deque<Long> deque = failures.computeIfAbsent(key(ip, account), k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque);
            deque.addLast(clock.getAsLong());
        }
    }

    private void prune(Deque<Long> deque) {
        long cutoff = clock.getAsLong() - WINDOW_MS;
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
            deque.pollFirst();
        }
    }

    /**
     * 顺带把已经过期的 key 也回收掉。失败登录本来就少，40 人量级整个 map 扫一遍可忽略；
     * 关键是让 map 的增长被"每一次失败登录"夹住，不至于无限攒 key。
     */
    private void evictStale() {
        for (Map.Entry<String, Deque<Long>> entry : failures.entrySet()) {
            Deque<Long> deque = entry.getValue();
            // 与 recordFailure 共用同一把锁：否则可能刚判空、就被并发塞进一条新失败
            synchronized (deque) {
                prune(deque);
                if (deque.isEmpty()) {
                    failures.remove(entry.getKey(), deque);   // 两参 remove：值没被换掉才删
                }
            }
        }
    }

    private String key(String ip, String account) {
        return ip + "|" + (account == null ? "" : account);
    }
}
