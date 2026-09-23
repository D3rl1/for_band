package com.forband.recording;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.CurrentUser;
import com.forband.file.FileAccessRule;
import com.forband.file.StoredFile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 录音文件的下载规则——**必须与录音接口用同一条判定**（`RecordingVisibility`）。
 * <p>
 * ⚠️ 这条规则是本批**最关键的一环**：录音能播放，浏览器就必然拿得到字节流，
 * 所以 `GET /api/files/{id}` 是绕过界面的那条路。若这里不套同样的可见性，
 * 前端藏了按钮、接口照样能把别人的录音拖走——那"按声部可见"就是假的。
 */
@Component
public class RecordingFileAccessRule implements FileAccessRule {

    private final RecordingMapper recordingMapper;
    private final RecordingVisibility visibility;

    public RecordingFileAccessRule(RecordingMapper recordingMapper, RecordingVisibility visibility) {
        this.recordingMapper = recordingMapper;
        this.visibility = visibility;
    }

    @Override
    public Optional<Boolean> canView(StoredFile file, CurrentUser viewer) {
        Recording row = recordingMapper.selectOne(new LambdaQueryWrapper<Recording>()
                .eq(Recording::getFileId, file.getId()).last("LIMIT 1"));
        if (row == null) {
            return Optional.empty();   // 不是录音文件，交给别的规则
        }
        return Optional.of(visibility.canView(row, viewer));
    }
}
