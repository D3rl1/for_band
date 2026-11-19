package com.forband.concert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 演出照片（docs/13 §13.3⑮）。文件本体在 `file` 表（统一机制，docs/08 §8.5）。
 * **团员都能上传**——演出当天拍照的往往是普通团员，限制上传只会让照片永远集不起来；
 * **删除限上传者本人 / 管理员**。
 */
@TableName("concert_photo")
public class ConcertPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long concertId;

    private Long fileId;

    private String caption;

    private Long uploadedBy;

    private LocalDateTime uploadedAt;

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

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
