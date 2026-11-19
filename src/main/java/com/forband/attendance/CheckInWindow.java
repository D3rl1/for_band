package com.forband.attendance;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.event.Event;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/** 签到窗口（`docs/04`，可用性规则而非判罚规则，D17）：开始前 30 分钟开，结束后 2 小时关。 */
@Component
public class CheckInWindow {

    private static final Duration OPEN_BEFORE = Duration.ofMinutes(30);
    private static final Duration CLOSE_AFTER = Duration.ofHours(2);

    public void assertOpen(Event event) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = event.getDate().atTime(event.getStartTime());
        LocalDateTime end = event.getDate().atTime(event.getEndTime());
        if (now.isBefore(start.minus(OPEN_BEFORE)) || now.isAfter(end.plus(CLOSE_AFTER))) {
            throw new BusinessException(ErrorCode.NOT_IN_WINDOW);
        }
    }
}
