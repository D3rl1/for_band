package com.forband.file;

import com.forband.file.dto.FileDtos.StorageUsage;
import com.forband.file.dto.FileDtos.StorageUsage.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存储用量（docs/08 §8.3「永久保留，**后台显示存储用量，到阈值时提醒管理员**」+ §8.5「全局用量显示在管理员后台」）。
 * <p>
 * ⚠️ **只显示、只提醒，绝不自动清理任何东西**——规格反复强调"清不清理、什么时候清，控制权留在人手里"，
 * 理由是"我的录音莫名少了"会直接摧毁使用意愿。所以这里**只有读**，没有任何删除逻辑，以后也别加。
 * <p>
 * `overThreshold` **由服务端算**（不让前端自己比，免得两边口径不一）。
 */
@Service
public class StorageUsageService {

    private final StorageUsageMapper mapper;
    private final long warnBytes;

    public StorageUsageService(StorageUsageMapper mapper,
                               @Value("${band.storage.warn-bytes}") long warnBytes) {
        this.mapper = mapper;
        this.warnBytes = warnBytes;
    }

    public StorageUsage usage() {
        long totalBytes = mapper.totalBytes();
        List<Category> byKind = List.of(
                new Category("PIECE_ATTACHMENT", "曲目附件",
                        mapper.pieceAttachmentBytes(), mapper.pieceAttachmentCount()),
                new Category("CONCERT_PHOTO", "演出照片",
                        mapper.concertPhotoBytes(), mapper.concertPhotoCount()),
                new Category("RECORDING", "练习录音",
                        mapper.recordingBytes(), mapper.recordingCount()));
        return new StorageUsage(totalBytes, mapper.totalCount(), warnBytes,
                totalBytes > warnBytes, byKind);
    }
}
