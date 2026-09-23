package com.forband.file;

import com.forband.auth.CurrentUser;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.file.dto.FileDtos.FileView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 统一文件上传 / 下载（docs/08 §8.5）。**三个板块共用这一套**，别各自再写一份。
 * <p>
 * 权限模型的关键在下载：**必须走后端鉴权接口，严禁暴露公开静态目录**——
 * 若用 `/uploads/xxx.mp3` 这类可猜路径，任何人拿到 URL 就能下载，整个权限模型形同虚设。
 */
@Service
public class FileService {

    private static final int SNIFF_BYTES = 32;

    private final StoredFileMapper fileMapper;
    private final FileStorage storage;
    private final List<FileAccessRule> accessRules;

    private final long maxAudioBytes;
    private final long maxImageBytes;
    private final long maxScoreBytes;

    public FileService(StoredFileMapper fileMapper, FileStorage storage,
                       List<FileAccessRule> accessRules,
                       @Value("${band.storage.max-audio-bytes}") long maxAudioBytes,
                       @Value("${band.storage.max-image-bytes}") long maxImageBytes,
                       @Value("${band.storage.max-score-bytes}") long maxScoreBytes) {
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.accessRules = accessRules;
        this.maxAudioBytes = maxAudioBytes;
        this.maxImageBytes = maxImageBytes;
        this.maxScoreBytes = maxScoreBytes;
    }

    @Transactional
    public FileView upload(MultipartFile upload, CurrentUser owner) {
        if (upload == null || upload.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件为空");
        }
        String originalName = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename();
        FileTypes.Kind byExtension = FileTypes.kindOfExtension(originalName);
        if (byExtension == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "不支持的文件类型（音频 m4a/mp3/wav、图片 jpg/png/gif/webp、谱子 pdf）");
        }

        long limit = limitOf(byExtension);
        if (upload.getSize() > limit) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "文件超过上限（" + (limit / 1024 / 1024) + "MB）");
        }

        FileTypes.Detected detected;
        FileStorage.Stored stored;
        try (InputStream raw = upload.getInputStream();
             BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(SNIFF_BYTES);
            byte[] head = in.readNBytes(SNIFF_BYTES);
            in.reset();
            detected = FileTypes.sniff(head);
            if (detected == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件内容认不出来，拒绝入库");
            }
            // 扩展名与文件头**互相印证**：改名绕过（exe 改名成 .mp3）在这里被挡住
            if (detected.kind() != byExtension) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "文件内容与扩展名不符（内容像 " + detected.kind() + "，名字却是 " + byExtension + "）");
            }
            stored = storage.write(in, FileTypes.extensionOf(originalName));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }

        StoredFile row = new StoredFile();
        row.setStoredName(stored.storedName());
        row.setOriginalName(originalName);
        row.setContentType(detected.contentType());   // 服务端判定，不信客户端
        row.setSizeBytes(stored.sizeBytes());
        row.setSha256(stored.sha256());
        row.setOwnerId(owner.memberId());
        fileMapper.insert(row);
        return new FileView(row.getId(), row.getOriginalName(), row.getContentType(), row.getSizeBytes());
    }

    /** 取文件行（给各板块引用时用）。 */
    public StoredFile require(long id) {
        StoredFile row = fileMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return row;
    }

    /** 批量取文件行（详情页要显示原始名/大小/类型，一次取完避免 N+1）。 */
    public java.util.Map<Long, StoredFile> findAll(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return java.util.Map.of();
        }
        return fileMapper.selectBatchIds(distinct).stream()
                .collect(java.util.stream.Collectors.toMap(StoredFile::getId, f -> f));
    }

    /** 下载前的鉴权：本人永远可取；否则问各板块的规则；都不认领 → 403（`08` §8.5 明写"曲目附件一律 403"）。 */
    public StoredFile requireViewable(long id, CurrentUser viewer) {
        StoredFile row = require(id);
        if (row.getOwnerId() != null && row.getOwnerId() == viewer.memberId()) {
            return row;
        }
        for (FileAccessRule rule : accessRules) {
            Optional<Boolean> allowed = rule.canView(row, viewer);
            if (allowed.isPresent()) {
                if (allowed.get()) {
                    return row;
                }
                throw new BusinessException(ErrorCode.NO_PERMISSION);
            }
        }
        throw new BusinessException(ErrorCode.NO_PERMISSION);
    }

    public InputStream open(StoredFile row) {
        return storage.read(row);
    }

    private long limitOf(FileTypes.Kind kind) {
        return switch (kind) {
            case AUDIO -> maxAudioBytes;
            case IMAGE -> maxImageBytes;
            case PDF -> maxScoreBytes;
        };
    }
}
