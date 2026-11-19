package com.forband.concert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 专场策展图（`docs/13` §13.3㉔，业务规则见 `docs/17`）。
 * <p>
 * ⚠️ 与 {@link ConcertPhoto}（演出页的晒图墙，全员可传、不排序）是**两件事**：这张表是**专页的门面**，
 * **仅管理员**策展、**有顺序**（{@code sortNo}）、**有独家长文案**（{@code note}）。
 * 允许复用同一张图——新插一行、{@code fileId} 指向同一个 `file`，不重复占磁盘。
 * <p>
 * ⚠️ {@code file_id} 必须被文件下载规则认领（`ConcertPhotoFileAccessRule`），否则专页的图只有管理员本人看得到。
 */
@TableName("showcase_photo")
public class ShowcasePhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long concertId;

    private Long fileId;

    private Integer sortNo;

    private String note;

    private Long createdBy;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConcertId() {
        return concertId;
    }

    public void setConcertId(Long concertId) {
        this.concertId = concertId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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
}
