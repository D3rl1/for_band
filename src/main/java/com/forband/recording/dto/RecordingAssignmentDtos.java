package com.forband.recording.dto;

import com.forband.recording.dto.RecordingDtos.RecordingRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 本周录音作业与录音管理视图（docs/08 §8.3「GET /manage 返回形状」）。
 * <p>
 * `state` 只有三种非空值：`MISS`（一个作业都没关联）/ `PARTIAL`（交了一部分）/ `DONE`（本周作业交齐）。
 * 历史周与「该声部本周没布置作业」的成员一律 `null` —— 前端据此区分"没交"和"不适用"。
 */
public final class RecordingAssignmentDtos {

    private RecordingAssignmentDtos() {
    }

    /** 布置作业：粒度是声部；`pieceId` 为空 = 基本功（如「单跳」「长弓」）。 */
    public record CreateAssignmentRequest(@NotNull Long sectionId,
                                          @NotBlank @Size(max = 64) String title,
                                          Long pieceId) {
    }

    /** 修改作业：**已有提交时服务层只接受 title 变化**（pieceId 必须原样带回）。 */
    public record UpdateAssignmentRequest(@NotBlank @Size(max = 64) String title, Long pieceId) {
    }

    /** 管理视图顶部的一条作业。`canDelete=false` 表示已有人提交，前端删除键置灰。 */
    public record AssignmentView(long id, Long sectionId, String sectionName, String title,
                                 Long pieceId, String pieceTitle, int submittedCount,
                                 boolean canDelete) {
    }

    /** 上传表单的下拉项；`linked` = 本人本周已有一条录音关联过它（默认不选已关联的）。 */
    public record CurrentAssignmentView(long id, String title, Long pieceId, String pieceTitle,
                                        boolean linked) {
    }

    /**
     * 管理视图里的一个应录成员。当前周：`sections` 含全部应录成员（未交者 `recordings=[]`，
     * 前端靠它生成顶部未交名单）；历史周：只含当周有录音的人。
     */
    public record ManageMemberView(long memberId, String name, String state, int doneCount,
                                   int totalCount, int unlinkedCount,
                                   List<RecordingRow> recordings) {
    }

    public record ManageSectionView(Long sectionId, String sectionName,
                                    List<ManageMemberView> members) {
    }

    public record ManageView(LocalDate weekStart, LocalDate weekEnd, boolean current,
                             List<AssignmentView> assignments,
                             List<ManageSectionView> sections) {
    }

    /** 历史周列表一行：`count` = 该周录音条数；`sectionCount` = 涉及声部数。 */
    public record WeekView(LocalDate weekStart, LocalDate weekEnd, long count, int sectionCount) {
    }
}
