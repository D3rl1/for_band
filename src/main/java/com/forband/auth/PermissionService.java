package com.forband.auth;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberStatus;
import com.forband.member.MemberStatusHistoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 全部权限判定的唯一入口（DECISIONS D16）。绝不散落 if(role==ADMIN)——
 * 角色定义会改（暂定项 T1/T8），散着写必然漏改。
 */
@Service
public class PermissionService {

    private final MemberMapper memberMapper;
    private final MemberStatusHistoryService statusHistory;

    public PermissionService(MemberMapper memberMapper, MemberStatusHistoryService statusHistory) {
        this.memberMapper = memberMapper;
        this.statusHistory = statusHistory;
    }

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
     * 登录资格（docs/01 §1.4）：ACTIVE / PAUSED 按原角色登录；
     * 荣誉身份必须毕业且有效期非空、未过期，不能借异常状态绕过有效期。
     */
    public boolean canLogin(Member member) {
        if (member == null) {
            return false;
        }
        if (Role.HONORARY.name().equals(member.getRole())) {
            return MemberStatus.GRADUATED.equals(member.getStatus())
                    && member.getHonoraryUntil() != null
                    && !member.getHonoraryUntil().isBefore(LocalDate.now());
        }
        return MemberStatus.ACTIVE.equals(member.getStatus())
                || MemberStatus.PAUSED.equals(member.getStatus());
    }

    /** 当前本人出勤资格；历史活动日期仍须由应到名单解析器校验。 */
    public boolean isCurrentAttendee(Member member) {
        return member != null && MemberStatus.ACTIVE.equals(member.getStatus())
                && isAttendeeIdentity(member);
    }

    public boolean isAttendeeOn(Member member, LocalDate date) {
        return member != null
                && (MemberStatus.ACTIVE.equals(member.getStatus()) || MemberStatus.PAUSED.equals(member.getStatus()))
                && isAttendeeIdentity(member)
                && MemberStatus.ACTIVE.equals(statusHistory.statusAt(member, date));
    }

    private boolean isAttendeeIdentity(Member member) {
        return !Boolean.TRUE.equals(member.getIsSystem())
                && !Role.ADMIN.name().equals(member.getRole())
                && !Role.CONDUCTOR.name().equals(member.getRole())
                && !Role.HONORARY.name().equals(member.getRole());
    }

    /** 读取数据库最新状态，不能信任旧会话或调用者缓存的身份。 */
    public boolean isCurrentAttendee(long memberId) {
        return isCurrentAttendee(memberMapper.selectById(memberId));
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

    /** 不参与当前考勤的身份不能申请 / 撤回请假（docs/02 §2.3）。 */
    public void checkAttendeeAction(CurrentUser user) {
        if (!isCurrentAttendee(user.memberId())) {
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
     * 看全团考勤明细 / 出勤统计（docs/02 §2.3「看某场考勤明细」「看出勤率 / 统计」两行）。
     * <p>
     * ⚠️ **白名单**，不是「非 MEMBER / 非 HONORARY」的反向推导（#223）：
     * 反向写等于给未来新角色**默认放行**；这里新角色默认拒绝，加角色时必须回来显式登记。
     * 前端 `stats.html` 也读同一结论（`/api/auth/me` 的 `canViewAllAttendance`），不再自己比角色。
     */
    public boolean canViewAllAttendance(CurrentUser user) {
        return switch (user.role()) {
            case ADMIN, LEADER, CONDUCTOR, SECTION_LEADER -> true;
            default -> false;
        };
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
     * LEADER 行 → 团长；申请人本人是团长 → **改由另一位可登录的现任团长签**（2026-09-25 前是一刀切拒绝，
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
            // 可登录的现任团长（含休团）仍可审批他人；本人不能自审。
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
