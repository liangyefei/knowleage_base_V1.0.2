package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImportFileMetadata 合并逻辑单元测试（纯 JUnit）
 * <p>覆盖批次默认 ⊕ 逐文件覆盖：documentMetadata key 覆盖、version/author/remark 非空覆盖、tags 覆盖。</p>
 */
class ImportFileMetadataTest {

    private ImportFileMetadata meta(Map<String, String> docMeta, String version, String author,
                                    List<String> tags, String remark) {
        return new ImportFileMetadata(docMeta, version, author, tags, remark, null);
    }

    @Test
    void shouldUseDefaultsWhenNoOverride() {
        ImportFileMetadata defaults = meta(Map.of("chip_model", "CV610", "sensitivity", "内部"),
                "v1", "alice", List.of("tag1"), "备注");

        ImportFileMetadata resolved = ImportFileMetadata.merge(defaults, null);

        assertEquals("CV610", resolved.documentMetadata().get("chip_model"));
        assertEquals("v1", resolved.version());
        assertEquals("alice", resolved.author());
        assertEquals(List.of("tag1"), resolved.tags());
        assertEquals("备注", resolved.remark());
    }

    @Test
    void shouldOverrideDictKeysPerFile() {
        ImportFileMetadata defaults = meta(new LinkedHashMap<>(Map.of("chip_model", "CV610", "sensitivity", "内部")),
                "v1", "alice", List.of("tag1"), null);

        ImportFileMetadata perFile = meta(Map.of("chip_model", "CV620", "official", "OFFICIAL"),
                null, null, null, null);

        ImportFileMetadata resolved = ImportFileMetadata.merge(defaults, perFile);

        // 逐文件覆盖同名 key
        assertEquals("CV620", resolved.documentMetadata().get("chip_model"));
        // 批次默认保留
        assertEquals("内部", resolved.documentMetadata().get("sensitivity"));
        // 逐文件新增 key 合入
        assertEquals("OFFICIAL", resolved.documentMetadata().get("official"));
        // 列字段缺省回退批次默认
        assertEquals("v1", resolved.version());
        assertEquals("alice", resolved.author());
        assertEquals(List.of("tag1"), resolved.tags());
    }

    @Test
    void shouldOverrideColumnsWhenPerFilePresent() {
        ImportFileMetadata defaults = meta(Map.of("chip_model", "CV610"), "v1", "alice", List.of("t1"), "r");

        ImportFileMetadata perFile = meta(Map.of(), "v2", "bob", List.of("t2", "t3"), "r2");

        ImportFileMetadata resolved = ImportFileMetadata.merge(defaults, perFile);

        assertEquals("v2", resolved.version());
        assertEquals("bob", resolved.author());
        assertEquals(List.of("t2", "t3"), resolved.tags());
        assertEquals("r2", resolved.remark());
    }

    @Test
    void shouldReturnEmptyWhenBothNull() {
        ImportFileMetadata resolved = ImportFileMetadata.merge(null, null);

        assertTrue(resolved.documentMetadata().isEmpty());
        assertNull(resolved.version());
        assertNull(resolved.author());
        assertTrue(resolved.tags().isEmpty());
    }

    /** title 仅 doc 用途消费；merge 链路逐文件非空覆盖、缺省回退批次默认 */
    @Test
    void shouldMergeTitleAndDefaultToNull() {
        ImportFileMetadata perFile = new ImportFileMetadata(Map.of(), null, null, null, null, "新标题");

        // 无批次默认：逐文件 title 保留
        assertEquals("新标题", ImportFileMetadata.merge(null, perFile).title());

        // 有批次默认：逐文件 title 覆盖
        ImportFileMetadata defaults = new ImportFileMetadata(Map.of(), "v1", null, null, null, "默认标题");
        assertEquals("新标题", ImportFileMetadata.merge(defaults, perFile).title());

        // 逐文件无 title：回退批次默认
        ImportFileMetadata noTitle = new ImportFileMetadata(Map.of(), null, null, null, null, null);
        assertEquals("默认标题", ImportFileMetadata.merge(defaults, noTitle).title());

        // empty()：title 恒 null（批量导入不消费该值）
        assertNull(ImportFileMetadata.empty().title());
    }
}
