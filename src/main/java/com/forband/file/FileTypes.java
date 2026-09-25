package com.forband.file;

import java.util.Locale;
import java.util.Set;

/**
 * 文件类型白名单 + **按文件头嗅探**（docs/13 ⑨：`content_type` 由**服务端判定**，不信客户端传来的 MIME；
 * docs/08 §8.5：类型与大小都要校验，且"服务端仍要校验实际大小，防绕过前端直接调接口传大图"）。
 * <p>
 * 允许的三种：
 * <ul>
 *   <li>{@link Kind#AUDIO} m4a / mp3 / wav（`08` 只认这三种；**不转码**）</li>
 *   <li>{@link Kind#IMAGE} jpg / png / gif / webp（演出照片、海报；谱子的扫描件也走这条）</li>
 *   <li>{@link Kind#PDF} 谱子</li>
 * </ul>
 */
public final class FileTypes {

    public enum Kind {
        AUDIO, IMAGE, PDF
    }

    public record Detected(String contentType, Kind kind) {
    }

    /** 只认这些扩展名（原始名仅用于展示与后缀，**不参与路径拼接**）。 */
    private static final Set<String> AUDIO_EXT = Set.of("m4a", "mp3", "wav");
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> PDF_EXT = Set.of("pdf");

    private FileTypes() {
    }

    /**
     * 按文件头判定类型。**认不出来一律返回 null**——调用方据此拒绝（422），
     * 这样"客户端把 exe 改名成 .mp3"这条路走不通。
     */
    public static Detected sniff(byte[] head) {
        if (head == null || head.length < 12) {
            return null;
        }
        if (startsWith(head, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return new Detected("application/pdf", Kind.PDF);
        }
        if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return new Detected("image/jpeg", Kind.IMAGE);
        }
        if (head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return new Detected("image/png", Kind.IMAGE);
        }
        if (startsWith(head, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || startsWith(head, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return new Detected("image/gif", Kind.IMAGE);
        }
        if (startsWith(head, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                && ascii(head, 8, 4).equals("WAVE")) {
            return new Detected("audio/wav", Kind.AUDIO);
        }
        if (startsWith(head, "ID3".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || (head[0] == (byte) 0xFF && (head[1] & 0xE0) == 0xE0)) {
            return new Detected("audio/mpeg", Kind.AUDIO);   // mp3（含没有 ID3 的裸帧）
        }
        // ISO-BMFF（m4a / mp4）：第 4~8 字节是 "ftyp"
        if (ascii(head, 4, 4).equals("ftyp")) {
            return new Detected("audio/mp4", Kind.AUDIO);
        }
        return null;
    }

    /** 扩展名 → 期望的类型（与嗅探结果**互相印证**：两边都要落在同一类里才收）。 */
    public static Kind kindOfExtension(String originalName) {
        String ext = extensionOf(originalName);
        if (ext.isEmpty()) {
            return null;
        }
        if (AUDIO_EXT.contains(ext)) {
            return Kind.AUDIO;
        }
        if (IMAGE_EXT.contains(ext)) {
            return Kind.IMAGE;
        }
        if (PDF_EXT.contains(ext)) {
            return Kind.PDF;
        }
        return null;
    }

    /** 取小写扩展名（无扩展名返回空串）。**只用于判断**，绝不拼进磁盘路径。 */
    public static String extensionOf(String originalName) {
        String name = originalName == null ? "" : originalName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        if (head.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (head[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] head, int offset, int length) {
        if (head.length < offset + length) {
            return "";
        }
        return new String(head, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
