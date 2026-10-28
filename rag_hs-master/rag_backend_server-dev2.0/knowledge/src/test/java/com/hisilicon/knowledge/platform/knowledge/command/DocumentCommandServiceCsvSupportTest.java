package com.hisilicon.knowledge.platform.knowledge.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * csv 支持单测：上传白名单放行 csv，且归类为 DOCUMENT（普通上传 / zip 条目 / tus 批量导入
 * 共用 {@link DocumentCommandService#isExtensionAllowed}，改一处全链路生效）。
 */
class DocumentCommandServiceCsvSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {"data.csv", "DATA.CSV", "芯片清单.Csv", "a.b.csv"})
    void shouldAllowCsvExtension(String fileName) {
        assertTrue(DocumentCommandService.isExtensionAllowed(
                fileName, DocumentCommandService.extractExtension(fileName)));
    }

    @Test
    void shouldClassifyCsvAsDocument() {
        assertEquals("DOCUMENT", DocumentCommandService.classify("data.csv"));
        assertEquals("DOCUMENT", DocumentCommandService.classify("DATA.CSV"));
    }

    @Test
    void shouldStripCsvExtensionForTitle() {
        assertEquals("芯片清单", DocumentCommandService.stripExtension("芯片清单.csv"));
    }

    @Test
    void csvShouldNotAffectZipOrMakefileRules() {
        // zip 压缩包本身仍走独立特判（仅批量导入放行），不在普通白名单内
        assertFalse(DocumentCommandService.isExtensionAllowed("bundle.zip",
                DocumentCommandService.extractExtension("bundle.zip")));
        assertTrue(DocumentCommandService.isExtensionAllowed("makefile", ""));
    }
}
