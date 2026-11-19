package com.forband.issue;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 问题讨论主表（docs/13 §13.3㉗）。
 *
 * <p>⚠️ {@code updatedAt} 只做**插入**填充：插入后的更新由服务层显式决定。
 * 回帖 / 改状态会推进它，作者编辑与 +1 不推进（docs/15 §15.4）。
 */
@TableName("issue")
public class Issue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private String title;

    private String content;

    private String deviceInfo;

    private String userAgent;

    private String pagePath;

    private String status;

    private Long statusChangedBy;

    private LocalDateTime statusChangedAt;

    private LocalDateTime editedAt;

    private Long sourceFeedbackId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime updatedAt;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getPagePath() {
        return pagePath;
    }

    public void setPagePath(String pagePath) {
        this.pagePath = pagePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStatusChangedBy() {
        return statusChangedBy;
    }

    public void setStatusChangedBy(Long statusChangedBy) {
        this.statusChangedBy = statusChangedBy;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(LocalDateTime statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public Long getSourceFeedbackId() {
        return sourceFeedbackId;
    }

    public void setSourceFeedbackId(Long sourceFeedbackId) {
        this.sourceFeedbackId = sourceFeedbackId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
