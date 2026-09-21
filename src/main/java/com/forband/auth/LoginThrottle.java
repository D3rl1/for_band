package com.forband.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败节流（docs/01）：按 ip+账号 组合计数——不按账号单独计数，
 * 防止恶意锁别人的号。10 分钟内失败 ≥5 次 → 响应延迟约 3 秒并继续计数，永不锁号。
 * 内存实现，重启即清零——40 人量级可接受。
 */
@Component
public class LoginThrottle {

    private static final int THRESHOLD = 5;
    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();
    private static final long DELAY_MS = 3000;

    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

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
        Deque<Long> deque = failures.computeIfAbsent(key(ip, account), k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque);
            deque.addLast(System.currentTimeMillis());
        }
    }

    public void clear(String ip, String account) {
        failures.remove(key(ip, account));
    }

    private void prune(Deque<Long> deque) {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
            deque.pollFirst();
        }
    }

    private String key(String ip, String account) {
        return ip + "|" + (account == null ? "" : account);
    }
}
