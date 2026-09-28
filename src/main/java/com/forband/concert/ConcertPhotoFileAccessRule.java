package com.forband.concert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.CurrentUser;
import com.forband.file.FileAccessRule;
import com.forband.file.StoredFile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 演出照片 / 海报 / 专场策展图的下载规则（`docs/07` §7.4「照片」+ §7.5 可见性、`docs/13` §13.3㉔）：
 * **登录即可，含荣誉团员**——与曲目附件**相反**（那条对荣誉团员一律 403）。
 * <p>
 * ⚠️ 这条规则曾经**整个漏掉**（`交接单` `#70`）：判定链上没人为演出照片认领，
 * 于是除上传者本人外**全员 403**（含管理员），演出详情页的照片墙一直是坏的。
 * <p>
 * ⚠️⚠️ 必须覆盖**三个引用点**，漏掉任何一个那些图就只有上传者本人看得到：
 * ① {@code concert_photo.file_id}、② {@code concert.poster_file_id}、③ {@code showcase_photo.file_id}
 * （③ 漏掉就是"专页的图对全团是空的"）。
 * <p>
 * 不判 {@code viewer.role()} 是**有意的**：登录闸门由 `FileController` 的类级 {@code @RequireRole} 把住，
 * 走到这里的人必然已登录，而本口径对荣誉团员**也放行**。
 */
@Component
public class ConcertPhotoFileAccessRule implements FileAccessRule {

    private final ConcertPhotoMapper photoMapper;
    private final ConcertMapper concertMapper;
    private final ShowcasePhotoMapper showcasePhotoMapper;

    public ConcertPhotoFileAccessRule(ConcertPhotoMapper photoMapper,
                                      ConcertMapper concertMapper,
                                      ShowcasePhotoMapper showcasePhotoMapper) {
        this.photoMapper = photoMapper;
        this.concertMapper = concertMapper;
        this.showcasePhotoMapper = showcasePhotoMapper;
    }

    @Override
    public Optional<Boolean> canView(StoredFile file, CurrentUser viewer) {
        boolean isConcertPhoto = referenced(photoMapper.selectCount(
                new LambdaQueryWrapper<ConcertPhoto>().eq(ConcertPhoto::getFileId, file.getId())));
        boolean isPoster = referenced(concertMapper.selectCount(
                new LambdaQueryWrapper<Concert>().eq(Concert::getPosterFileId, file.getId())));
        boolean isShowcasePhoto = referenced(showcasePhotoMapper.selectCount(
                new LambdaQueryWrapper<ShowcasePhoto>().eq(ShowcasePhoto::getFileId, file.getId())));

        if (isConcertPhoto || isPoster || isShowcasePhoto) {
            return Optional.of(true);
        }
        return Optional.empty();   // 不是演出照片，交给别的规则
    }

    private static boolean referenced(Long count) {
        return count != null && count > 0;
    }
}
