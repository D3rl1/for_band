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

    /**
     * `file` = 该头像的**保存文件名（带后缀）**——清单显式给出，代码里不拼后缀（docs/14 §14.6.4）。
     * <p>
     * {@code grantOnly} 来自清单的可选字段 {@code "scope": "grant"}（docs/02 §2.4、#266）：
     * **不写 / 写别的值一律当公共头像**——限定是例外，不能靠"忘了写字段"把公共头像误关起来。
     */
    public record Avatar(String id, String name, String file, boolean grantOnly) {
    }

    private final List<Avatar> avatars;

    /** Spring 用这个入口；下面那个测试构造器存在时**必须**显式标注，否则装配报“No default constructor”。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AvatarCatalog(ObjectMapper objectMapper) {
        this(objectMapper, openManifest());
    }

    /** 测试用入口：直接喂一份清单，不碰 classpath（见 {@code AvatarCatalogTests}）。 */
    AvatarCatalog(ObjectMapper objectMapper, InputStream in) {
        List<Avatar> loaded = List.of();
        try (in) {
            JsonNode items = objectMapper.readTree(in).path("avatars");
            List<Avatar> parsed = new java.util.ArrayList<>();
            for (JsonNode item : items) {
                parsed.add(new Avatar(item.path("id").asText(), item.path("name").asText(),
                        item.path("file").asText(), "grant".equals(item.path("scope").asText())));
            }
            loaded = List.copyOf(parsed);
        } catch (Exception e) {
            // 素材没入库（或文件坏了）时不能把应用拖下水：清单为空 → 界面全部回退姓名首字，
            // 换头像网格只剩「不用头像」。这是刻意选的降级方式，别改成启动失败。
            log.warn("预置头像清单读取失败，本次以空清单启动：换头像将只剩「不用头像」", e);
        }
        this.avatars = loaded;
    }

    private static InputStream openManifest() {
        try {
            return new ClassPathResource(MANIFEST).getInputStream();
        } catch (Exception e) {
            // 交给构造器统一按"空清单"处理，这里不抛：启动失败比空清单更难查。
            return InputStream.nullInputStream();
        }
    }

    public List<Avatar> all() {
        return avatars;
    }

    /** 在不在清单里（公共 + 限定都算）。 */
    public boolean isKnown(String id) {
        if (id == null) {
            return true;
        }
        return ids().contains(id);
    }

    /**
     * 公共头像判定：`null`（不用头像）永远合法；限定头像必须先拿到发放资格。
     * 这是头像写入路径的**第一道闸门**，第二道是 {@code AvatarGrantService.hasGrant}。
     */
    public boolean isPublic(String id) {
        if (id == null) {
            return true;
        }
        Avatar avatar = find(id);
        return avatar != null && !avatar.grantOnly();
    }

    /** 是否限定头像（只有它才需要发放；未知 id 返回 false，由 {@link #isKnown} 另判）。 */
    public boolean isGrantOnly(String id) {
        Avatar avatar = id == null ? null : find(id);
        return avatar != null && avatar.grantOnly();
    }

    private Avatar find(String id) {
        for (Avatar avatar : avatars) {
            if (avatar.id().equals(id)) {
                return avatar;
            }
        }
        return null;
    }

    private Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        for (Avatar avatar : avatars) {
            ids.add(avatar.id());
        }
        return ids;
    }
}
