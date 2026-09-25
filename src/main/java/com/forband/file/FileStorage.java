package com.forband.file;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 落盘读写（docs/08 §8.5 的两条硬要求都在这里）：
 * <ol>
 *   <li>**磁盘文件名随机生成**，不用原始文件名——原始名可猜、还可能带路径穿越字符；</li>
 *   <li>文件存在**仓库之外**的目录里（`band.storage.dir`），**不进 git**，随每日备份一起处理。</li>
 * </ol>
 * ⚠️ 这个目录**绝不能**变成可公开访问的静态目录：下载一律走 {@code GET /api/files/{id}} 的鉴权接口，
 * 否则"可猜路径 = 任何人拿到 URL 就能下载，整个权限模型形同虚设"。
 */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    private final Path root;

    public FileStorage(@Value("${band.storage.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录：" + root, e);
        }
        log.info("上传目录：{}", root);
    }

    public Path root() {
        return root;
    }

    /**
     * 把输入流写进上传目录，**随机命名**，并顺带算 sha256。
     * @param extension 已白名单校验过的扩展名（为空则不加后缀）
     */
    public Stored write(InputStream in, String extension) {
        String storedName = UUID.randomUUID().toString().replace("-", "")
                + (extension.isEmpty() ? "" : "." + extension);
        Path target = resolve(storedName);
        MessageDigest digest = newSha256();
        long size;
        try (DigestInputStream dis = new DigestInputStream(in, digest)) {
            size = Files.copy(dis, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败必须自己说话（docs/10 §10.5）：目录 + 目标文件名 + 完整异常（带堆栈）。
            // 三者缺一不可——目录不可写是 AccessDeniedException，磁盘满是 No space left on device，
            // 两者的修法完全不同；压成一句话就白记了（2026-09-24 线上真踩过，日志里零线索）。
            // 响应仍只说人话、不暴露服务器路径（见下面那个 BusinessException）。
            log.error("文件写入失败：目录={} 文件名={}", root, storedName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件写入失败");
        }
        return new Stored(storedName, size, HexFormat.of().formatHex(digest.digest()));
    }

    /** 读回磁盘内容（下载用）。文件不在了 → 404，而不是 500。 */
    public InputStream read(StoredFile file) {
        try {
            return Files.newInputStream(resolve(file.getStoredName()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件内容已丢失");
        }
    }

    public void delete(String storedName) {
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (IOException e) {
            log.warn("删除磁盘文件失败：{}", storedName, e);
        }
    }

    /** 只允许"目录 + 单个文件名"，且必须仍落在上传目录内（双保险，防路径穿越）。 */
    private Path resolve(String storedName) {
        Path path = root.resolve(storedName).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "非法文件名");
        }
        return path;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Stored(String storedName, long sizeBytes, String sha256) {
    }
}
