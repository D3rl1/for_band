package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.attendance.Attendance;
import com.forband.attendance.AttendanceMapper;
import com.forband.attendance.AttendanceStatus;
import com.forband.auth.CurrentUser;
import com.forband.event.Event;
import com.forband.event.EventMapper;
import com.forband.leave.LeaveRequest;
import com.forband.leave.LeaveRequestMapper;
import com.forband.leave.LeaveStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「个人栏」的数据口子（docs/05 §5.6）：最近流水 = 签到 + 请假按时间倒序合并。
 * 只回本人数据、不接受 memberId 参数（避免越权）；?limit 默认 5、上限 20。
 * 录音（期 5）落地后并入第三类。
 */
@Service
public class MeService {

    public record RecentActivityItem(String type, LocalDateTime time, long eventId,
                                     String title, String note) {
    }

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final AttendanceMapper attendanceMapper;
    private final LeaveRequestMapper requestMapper;
    private final EventMapper eventMapper;

    public MeService(AttendanceMapper attendanceMapper, LeaveRequestMapper requestMapper,
                     EventMapper eventMapper) {
        this.attendanceMapper = attendanceMapper;
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
    }

    public List<RecentActivityItem> recentActivity(CurrentUser user, int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        List<RecentActivityItem> items = new ArrayList<>();
        // 取 top-N 走分页插件（生成的 LIMIT 是绑定参数）——不拼 SQL 片段，见 SECURITY.md C1
        // attendance 只取 PRESENT 行：LEAVE 行是请假批准的后果，与 leave_request 重复——请假以申请单为准
        for (Attendance a : attendanceMapper.selectPage(new Page<>(1, n, false),
                new LambdaQueryWrapper<Attendance>()
                        .eq(Attendance::getMemberId, user.memberId())
                        .eq(Attendance::getStatus, AttendanceStatus.PRESENT)
                        .orderByDesc(Attendance::getMarkedAt)).getRecords()) {
            items.add(new RecentActivityItem("CHECKIN", a.getMarkedAt(), a.getEventId(), null, "出勤"));
        }
        for (LeaveRequest r : requestMapper.selectPage(new Page<>(1, n, false),
                new LambdaQueryWrapper<LeaveRequest>()
                        .eq(LeaveRequest::getMemberId, user.memberId())
                        .orderByDesc(LeaveRequest::getAppliedAt)).getRecords()) {
            items.add(new RecentActivityItem("LEAVE", r.getAppliedAt(), r.getEventId(), null,
                    leaveNote(r.getStatus())));
        }

        Map<Long, Event> events = items.isEmpty() ? Map.of()
                : eventMapper.selectBatchIds(
                        items.stream().map(RecentActivityItem::eventId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(Event::getId, Function.identity()));

        return items.stream()
                .map(i -> new RecentActivityItem(i.type(), i.time(), i.eventId(),
                        events.containsKey(i.eventId()) ? events.get(i.eventId()).getTitle() : null,
                        i.note()))
                .sorted(Comparator.comparing(RecentActivityItem::time).reversed())
                .limit(n)
                .toList();
    }

    private static String leaveNote(String status) {
        if (LeaveStatus.PENDING.equals(status)) {
            return "请假 · 待审批";
        }
        if (LeaveStatus.APPROVED.equals(status)) {
            return "请假 · 已批准";
        }
        if (LeaveStatus.REJECTED.equals(status)) {
            return "请假 · 已驳回";
        }
        return "请假 · 已撤销";
    }
}
