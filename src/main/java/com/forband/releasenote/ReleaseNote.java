package com.forband.releasenote;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 更新公告（docs/13 §13.3㉒）。正文**纯文本 + 换行**（前端 `textContent`，不解析 HTML/markdown）；
 * **发布后不可改不可删**——团员读过的历史要稳定，所以没有 updated_at，也不提供 PUT/DELETE。
 */
@TableName("release_note")
public class ReleaseNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    /** 业务时间，**显式赋值**（MetaObjectHandler 只管 created_at / updated_at）。 */
    private LocalDateTime publishedAt;

    private Long publishedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
