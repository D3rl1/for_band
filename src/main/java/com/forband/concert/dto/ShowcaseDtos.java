package com.forband.concert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 「我们的专场」专页（`docs/17` §17.3 的接口清单）。
 * ⚠️ **不新增错误码**：权限 `30001`、字段校验 `10001`、找不到 404 —— 全复用（`docs/17` §17.3 结尾明写）。
 */
public final class ShowcaseDtos {

    private ShowcaseDtos() {
    }

    /** 时间线的一个节点 = **一个年份**（`docs/17` §17.4「时间线的排法」）。封面 = 该年排序第一张。 */
    public record TimelineNode(int year, int photoCount, long coverFileId) {
    }

    /**
     * 某一年的一张策展图（`docs/17` §17.3：按「演出日期 → `sort_no`」排）。
     * `concertId` 是给**策展管理页**用的：它按年取一次、再筛出"正在策展的这一场"，
     * 免得为它单开一条"读某场的策展图"的端点（规格的端点清单就 6 条，别加）。
     */
    public record YearPhoto(long photoId, long concertId, long fileId, String note, int sortNo) {
    }

    /** 开一个新时间点：建一场 `is_showcase=1` 的演出，只要名称 + 日期 + 可选地点。 */
    public record OpenRequest(@NotBlank(message = "演出名不能为空") @Size(max = 64) String name,
                              @NotNull(message = "日期不能为空") LocalDate date,
                              @Size(max = 64) String venue) {
    }

    /** 开新时间点的结果（够前端跳转 / 刷新列表用）。 */
    public record OpenResult(long id, String name, LocalDate date, String venue, boolean isShowcase) {
    }

    /** 设为 / 取消专场：**必须双向**（`docs/17` §17.3）。 */
    public record ToggleRequest(@NotNull(message = "isShowcase 不能为空") Boolean isShowcase) {
    }

    /** 加一张策展图：`sort_no` 由服务端**追加到末尾**（不要求前端算）。`note` 上限 2000 字。 */
    public record AddPhotoRequest(@NotNull(message = "fileId 不能为空") Long fileId,
                                  @Size(max = 2000, message = "策展文案最多 2000 字") String note) {
    }

    /** 整批设顺序：按数组顺序即最终顺序（**whole-replace**，照 `07` 的 `PUT .../program` 先例）。 */
    public record OrderRequest(@NotNull(message = "photoIds 不能为空") List<Long> photoIds) {
    }

    /** 改这一张的文案。 */
    public record NoteRequest(@Size(max = 2000, message = "策展文案最多 2000 字") String note) {
    }

    /** 「从已有的里挑」：这场演出**晒图墙上已经有的**照片（`docs/17` §17.5）。⚠️ 只读，不改 `concert_photo`。 */
    public record CandidateRow(long photoId, long fileId, String caption) {
    }

    /** 一场专场里的一行策展图（管理页用：挑完 / 加完 / 排完都回这一份）。 */
    public record ShowcasePhotoRow(long photoId, long fileId, int sortNo, String note) {
    }

    /**
     * 「**参演过 N 场专场**」（`docs/14` §14.6.4「个人页」第 6 条，`交接单 #117`）。
     * ⚠️ **别人也能看**（团员主页）⇒ 这里**只有这一个数**，不许顺手把出勤率之类搬进来。
     * ⚠️ **0 就回 0**（不是 404 / 不是 null）——"一场没参演过"是个**事实**。
     */
    public record MemberShowcaseCount(long count) {
    }
}
