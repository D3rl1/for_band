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
     * 这条录音 viewer 能不能删：**管理员 / 本人 / 本声部长**
     * （`docs/02` §2.3 矩阵 + `docs/08` §8.3 字面：删除是不可逆动作，收窄到与录音直接相关的三方）。
     * <p>
     * ⚠️ **团长 / 指挥不能删任何人的录音**——他们照旧能"听全部"（{@link #canView} 没动），但**不删**。
     * 本方法的注释早先写的是"管理员 / 团长 / 指挥删任意"，引的是 `docs/02` 的**未同步旧值**；
     * 规格 2026-09-23 已把团长 / 指挥由 ✅ 改 ❌（`docs/02:104-107` 明写"口径以 `08` 为准"），
     * 代码 2026-09-26 才跟上（`交接单.md` `#57`）。
     * <p>
     * ⚠️ **别图省事写成 `seesAll(viewer)` 兜底**——那正是这个越权的成因（团长 / 指挥也走"能看全部"那一支）。
     */
    public boolean canDelete(Recording row, CurrentUser viewer) {
        if (row == null) {
            return false;
        }
        if (viewer.role() == Role.ADMIN) {
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
