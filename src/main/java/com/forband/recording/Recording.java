package com.forband.recording;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 练习录音（docs/13 §13.3⑯）。文件本体在 `file` 表，走统一上传/鉴权下载（docs/08 §8.5）。
 * <p>
 * **`sectionId` 是冗余列**（可见性按声部判定、查询高频）；上传者没有声部时（指挥 / 团长）为空。
 * `durationSec` 是**客户端上报**的——服务端不解析音频，所以 8 分钟上限（暂停不计）只在前端拦（见 {@link RecordingService}）。
 */
@TableName("recording")
public class Recording {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private Long sectionId;

    private Long assignmentId;

    private Long pieceId;

    private String title;

    private Long fileId;

    private Integer durationSec;

    private Long sizeBytes;

    private LocalDateTime uploadedAt;

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

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(Long assignmentId) {
        this.assignmentId = assignmentId;
    }

    public Long getPieceId() {
        return pieceId;
    }

    public void setPieceId(Long pieceId) {
        this.pieceId = pieceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(Integer durationSec) {
        this.durationSec = durationSec;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
