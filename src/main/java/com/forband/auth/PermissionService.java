package com.forband.auth;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 全部权限判定的唯一入口（DECISIONS D16）。绝不散落 if(role==ADMIN)——
 * 角色定义会改（暂定项 T1/T8），散着写必然漏改。
 */
@Service
public class PermissionService {

    /** value 为空数组时表示"任何已登录角色"。 */
    public boolean hasAnyRole(CurrentUser user, Role... roles) {
        if (roles.length == 0) {
            return true;
        }
        for (Role role : roles) {
            if (role == user.role()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 登录资格（docs/01，2026-09-23 改写）：status = ACTIVE，或（role = HONORARY
     * 且 honorary_until 未过期；为空 = 不可登录，fail-closed）。过期只挡登录，数据照留。
     */
    public boolean canLogin(Member member) {
        if (MemberStatus.ACTIVE.equals(member.getStatus())) {
            return true;
        }
        return Role.HONORARY.name().equals(member.getRole())
                && member.getHonoraryUntil() != null
                && !member.getHonoraryUntil().isBefore(LocalDate.now());
    }

    public void checkRole(CurrentUser user, Role... roles) {
        if (!hasAnyRole(user, roles)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }

    public void checkSelf(CurrentUser user, long memberId) {
        if (user.memberId() != memberId) {
            throw new BusinessException(ErrorCode.NOT_SELF);
        }
    }

    /**
     * 在团成员专属动作（申请 / 撤回请假等，docs/02 §2.3 + docs/13 不变式 14）：
     * **不参与考勤的人不参与请假**——荣誉团员（只读）、指挥（不进应到名单）、
     * 系统管理员账号（is_system）都不能提/撤请假。
     */
    public void checkAttendeeAction(CurrentUser user) {
        if (user.role() == Role.HONORARY || user.role() == Role.CONDUCTOR || user.system()) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }

    /**
     * 排除荣誉团员（docs/02 §2.3 末尾的防腐条款"新功能默认不给荣誉团员"）。
     * 用在"所有登录角色都能做、唯独荣誉团员不行"的动作上（如提反馈）——
     * ⚠️ `@RequireRole` **表达不了"排除某个角色"**（它只能列举允许的角色），所以必须在这里判。
     */
    public void checkNotHonorary(CurrentUser user) {
        if (user.role() == Role.HONORARY) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }

    /** sectionId 为 null（无声部）时仅 ADMIN 可通过。 */
    public void checkSameSection(CurrentUser user, Long sectionId) {
        if (user.role() == Role.ADMIN) {
            return;
        }
        if (sectionId == null || user.sectionId() == null || !user.sectionId().equals(sectionId)) {
            throw new BusinessException(ErrorCode.NOT_YOUR_SECTION);
        }
    }

    /** 手机号可见性（docs/03）：仅管理员 / 团长 / 指挥。 */
    public boolean canViewPhone(CurrentUser user) {
        return user.role() == Role.ADMIN || user.role() == Role.LEADER || user.role() == Role.CONDUCTOR;
    }

    /**
     * 代签资格（docs/02 §2.3 + docs/05 §5.3）：**只有管理员与团长**可代签任意团员，其余角色一律拒绝。
     * 声部长原本可代签本声部，2026-09-23 收窄——代签的真实用途是**修正数据**（补录、改错），
     * 没有正常场景需要"声部长替团员按一下"。
     */
    public void checkCanMarkFor(CurrentUser marker) {
        switch (marker.role()) {
            case ADMIN, LEADER -> {
            }
            default -> throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }

    /**
     * 请假待签行的签署权（docs/05 §5.5 边界情况，字面口径）：
     * SECTION_LEADER 行 → 申请人同声部的声部长；申请人本人就是声部长 → 团长；
     * LEADER 行 → 团长；申请人本人是团长 → **改由另一位在团团长签**（2026-09-25 前是一刀切拒绝，
     * 等于只有管理员能签，与规格不符）；管理员始终可签任何行（兜底声部无长）。
     */
    public boolean canDecideApproval(CurrentUser decider, Role requesterRole, long requesterId,
                                     Long requesterSectionId, String requiredRole) {
        if (decider.role() == Role.ADMIN) {
            return true;
        }
        if (Role.SECTION_LEADER.name().equals(requiredRole)) {
            if (requesterRole == Role.SECTION_LEADER) {
                return decider.role() == Role.LEADER;
            }
            return decider.role() == Role.SECTION_LEADER
                    && decider.sectionId() != null
                    && decider.sectionId().equals(requesterSectionId);
        }
        if (Role.LEADER.name().equals(requiredRole)) {
            if (decider.role() != Role.LEADER) {
                return false;
            }
            if (requesterRole != Role.LEADER) {
                return true;
            }
            // 申请人也是团长（docs/05 §5.5「另一位在团团长签」）：要求换一个人签即可，
            // 这里**故意不去数"在团团长"有几个**——能在线的团长必然是在团团长
            // （AuthInterceptor 每个请求都过一遍 canLogin，非 ACTIVE 的会被 revokeAllFor + 403），
            // 所以"只有一位在团团长"时，唯一可能的团长审批人就是申请人本人，被这句挡掉，
            // 只能落到本方法开头 ADMIN 那条 —— 正是规格说的「管理员兜底」。两者行为等价。
            return decider.memberId() != requesterId;
        }
        return false;
    }

    public void checkCanDecideApproval(CurrentUser decider, Role requesterRole, long requesterId,
                                       Long requesterSectionId, String requiredRole) {
        if (!canDecideApproval(decider, requesterRole, requesterId, requesterSectionId, requiredRole)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }
}
