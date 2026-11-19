package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 「个人页」的读写口子（docs/03 §3.3/§3.4）：**本人**、**绝不收 `memberId`**，
 * 可写字段只有 `avatar` / `intro` / `contact` / `birthday`。
 * <p>
 * ⚠️ **荣誉团员只读**：`docs/02` §2.3 的防腐条款把"改自己的资料（含头像）"判为 ❌，
 * 所以 `update()` 第一件事就是 `checkNotHonorary`（2026-09-24 拍板维持 ❌，2026-09-25 落地）。
 * <p>
 * ⚠️ **姓名 / 声部 / 乐器 / 角色 / 学号在这里一概改不动**——那些是**账号级**动作，收归管理员
 * （`docs/02` T8 + §2.5 的边界澄清：T8 管的是账号级动作，**不挡"本人写自己的资料字段"**，
 * 先例是 `PUT /api/alumni/{memberId}/message` 的「本人 / 管理员」）。
 */
@Service
public class MeProfileService {

    private final MemberMapper memberMapper;
    private final AvatarCatalog avatarCatalog;
    private final AvatarFrameCatalog avatarFrameCatalog;
    private final AvatarGrantService avatarGrantService;
    private final PermissionService permissionService;

    public MeProfileService(MemberMapper memberMapper, AvatarCatalog avatarCatalog,
                            AvatarFrameCatalog avatarFrameCatalog,
                            AvatarGrantService avatarGrantService,
                            PermissionService permissionService) {
        this.memberMapper = memberMapper;
        this.avatarCatalog = avatarCatalog;
        this.avatarFrameCatalog = avatarFrameCatalog;
        this.avatarGrantService = avatarGrantService;
        this.permissionService = permissionService;
    }

    /**
     * 本人可写的四个字段：`null` = 这次不改（前端"只提交改动过的字段"）。
     * 生日 / 头像框要能**清空**，而 `null` 已经表示"不改"，所以清空各自用显式开关。
     */
    public record ProfileUpdate(String avatar, String avatarFrame, String intro, String contact,
                                LocalDate birthday, boolean clearBirthday,
                                boolean clearAvatarFrame) {
    }

    @Transactional
    public void update(CurrentUser user, ProfileUpdate request) {
        // 先鉴权后校验：荣誉团员必须拿到 403，而不是"字段没填对"的 422
        permissionService.checkNotHonorary(user);
        validateAvatar(request.avatar(), user.memberId());
        validateAvatarFrame(request.avatarFrame());
        Member update = new Member();
        update.setId(user.memberId());
        update.setAvatar(request.avatar());
        update.setAvatarFrame(request.avatarFrame());
        update.setIntro(request.intro());
        update.setContact(request.contact());
        update.setBirthday(request.birthday());
        memberMapper.updateById(update);   // updateById 忽略 null → 不传就不改

        if (request.clearBirthday()) {
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, user.memberId())
                    .set(Member::getBirthday, null));
        }
        if (request.clearAvatarFrame()) {
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, user.memberId())
                    .set(Member::getAvatarFrame, null));
        }
    }

    /**
     * 头像校验：**预置清单是唯一权威**（`null` = 不用头像，永远合法）。
     * <p>
     * 两道闸门（#266）：在清单里 ∧（公共 ∨ 目标成员有未撤回的发放资格）。
     * ⚠️ {@code memberId} 必须是**目标成员**——管理员代改别人头像时不能拿管理员自己的资格顶替。
     */
    public void validateAvatar(String avatar, long memberId) {
        if (!avatarCatalog.isKnown(avatar)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "头像不在预置清单里");
        }
        if (!avatarCatalog.isPublic(avatar) && !avatarGrantService.hasGrant(memberId, avatar)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "这张头像是限定头像，需要先获得发放资格");
        }
    }

    /** 头像框白名单校验：同上，`null` = 不用框。 */
    public void validateAvatarFrame(String avatarFrame) {
        if (!avatarFrameCatalog.isAllowed(avatarFrame)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "头像框不在预置清单里");
        }
    }
}
