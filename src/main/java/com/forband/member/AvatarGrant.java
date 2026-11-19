package com.forband.member;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 纪念头像发放关系（`docs/13` §13.3㉛、`docs/02` §2.4、#266）：**谁有资格用哪张限定头像**。
 * <p>
 * ⚠️ **只增不删**：一次发放永久有效，任何代码都不许因离团 / 停用 / 改角色 / 荣誉到期而删行；
 * 误发撤回只写 {@code revoked_*} 三列，留下"曾经发给谁、为什么收回"的痕迹。
 * <p>
 * 头像本身仍在 `member.avatar` 存受控 id，本表只回答"有没有资格"，不复制素材信息。
 */
@TableName("avatar_grant")
public class AvatarGrant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 获赠成员（FK→member）。 */
    private Long memberId;

    /** 限定头像的受控标识符（清单里 `scope=grant` 的条目）。 */
    private String avatarId;

    /** 纪念批次 / 活动名，发放记录按它筛。 */
    private String batch;

    /** 发放理由 / 备注。 */
    private String note;

    /** 发放操作人（FK→member）。 */
    private Long grantedBy;

    private LocalDateTime grantedAt;

    /** 撤回操作人；为空 = 资格仍有效。 */
    private Long revokedBy;

    private LocalDateTime revokedAt;

    /** 撤回必须填理由（只用于纠错，不是到期收回）。 */
    private String revokeReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getAvatarId() {
        return avatarId;
    }

    public void setAvatarId(String avatarId) {
        this.avatarId = avatarId;
    }

    public String getBatch() {
        return batch;
    }

    public void setBatch(String batch) {
        this.batch = batch;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }
}
