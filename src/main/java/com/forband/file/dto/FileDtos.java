package com.forband.file.dto;

public final class FileDtos {

    private FileDtos() {
    }

    /** 上传成功后返回的文件视图。**不含 `storedName`**——磁盘名是内部实现，不该出接口。 */
    public record FileView(long id, String originalName, String contentType, long sizeBytes) {
    }

    /**
     * 存储用量（docs/08 §8.3/§8.5）：管理员后台显示，超阈值时提醒。
     * `overThreshold` 由**服务端**算好给前端（免得两边口径不一）；**只读、绝不自动清理**。
     */
    public record StorageUsage(long totalBytes, long totalCount, long thresholdBytes,
                               boolean overThreshold, java.util.List<Category> byKind) {

        /** 一类引用（曲目附件 / 演出照片 / 练习录音）的用量。 */
        public record Category(String kind, String label, long bytes, long count) {
        }
    }
}
