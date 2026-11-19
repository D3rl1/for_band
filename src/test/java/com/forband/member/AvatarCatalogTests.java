package com.forband.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像清单的解析规则（docs/02 §2.4、docs/13 §13.3㉛、#266）：
 * 每条可选 {@code "scope": "grant"}，不写 / 写别的值 = 公共头像。
 * <p>
 * 这层是"限定头像"的**唯一判定来源**——数据库只存"谁有资格"，
 * 不再另维护一份"哪些是限定"的名单，免得两处漂。
 */
class AvatarCatalogTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AvatarCatalog parse(String json) {
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return new AvatarCatalog(MAPPER, in);
    }

    @Test
    void scope_grant_marks_limited_avatar() {
        AvatarCatalog catalog = parse("""
                {"avatars":[
                  {"id":"public-one","name":"公共","file":"a.svg"},
                  {"id":"limited-one","name":"纪念","file":"b.png","scope":"grant"}
                ]}
                """);

        assertTrue(catalog.isKnown("public-one"));
        assertTrue(catalog.isKnown("limited-one"));
        assertTrue(catalog.isPublic("public-one"));
        assertFalse(catalog.isPublic("limited-one"));
        assertTrue(catalog.isGrantOnly("limited-one"));
        assertFalse(catalog.isGrantOnly("public-one"));
    }

    @Test
    void missing_or_unknown_scope_defaults_to_public() {
        AvatarCatalog catalog = parse("""
                {"avatars":[
                  {"id":"a","name":"甲","file":"a.svg"},
                  {"id":"b","name":"乙","file":"b.svg","scope":"whatever"},
                  {"id":"c","name":"丙","file":"c.svg","scope":""}
                ]}
                """);

        assertTrue(catalog.isPublic("a"));
        assertTrue(catalog.isPublic("b"));
        assertTrue(catalog.isPublic("c"));
        assertFalse(catalog.isGrantOnly("a"));
    }

    @Test
    void null_and_unknown_avatar_are_not_grant_only() {
        AvatarCatalog catalog = parse("{\"avatars\":[]}");

        // null = 不用头像，任何地方都合法；未知 id 由 isKnown 拒掉，不能算"限定"。
        assertTrue(catalog.isPublic(null));
        assertFalse(catalog.isKnown("not-in-list"));
        assertFalse(catalog.isGrantOnly("not-in-list"));
    }

    @Test
    void broken_manifest_degrades_to_empty_catalog() {
        AvatarCatalog catalog = parse("{ this is not json");

        assertEquals(0, catalog.all().size());
        assertFalse(catalog.isKnown("fluent-frog"));
    }

    @Test
    void production_manifest_grant_entries_have_assets() throws Exception {
        AvatarCatalog catalog = new AvatarCatalog(MAPPER,
                new ClassPathResource("static/vendor/avatars/manifest.json").getInputStream());

        assertTrue(catalog.all().size() > 0);
        for (AvatarCatalog.Avatar avatar : catalog.all()) {
            assertTrue(new ClassPathResource(
                            "static/vendor/avatars/" + avatar.file()).exists(),
                    "清单条目缺素材：" + avatar.id() + " → " + avatar.file());
        }
    }
}
