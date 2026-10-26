package com.forband.recording;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本周录音作业（docs/13 §13.3㉖，业务规则见 docs/08 §8.4）。
 * 粒度是**声部**：一条 = 某声部某周的「标题 + 可选曲目」；基本功作业不挂曲目。
 * <p>
 * **不存完成度**：未交/部分交/已交在查询时按 `recording.assignment_id` 现算（冗余状态会被换声部、
 * 删录音、改作业弄脏）。历史周只读——服务层按 `weekStart == 本周一` 拦。
 */
@TableName("recording_assignment")
public class RecordingAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 该周周一（Asia/Shanghai）——周的唯一定义，不存 week_end。 */
    private LocalDate weekStart;

    private Long sectionId;

    private String title;

    /** 为空 = 基本功（如「单跳」「长弓」）。 */
    private Long pieceId;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public void setWeekStart(LocalDate weekStart) {
        this.weekStart = weekStart;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getPieceId() {
        return pieceId;
    }

    public void setPieceId(Long pieceId) {
        this.pieceId = pieceId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
