package com.forband.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 预置头像清单的**唯一权威**读取口（docs/14 §14.6.4：`member.avatar` 存**受控标识符**、
 * 服务端必须校验 `值 ∈ 清单 ∪ {null}`——这是"零安全面"的关键，**别改成"存 URL"**）。
 * <p>
 * 清单文件与素材放在一起（`static/vendor/avatars/manifest.json`），**前端读同一份**渲染网格，
 * 免得两边各手抄一遍 id 而对不上。
 * <p>
 * ⚠️ 清单是**启动时读一次**的：新增一张头像（改 `manifest.json`）**必须重启服务**，
 * 否则这里的名单还是旧的，写库时会被 {@link #isAllowed} 拒成 422。
 */
@Component
public class AvatarCatalog {

    private static final Logger log = LoggerFactory.getLogger(AvatarCatalog.class);

    private static final String MANIFEST = "static/vendor/avatars/manifest.json";

    /** `file` = 该头像的**保存文件名（带后缀）**——清单显式给出，代码里不拼后缀（docs/14 §14.6.4）。 */
    public record Avatar(String id, String name, String file) {
    }

    private final List<Avatar> avatars;

    public AvatarCatalog(ObjectMapper objectMapper) {
        List<Avatar> loaded = List.of();
        try (InputStream in = new ClassPathResource(MANIFEST).getInputStream()) {
            JsonNode items = objectMapper.readTree(in).path("avatars");
            List<Avatar> parsed = new java.util.ArrayList<>();
            for (JsonNode item : items) {
                parsed.add(new Avatar(item.path("id").asText(), item.path("name").asText(),
                        item.path("file").asText()));
            }
            loaded = List.copyOf(parsed);
        } catch (Exception e) {
            // 素材没入库（或文件坏了）时不能把应用拖下水：清单为空 → 界面全部回退姓名首字，
            // 换头像网格只剩「不用头像」。这是刻意选的降级方式，别改成启动失败。
            log.warn("预置头像清单读取失败（{}），本次以空清单启动：换头像将只剩「不用头像」", MANIFEST, e);
        }
        this.avatars = loaded;
    }

    public List<Avatar> all() {
        return avatars;
    }

    /** 只判"在不在清单里"——`null`（不用头像）永远算合法。 */
    public boolean isAllowed(String id) {
        if (id == null) {
            return true;
        }
        return ids().contains(id);
    }

    private Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        for (Avatar avatar : avatars) {
            ids.add(avatar.id());
        }
        return ids;
    }
}
