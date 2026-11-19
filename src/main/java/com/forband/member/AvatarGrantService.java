package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.member.dto.AvatarGrantDtos.GrantInfo;
import com.forband.member.dto.AvatarGrantDtos.GrantResult;
import com.forband.member.dto.AvatarGrantDtos.GrantRow;
import com.forband.member.dto.AvatarGrantDtos.RevokeResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纪念头像发放（`docs/02` §2.4、`docs/13` §13.3㉛、#266）。
 * <p>
 * 三条不能改的口径：
 * <ul>
 *   <li>**只有管理员能发 / 撤**——闸门在控制器 {@code @RequireRole(ADMIN)}，团长不因能看团员资料而获得此权；</li>
 *   <li>**永久**：任何代码都不许因离团 / 停用 / 改角色 / 荣誉到期删行或置无效；不设到期日；</li>
 *   <li>**撤回只用于纠错**：必须给理由，只写 {@code revoked_*}，不 DELETE；佩戴中撤回回退姓名首字。</li>
 * </ul>
 */
@Service
public class AvatarGrantService {

    private final AvatarGrantMapper avatarGrantMapper;
    private final MemberMapper memberMapper;
    private final AvatarCatalog avatarCatalog;

    public AvatarGrantService(AvatarGrantMapper avatarGrantMapper, MemberMapper memberMapper,
                              AvatarCatalog avatarCatalog) {
        this.avatarGrantMapper = avatarGrantMapper;
        this.memberMapper = memberMapper;
        this.avatarCatalog = avatarCatalog;
    }

    /**
     * 给一批成员发一张限定头像。整批成败一致：名单里有不存在的成员 → 整单 422。
     * 已有效的重复发放**跳过**（不产生第二行）；撤回过的**重新激活**同一行（不自动恢复，是显式再发）。
     */
    @Transactional
    public GrantResult grant(String avatarId, List<Long> memberIds, String batch, String note,
                             CurrentUser operator) {
        if (!avatarCatalog.isKnown(avatarId) || !avatarCatalog.isGrantOnly(avatarId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有清单里标记为限定（scope=grant）的头像才需要发放");
        }
        Set<Long> wanted = new LinkedHashSet<>(memberIds);
        List<Member> members = memberMapper.selectBatchIds(wanted);
        if (members.size() != wanted.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "名单里有不存在的成员");
        }

        LocalDateTime now = LocalDateTime.now();
        List<Long> granted = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        List<Long> reactivated = new ArrayList<>();

        for (Member member : members) {
            AvatarGrant existing = find(member.getId(), avatarId);
            if (existing == null) {
                AvatarGrant row = new AvatarGrant();
                row.setMemberId(member.getId());
                row.setAvatarId(avatarId);
                row.setBatch(batch);
                row.setNote(note);
                row.setGrantedBy(operator.memberId());
                row.setGrantedAt(now);
                avatarGrantMapper.insert(row);
                granted.add(member.getId());
            } else if (existing.getRevokedAt() == null) {
                skipped.add(member.getId());
            } else {
                avatarGrantMapper.update(null, new LambdaUpdateWrapper<AvatarGrant>()
                        .eq(AvatarGrant::getId, existing.getId())
                        .set(AvatarGrant::getGrantedBy, operator.memberId())
                        .set(AvatarGrant::getGrantedAt, now)
                        .set(AvatarGrant::getBatch, batch)
                        .set(AvatarGrant::getNote, note)
                        .set(AvatarGrant::getRevokedBy, null)
                        .set(AvatarGrant::getRevokedAt, null)
                        .set(AvatarGrant::getRevokeReason, null));
                reactivated.add(member.getId());
            }
        }
        return new GrantResult(granted, skipped, reactivated);
    }

