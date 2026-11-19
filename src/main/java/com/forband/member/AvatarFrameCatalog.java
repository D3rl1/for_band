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
 * 头像框清单的**唯一权威**读取口（docs/14 §14.6.4「秋日树框」）。
 * <p>
 * `member.avatar_frame` 存受控 id，服务端校验 `值 ∈ 清单 ∪ {null}`；前端读同一份
 * `static/vendor/avatar-frames/manifest.json` 渲染。框是独立装饰层，**不放进 `member.avatar`**。
 * <p>
 * ⚠️ 清单启动时读一次：新增框必须重启，否则 {@link #isAllowed} 仍拒写。
 */
@Component
public class AvatarFrameCatalog {

    private static final Logger log = LoggerFactory.getLogger(AvatarFrameCatalog.class);
    private static final String MANIFEST = "static/vendor/avatar-frames/manifest.json";

    /** `dir` 下的 `assets/` 是运行素材目录；`file` 是网格预览用的静帧。 */
    public record Frame(String id, String name, String dir, String file) {
    }

    private final List<Frame> frames;

    public AvatarFrameCatalog(ObjectMapper objectMapper) {
        List<Frame> loaded = List.of();
        try (InputStream in = new ClassPathResource(MANIFEST).getInputStream()) {
            JsonNode items = objectMapper.readTree(in).path("frames");
            List<Frame> parsed = new java.util.ArrayList<>();
            for (JsonNode item : items) {
                parsed.add(new Frame(item.path("id").asText(), item.path("name").asText(),
                        item.path("dir").asText(), item.path("file").asText()));
            }
            loaded = List.copyOf(parsed);
        } catch (Exception e) {
            // 与头像清单同一降级：读不到就以空清单启动，界面只剩“不用头像框”。
            log.warn("头像框清单读取失败（{}），本次以空清单启动", MANIFEST, e);
        }
        this.frames = loaded;
    }

    public List<Frame> all() {
        return frames;
    }

    /** 只判“在不在清单里”——`null`（不用框）永远合法。 */
    public boolean isAllowed(String id) {
        if (id == null) {
            return true;
        }
        return ids().contains(id);
    }

    private Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        for (Frame frame : frames) {
            ids.add(frame.id());
        }
        return ids;
    }
}
