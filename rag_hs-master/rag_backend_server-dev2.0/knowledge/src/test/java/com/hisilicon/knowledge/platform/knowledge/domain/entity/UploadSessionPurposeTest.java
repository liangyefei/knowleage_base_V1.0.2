package com.hisilicon.knowledge.platform.knowledge.domain.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UploadSessionPurpose 解析单元测试（纯 JUnit，领域层不依赖容器）
 * <p>null/空白/显式 batchImport 别名 → BATCH_IMPORT；大小写不敏感；未知显式值抛出（fail-closed，
 * 调用方转 A0420——用途决定 create 校验与 complete 落库行为，不静默降级）。</p>
 */
class UploadSessionPurposeTest {

    @Test
    void shouldDefaultToBatchImportWhenAbsent() {
        assertEquals(UploadSessionPurpose.BATCH_IMPORT, UploadSessionPurpose.fromString(null));
        assertEquals(UploadSessionPurpose.BATCH_IMPORT, UploadSessionPurpose.fromString(""));
        assertEquals(UploadSessionPurpose.BATCH_IMPORT, UploadSessionPurpose.fromString("  "));
        assertEquals(UploadSessionPurpose.BATCH_IMPORT, UploadSessionPurpose.fromString("batchImport"));
        assertEquals(UploadSessionPurpose.BATCH_IMPORT, UploadSessionPurpose.fromString("BATCHIMPORT"));
    }

    @Test
    void shouldParseDocPurposesCaseInsensitive() {
        assertEquals(UploadSessionPurpose.DOC_EDIT, UploadSessionPurpose.fromString("docEdit"));
        assertEquals(UploadSessionPurpose.DOC_EDIT, UploadSessionPurpose.fromString("DOCEDIT"));
        assertEquals(UploadSessionPurpose.VERSION_OVERRIDE, UploadSessionPurpose.fromString("versionOverride"));
        assertEquals(UploadSessionPurpose.VERSION_OVERRIDE, UploadSessionPurpose.fromString(" VersionOverride "));
    }

    @Test
    void shouldRejectUnknownPurpose() {
        assertThrows(IllegalArgumentException.class, () -> UploadSessionPurpose.fromString("hackMode"));
        assertThrows(IllegalArgumentException.class, () -> UploadSessionPurpose.fromString("import"));
    }
}
