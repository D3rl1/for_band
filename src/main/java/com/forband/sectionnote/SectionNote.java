package com.forband.sectionnote;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 上任声部长的留言（docs/13 §13.3㉕，业务规则见 docs/03 §3.5）。
 * 声部长留给**下一任**的短笺：历届各留一条、按 `created_at` **叠着看**（所以 `section_id` **不是唯一键**）。
 * <p>
 * ⚠️ **任何人都不能删**（留痕性质）⇒ 没有删除路径，接口也刻意不提供。
 * ⚠️ `authorId` 记的是"**写的那一刻**"的身份：卸任之后他仍然是这条的作者。
 */
@TableName("section_note")
public class SectionNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sectionId;

    private Long authorId;

    /** 正文**纯文本 + 保留换行**（前端 `textContent`，不解析 HTML/markdown——同 `release_note` 的口径）。 */
    private String body;

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

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
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
