package com.forband.recording;

import com.forband.auth.CurrentUser;
import com.forband.auth.Role;
import org.springframework.stereotype.Component;

/**
 * **录音可见性的唯一判定**（docs/08 §8.4 + docs/02 §2.3）。全批只有这一处规则，
 * 被四处复用：列表 / 详情 / 点评 / **文件流**（`RecordingFileAccessRule`）。
 * <p>
 * ⚠️ **列举"谁能看"，不列举"谁不能看"**——`LeaveService.list` 那次越权就是反面教材：
 * 写"列举谁不能看"，新加的角色（当时是荣誉团员）就静默落进"能看"那一支。
 * <p>
 * 规则（docs/08 §8.4）：管理员 / 团长 / 指挥看**全部**；声部长看**本声部 + 自己**；
 * 其余（团员 / 荣誉团员 / 将来新增的角色）看**自己的**。
 */
@Component
public class RecordingVisibility {

    /** 能看全部的角色（**显式列举**，别写成"不是声部长就是管理员"那种默认放行）。 */
    public boolean seesAll(CurrentUser viewer) {
        return viewer.role() == Role.ADMIN || viewer.role() == Role.LEADER
                || viewer.role() == Role.CONDUCTOR;
    }

    /**
     * 这条录音 viewer 能不能看/听。
     * 点评权限**直接复用这一条**（docs/08 §8.4 的"双向"要求团员能追问自己的录音；
     * `docs/02` 矩阵里团员那格 ❌ 读作"不能点评**别人的**"——两处口径已记进移交请规格统一）。
     */
    public boolean canView(Recording row, CurrentUser viewer) {
        if (row == null) {
            return false;
        }
        if (seesAll(viewer)) {
            return true;
        }
        if (row.getMemberId() != null && row.getMemberId() == viewer.memberId()) {
            return true;   // 自己的（荣誉团员也走这条：他们只看得到自己发过的）
        }
        return viewer.role() == Role.SECTION_LEADER
                && row.getSectionId() != null
                && row.getSectionId().equals(viewer.sectionId());   // 本声部
    }

    /**
     * 这条录音 viewer 能不能删（docs/02 §2.3：管理员 / 团长 / 指挥删任意，声部长删本声部，团员删自己的）。
     * ⚠️ `docs/08` §8.3 写的是"本人 + 本声部长 + 管理员"（少了团长 / 指挥）——按 `02` 实现（它自称权威），
     * 冲突已记进移交。
     */
    public boolean canDelete(Recording row, CurrentUser viewer) {
        if (row == null) {
            return false;
        }
        if (seesAll(viewer)) {
            return true;
        }
        if (row.getMemberId() != null && row.getMemberId() == viewer.memberId()) {
            return true;
        }
        return viewer.role() == Role.SECTION_LEADER
                && row.getSectionId() != null
                && row.getSectionId().equals(viewer.sectionId());
    }
}
