package com.forband.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 纪念头像发放的请求 / 响应（`docs/02` §2.4、#266）。
 * <p>
 * 发放是**整批成功或整批失败**：名单里有一个不存在的成员就整单 422，
 * 不返回"部分成功"——否则前端会显示一条一半成功的记录，对账最难。
 */
public final class AvatarGrantDtos {

    private AvatarGrantDtos() {
    }

    /** 一次发放：一张限定头像 → 一批成员。{@code batch} / {@code note} 可空。 */
    public record GrantRequest(
            @NotBlank @Size(max = 32) String avatarId,
            @NotEmpty List<Long> memberIds,
            @Size(max = 64) String batch,
            @Size(max = 200) String note) {
    }

    /** 发放结果：新发放 / 已有效跳过 / 撤回后重新激活，三组互斥。 */
    public record GrantResult(List<Long> granted, List<Long> skipped, List<Long> reactivated) {
    }

    /** 撤回结果：{@code avatarCleared} = 该成员正戴着这张头像，已回退成姓名首字。 */
    public record RevokeResult(boolean avatarCleared) {
    }

    /**
     * 本人当前有效的一条限定头像资格（`GET /api/me/avatar-grants`）：
     * 前端据此过滤头像网格，并在选中区显示「纪念限定 + 批次名」。
     * 只回**使用资格**需要的信息，不带发放人 / 理由等管理端字段。
     */
    public record GrantInfo(String avatarId, String batch) {
    }

    /** 发放记录的一行（含已撤回的，供核对与补发）。 */
    public record GrantRow(long memberId, String memberName, String account,
                           String avatarId, String batch, String note,
                           Long grantedBy, String grantedByName, LocalDateTime grantedAt,
                           LocalDateTime revokedAt, String revokeReason) {
    }
}