    /**
     * 撤回误发。理由必填；只标记不删行。若该成员正戴着这张头像，回退成姓名首字
     * （`member.avatar = NULL`），并在结果里告诉前端"确实动了佩戴"。
     */
    @Transactional
    public RevokeResult revoke(long memberId, String avatarId, String reason, CurrentUser operator) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "撤回必须填写理由");
        }
        // 理由走查询参数，没有 @Size 兜底：不拦就是超长撞 varchar(200) 的 500。
        if (reason.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "撤回理由不能超过 200 字");
        }
        AvatarGrant existing = find(memberId, avatarId);
        if (existing == null || existing.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该成员没有这张头像的有效发放记录");
        }
        avatarGrantMapper.update(null, new LambdaUpdateWrapper<AvatarGrant>()
                .eq(AvatarGrant::getId, existing.getId())
                .set(AvatarGrant::getRevokedBy, operator.memberId())
                .set(AvatarGrant::getRevokedAt, LocalDateTime.now())
                .set(AvatarGrant::getRevokeReason, reason));

        Member member = memberMapper.selectById(memberId);
        boolean wearing = member != null && avatarId.equals(member.getAvatar());
        if (wearing) {
            // updateById 忽略 null → 清列必须显式 set（同 MeProfileService 的清空写法）。
            memberMapper.update(null, new LambdaUpdateWrapper<Member>()
                    .eq(Member::getId, memberId)
                    .set(Member::getAvatar, null));
        }
        return new RevokeResult(wearing);
    }

    /**
     * 本人当前**有效**的限定头像资格（已撤回的不算）。带 `batch` 是为了团员端能
     * 显示「纪念限定 · 批次名」（docs/14 §14.6.4「获赠团员端」）。
     */
    public List<GrantInfo> activeGrants(long memberId) {
        List<GrantInfo> grants = new ArrayList<>();
        for (AvatarGrant row : avatarGrantMapper.selectList(new LambdaQueryWrapper<AvatarGrant>()
                .eq(AvatarGrant::getMemberId, memberId)
                .isNull(AvatarGrant::getRevokedAt))) {
            grants.add(new GrantInfo(row.getAvatarId(), row.getBatch()));
        }
        return grants;
    }

    /** 写入头像时的第二道闸门：有未撤回的发放记录才算有资格。 */
    public boolean hasGrant(long memberId, String avatarId) {
        AvatarGrant row = find(memberId, avatarId);
        return row != null && row.getRevokedAt() == null;
    }

    /** 发放记录（含已撤回的）：按头像 / 批次筛，供核对误发与补发。 */
    public List<GrantRow> list(String avatarId, String batch) {
        LambdaQueryWrapper<AvatarGrant> query = new LambdaQueryWrapper<AvatarGrant>()
                .eq(avatarId != null && !avatarId.isBlank(), AvatarGrant::getAvatarId, avatarId)
                .eq(batch != null && !batch.isBlank(), AvatarGrant::getBatch, batch)
                .orderByDesc(AvatarGrant::getGrantedAt)
                .orderByDesc(AvatarGrant::getId);
        List<AvatarGrant> rows = avatarGrantMapper.selectList(query);
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<Long> peopleIds = new HashSet<>();
        for (AvatarGrant row : rows) {
            peopleIds.add(row.getMemberId());
            if (row.getGrantedBy() != null) {
                peopleIds.add(row.getGrantedBy());
            }
        }
        Map<Long, Member> people = new LinkedHashMap<>();
        for (Member member : memberMapper.selectBatchIds(peopleIds)) {
            people.put(member.getId(), member);
        }

        List<GrantRow> result = new ArrayList<>();
        for (AvatarGrant row : rows) {
            Member member = people.get(row.getMemberId());
            Member granter = row.getGrantedBy() == null ? null : people.get(row.getGrantedBy());
            result.add(new GrantRow(row.getMemberId(),
                    member == null ? null : member.getName(),
                    member == null ? null : member.getAccount(),
                    row.getAvatarId(), row.getBatch(), row.getNote(),
                    row.getGrantedBy(), granter == null ? null : granter.getName(),
                    row.getGrantedAt(), row.getRevokedAt(), row.getRevokeReason()));
        }
        return result;
    }

    private AvatarGrant find(long memberId, String avatarId) {
        List<AvatarGrant> rows = avatarGrantMapper.selectList(new LambdaQueryWrapper<AvatarGrant>()
                .eq(AvatarGrant::getMemberId, memberId)
                .eq(AvatarGrant::getAvatarId, avatarId));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
