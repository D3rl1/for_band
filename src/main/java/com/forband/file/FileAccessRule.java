package com.forband.file;

import com.forband.auth.CurrentUser;

import java.util.Optional;

/**
 * 文件可见性的**扩展点**：一个文件的可见性由「它被哪类实体引用」决定——
 * 曲目附件（`06`）、演出照片（`07`）、练习录音（`08`）各有各的规则（docs/08 §8.5）。
 * <p>
 * 每个板块实现一个 `@Component`，Spring 把它们注进 {@link FileService}。
 * `Optional.empty()` = "这个文件不归我管"，交给下一条规则；全都不认 → 只有上传者本人可取。
 */
public interface FileAccessRule {

    /** 认领这个文件并给出结论；不认领返回 {@code Optional.empty()}。 */
    Optional<Boolean> canView(StoredFile file, CurrentUser viewer);
}
