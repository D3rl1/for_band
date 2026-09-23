package com.forband.recording;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 录音点评（docs/13 §13.3⑰）。**平铺按时间，不做父子嵌套**（docs/08 §8.4）——
 * 双向交流（声部长留言、团员追问）靠时间线自然完成。
 * <p>
 * ⚠️ **刻意不做「已点评 / 未点评」标记**（DECISIONS D18）：一旦有这个状态，
 * 声部长的点评就从"自发帮忙"变成"欠账 KPI"，他会开始躲这件事。
 */
@TableName("recording_comment")
public class RecordingComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordingId;

    private Long authorId;

    private String content;

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

    public Long getRecordingId() {
        return recordingId;
    }

    public void setRecordingId(Long recordingId) {
        this.recordingId = recordingId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
