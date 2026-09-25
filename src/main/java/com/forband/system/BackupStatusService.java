package com.forband.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 备份状态（docs/12 §12.5「⚡ 备份成败怎么让应用知道」）。
 * <p>
 * **职责单一：脚本写、应用只读。** 应用绝不发起备份、也绝不写这个文件——
 * 那不是"顺手多做一点"，而是把"库挂了就写不进去"这个真实故障重新引进来
 * （规格选状态文件而不是回调 API，正是为了"库挂了的时候这条信息还写得下"）。
 * <p>
 * ⚠️ **读不出来一律按"未知"处理，绝不抛异常**——状态显示本身不能变成一个新故障：
 * 文件不存在（脚本还没跑过）、权限不对、JSON 半截（脚本没按原子写）都算未知。
 * 这几个分支都**不是** 500，页面会把未知当异常提示（见 `logs.html`）。
 */
@Service
public class BackupStatusService {

    private static final Logger log = LoggerFactory.getLogger(BackupStatusService.class);

    private final Path statusFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BackupStatusService(@Value("${band.backup.status-file}") String statusFile) {
        this.statusFile = Path.of(statusFile).toAbsolutePath().normalize();
    }

    /**
     * 状态文件的四个字段**原样返回**（不做二次格式化），另附应用算出来的 `staleHours`。
     *
     * @param known         状态文件读得到且内容可用；`false` 时其余字段全是 null = 未知
     * @param ok            本次备份是否成功（来自文件）
     * @param lastAttemptAt 本次开始时间（ISO 8601，带时区）
     * @param lastSuccessAt 最近一次成功的时间；**本次失败时脚本会保留上次的值，所以它可能比 lastAttemptAt 早**
     * @param message       失败原因（脚本截到 200 字）
     * @param staleHours    距上次成功的小时数（保留一位小数）；从没成功过 → null
     */
    public record Status(boolean known, Boolean ok, String lastAttemptAt,
                         String lastSuccessAt, String message, Double staleHours) {

        static Status unknown() {
            return new Status(false, null, null, null, null, null);
        }
    }

    public Status read() {
        String raw;
        try {
            raw = Files.readString(statusFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            // 脚本还没跑过（首次部署当天就是这个状态）。**不打日志**：这不是异常，页面会显示"未知"。
            return Status.unknown();
        } catch (IOException e) {
            log.warn("备份状态文件读不出来，按未知处理：{}", statusFile, e);
            return Status.unknown();
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (IOException e) {
            log.warn("备份状态文件不是合法 JSON，按未知处理：{}", statusFile, e);
            return Status.unknown();
        }
        if (node == null || !node.isObject() || !node.hasNonNull("ok") || !node.get("ok").isBoolean()) {
            log.warn("备份状态文件缺少 ok 字段，按未知处理：{}", statusFile);
            return Status.unknown();
        }

        String lastAttemptAt = text(node, "last_attempt_at");
        String lastSuccessAt = text(node, "last_success_at");
        OffsetDateTime lastSuccess;
        try {
            lastSuccess = lastSuccessAt == null ? null : parseIso(lastSuccessAt);
        } catch (DateTimeParseException e) {
            // ⚠️ **时间戳读不动就整体按未知**，不能"ok 照给、staleHours 给 null"——
            // 那样页面会因为"last_success_at 不为空"而不标红，把"时间戳坏了"这件异常静默掉。
            log.warn("备份状态文件的 last_success_at 不是 ISO 8601 时间，按未知处理：{}", statusFile);
            return Status.unknown();
        }

        return new Status(true, node.get("ok").booleanValue(), lastAttemptAt, lastSuccessAt,
                text(node, "message"), staleHours(lastSuccess));
    }

    /**
     * ⚠️ ISO 8601 的时区**两种写法都合法**（`+08:00` 与 `+0800`），而 `OffsetDateTime.parse`
     * 只认带冒号的那种——实测 `2026-09-25T20:32:34+0800`（`date +%Y-%m-%dT%H:%M:%S%z` 的产物，
     * 也就是备份脚本最可能写出来的形状）会直接抛 `DateTimeParseException`。
     * 那会让页面**永远显示"未知"**、而日志里只有一句含糊的 warn——正好落进这条规格要防的静默失败，
     * 所以这里先把紧凑写法补上冒号再解析。
     */
    private static OffsetDateTime parseIso(String text) {
        return OffsetDateTime.parse(text.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2"));
    }

    private static Double staleHours(OffsetDateTime lastSuccess) {
        if (lastSuccess == null) {
            return null;   // 从没成功过 —— 页面照样要标红，判据在页面那侧
        }
        long minutes = Duration.between(lastSuccess, OffsetDateTime.now()).toMinutes();
        return Math.round(minutes / 6.0) / 10.0;   // 分钟 → 小时，保留一位小数
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }
}
