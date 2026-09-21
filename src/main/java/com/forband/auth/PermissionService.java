package com.forband.auth;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.Member;
import org.springframework.stereotype.Service;

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
     * 代签资格（docs/05）：管理员/团长可代全团；声部长仅限本声部；
     * 指挥与团员不可代签。目标团员 must 非 null。
     */
    public void checkCanMarkFor(CurrentUser marker, Member target) {
        switch (marker.role()) {
            case ADMIN, LEADER -> {
            }
            case SECTION_LEADER -> checkSameSection(marker, target.getSectionId());
            default -> throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }
}
