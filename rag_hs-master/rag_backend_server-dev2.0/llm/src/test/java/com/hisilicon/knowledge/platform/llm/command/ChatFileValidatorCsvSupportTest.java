package com.hisilicon.knowledge.platform.llm.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话附件 csv 支持单测：与知识库文档上传白名单口径一致。
 * <p>只测纯静态逻辑（白名单 / 类型归类）；{@code validate} 内部经 LocaleUtil 走 CDI，
 * 归 QuarkusTest 链路覆盖（ChatResourceTest）。</p>
 */
class ChatFileValidatorCsvSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {"data.csv", "DATA.CSV", "芯片清单.Csv"})
    void csvShouldBeAllowedDocumentType(String fileName) {
        Optional<String> ext = ChatFileValidator.extensionOf(fileName);
        assertTrue(ext.isPresent() && ChatFileValidator.ALLOWED_EXTENSIONS.contains(ext.get()));
        assertEquals("DOCUMENT", ChatFileValidator.deduceFileType(fileName));
    }

    @Test
    void zipShouldStayRejected() {
        assertEquals("OTHER", ChatFileValidator.deduceFileType("bundle.zip"));
        assertTrue(!ChatFileValidator.ALLOWED_EXTENSIONS.contains("zip"));
    }
}
