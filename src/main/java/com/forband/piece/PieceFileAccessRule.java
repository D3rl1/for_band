package com.forband.piece;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.Role;
import com.forband.file.FileAccessRule;
import com.forband.file.StoredFile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 曲目附件（谱子 / 音频）的下载规则（docs/06 §6.4 可见性 + docs/08 §8.5）：
 * **登录即可下载**（规格标注为暂定 T3），**但荣誉团员一律不可**——
 * 用户原话："账号只是为了追忆往事，**跟谱子扯不上关系**"。曲名与演出曲目单他们照常看得到。
 */
@Component
public class PieceFileAccessRule implements FileAccessRule {

    private final PieceAttachmentMapper attachmentMapper;

    public PieceFileAccessRule(PieceAttachmentMapper attachmentMapper) {
        this.attachmentMapper = attachmentMapper;
    }

    @Override
    public Optional<Boolean> canView(StoredFile file, CurrentUser viewer) {
        Long refs = attachmentMapper.selectCount(new LambdaQueryWrapper<PieceAttachment>()
                .eq(PieceAttachment::getFileId, file.getId()));
        if (refs == null || refs == 0) {
            return Optional.empty();   // 不是曲目附件，交给别的规则
        }
        return Optional.of(viewer.role() != Role.HONORARY);
    }
}
