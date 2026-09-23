package com.forband.file;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 存储用量的聚合查询（docs/08 §8.3 / §8.5）。
 * <p>
 * 三段 SQL 都是**常量**（没有拼接、没有用户输入），符合 `SECURITY.md` C1；
 * 聚合本来就不该把行捞进内存再累加。
 * <p>
 * **总数按 `file` 表全部行算**（= 磁盘上实际有多少东西），三类的分类数按各自引用表 join 算——
 * 这样"上传了但没挂到任何实体上"的孤儿文件**也会计入总量**，警戒线才不会漏算。
 */
@Mapper
public interface StorageUsageMapper {

    /** 磁盘上全部文件（含孤儿）。 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM file")
    long totalBytes();

    @Select("SELECT COUNT(*) FROM file")
    long totalCount();

    @Select("SELECT COALESCE(SUM(f.size_bytes), 0) FROM file f"
            + " JOIN piece_attachment a ON a.file_id = f.id")
    long pieceAttachmentBytes();

    @Select("SELECT COUNT(*) FROM piece_attachment")
    long pieceAttachmentCount();

    @Select("SELECT COALESCE(SUM(f.size_bytes), 0) FROM file f"
            + " JOIN concert_photo p ON p.file_id = f.id")
    long concertPhotoBytes();

    @Select("SELECT COUNT(*) FROM concert_photo")
    long concertPhotoCount();

    @Select("SELECT COALESCE(SUM(f.size_bytes), 0) FROM file f"
            + " JOIN recording r ON r.file_id = f.id")
    long recordingBytes();

    @Select("SELECT COUNT(*) FROM recording")
    long recordingCount();
}
