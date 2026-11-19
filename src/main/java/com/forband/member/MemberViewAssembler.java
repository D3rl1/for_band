package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.member.dto.MemberView;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 名册 / 详情视图的**唯一生产点**：可见性全在这里裁（`docs/03` §3.4 那张「`MemberView` 各字段的可见性表」）。
 * <p>
 * ⚠️ **两处口径是用户 2026-09-25 当场改的，与那张表相反**（见 `交接单.md` `#42` 的移交事项）：
 * `contact` **对全团可见**（"填了就说明不担心"）、`account` **维持可见**（"就是姓名拼音"）。
 */
@Component
public class MemberViewAssembler {

    /** 「对外只到月-日 / 只到年份」用的两个格式（本人与管理员走 `ISO_LOCAL_DATE` 全值）。 */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter YEAR_ONLY = DateTimeFormatter.ofPattern("yyyy");

    private final PermissionService permissionService;

    public MemberViewAssembler(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public MemberView toView(Member member, CurrentUser viewer, Map<Long, String> sectionNames) {
        boolean isAdmin = viewer.role() == Role.ADMIN;
        // 本人看自己 = 看自己的资料，姓名 / 学号 / 生日都给全值
        boolean fullValue = isAdmin || viewer.memberId() == member.getId();
        return new MemberView(member.getId(), member.getName(),
                // 学号与生日同级隐私（T17）：本人 + 管理员
                fullValue ? member.getStudentNo() : null,
                member.getSectionId(),
                member.getSectionId() == null ? null : sectionNames.get(member.getSectionId()),
                member.getInstrument(),
                permissionService.canViewPhone(viewer) ? member.getPhone() : null,
                member.getRole(), member.getStatus(), member.getAccount(),
                // activated（= 还在用初始密码）/ 荣誉有效期 / 最后登录：账号级信息，仅管理员
                // （界面上也只有管理端用得到；给别人 null，别用 false 冒充"未激活"）
                isAdmin ? Boolean.valueOf(member.getInitialPassword() == null) : null,
                dateText(member.getJoinedAt(), fullValue),
                dateText(member.getLeftAt(), fullValue),
                isAdmin ? member.getHonoraryUntil() : null,
                isAdmin ? member.getLastLoginAt() : null,
                isAdmin ? member.getNote() : null,
                member.getAvatar(), member.getAvatarFrame(), member.getIntro(), member.getContact(),
                birthdayText(member.getBirthday(), fullValue));
    }

    /**
     * 生日：本人 / 管理员看全值，**别人只到「月-日」、不显示年份**（`docs/03` §3.4 + `docs/13` §13.3②；
     * 用户 2026-09-25 拍板"按规格实现"，此前实现比规格更保守、对外一律 null，见 `交接单.md` `#8`）。
     */
    private static String birthdayText(LocalDate birthday, boolean fullValue) {
        if (birthday == null) {
            return null;
        }
        return birthday.format(fullValue ? DateTimeFormatter.ISO_LOCAL_DATE : MONTH_DAY);
    }

    /**
     * 入团 / 退团时间：本人与管理员看全值（管理端的日期输入要完整值才能编辑），**别人只到年份**（`docs/03` §3.4）。
     */
    private static String dateText(LocalDate date, boolean fullValue) {
        if (date == null) {
            return null;
        }
        return date.format(fullValue ? DateTimeFormatter.ISO_LOCAL_DATE : YEAR_ONLY);
    }
}
