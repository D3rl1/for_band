package com.forband.member;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 主页访客记录（`docs/13` §13.3㉓）：**谁 · 哪天来看过谁的主页**。
 * <p>
 * ⚠️ **去重靠唯一键**（`uk_viewer_host_date`），**不靠代码判重**——并发下代码判重挡不住。
 * `visit_date` 之所以是**显式的日期列**：`DATE(visited_at)` 这种函数表达式进不了唯一键。
 * <p>
 * ⚠️ **不记 IP / UA**（"谁 + 何时"就够，少记隐私）；**不进操作日志**
 * （`docs/11` §11.3 的判据：对象自己已经记了"谁在何时来的"，不必重复记）。
 */
@TableName("profile_visit")
public class ProfileVisit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 谁来看的（FK→member）。 */
    private Long viewerId;

    /** 看了**谁**的主页（FK→member）。 */
    private Long hostId;

    /** 去重键的一部分：同一人同一天对同一人只留一条。 */
    private LocalDate visitDate;

    /** **当天第一次**来的时间点（第二、三次会被唯一键挡掉）。 */
    private LocalDateTime visitedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getViewerId() {
        return viewerId;
    }

    public void setViewerId(Long viewerId) {
        this.viewerId = viewerId;
    }

    public Long getHostId() {
        return hostId;
    }

    public void setHostId(Long hostId) {
        this.hostId = hostId;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public void setVisitDate(LocalDate visitDate) {
        this.visitDate = visitDate;
    }

    public LocalDateTime getVisitedAt() {
        return visitedAt;
    }

    public void setVisitedAt(LocalDateTime visitedAt) {
        this.visitedAt = visitedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
