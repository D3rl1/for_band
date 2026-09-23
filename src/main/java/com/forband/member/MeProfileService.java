package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 「个人页」的读写口子（docs/03 §3.3/§3.4）：**本人**、**绝不收 `memberId`**，
 * 可写字段只有 `avatar` / `intro` / `contact` / `birthday`。
 * <p>
 * ⚠️ **姓名 / 声部 / 乐器 / 角色 / 学号在这里一概改不动**——那些是**账号级**动作，收归管理员
 * （`docs/02` T8 + §2.5 的边界澄清：T8 管的是账号级动作，**不挡"本人写自己的资料字段"**，
 * 先例是 `PUT /api/alumni/{memberId}/message` 的「本人 / 管理员」）。
 */
@Service
public class MeProfileService {

    private final MemberMapper memberMapper;
    private final AvatarCatalog avatarCatalog;

    public MeProfileService(MemberMapper memberMapper, AvatarCatalog avatarCatalog) {
        this.memberMapper = memberMapper;
        this.avatarCatalog = avatarCatalog;
    }

    /**
     * 本人可写的四个字段：`null` = 这次不改（前端"只提交改动过的字段"）。
     * 生日要能**清空**，而 `null` 已经表示"不改"，所以清空单独用一个显式开关。
     */
    public record ProfileUpdate(String avatar, String intro, String contact, LocalDate birthday,
                                boolean clearBirthday) {
    }

    @Transactional
    public void update(CurrentUser user, ProfileUpdate request) {
        validateAvatar(request.avatar());
        Member update = new Member();
        update.setId(user.memberId());
        update.setAvatar(request.avatar());
        update.setIntro(request.intro());
        update.setContact(request.contact());
        update.setBirthday(request.birthday());
        memberMapper.updateById(update);   // updateById 忽略 null → 不传就不改

        if (request.clearBirthday()) {
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, user.memberId())
                    .set(Member::getBirthday, null));
        }
    }

    /** 头像白名单校验：**预置清单是唯一权威**（`null` = 不用头像，永远合法）。管理员那条路径也复用它。 */
    public void validateAvatar(String avatar) {
        if (!avatarCatalog.isAllowed(avatar)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "头像不在预置清单里");
        }
    }
}
