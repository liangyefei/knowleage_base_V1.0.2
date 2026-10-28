package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试：内容命中片段组装（{@link DocumentContentIndexService#buildSnippet} 纯函数）
 */
class DocumentContentIndexServiceSnippetTest {

    @Test
    void shouldReturnEmptyForNullOrBlankInputs() {
        assertTrue(DocumentContentIndexService.buildSnippet(null, "kw").isEmpty());
        assertTrue(DocumentContentIndexService.buildSnippet("正文", null).isEmpty());
        assertTrue(DocumentContentIndexService.buildSnippet("正文", "   ").isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenKeywordAbsent() {
        assertTrue(DocumentContentIndexService.buildSnippet("正文不含关键词", "缺失").isEmpty());
    }

    @Test
    void shouldMatchCaseInsensitively() {
        String snippet = DocumentContentIndexService.buildSnippet("GPIO register CONFIG detail", "config").orElseThrow();
        assertEquals("GPIO register CONFIG detail", snippet);
    }

    @Test
    void shouldClipAroundFirstOccurrenceWithBoundary() {
        // 短文本：首个命中在开头/结尾，片段为全文
        String text = "前置寄存器与后置";
        assertEquals(text, DocumentContentIndexService.buildSnippet(text, "寄存器").orElseThrow());
    }

    @Test
    void shouldClipLongTextWithContextChars() {
        String head = "甲".repeat(300);
        String tail = "乙".repeat(300);
        String text = head + "关键词" + tail;
        String snippet = DocumentContentIndexService.buildSnippet(text, "关键词").orElseThrow();
        int ctx = DocumentContentIndexService.SNIPPET_CONTEXT_CHARS;
        // 命中前有 300 字符：只保留前 100；命中后同理
        assertEquals("甲".repeat(ctx) + "关键词" + "乙".repeat(ctx), snippet);
        assertTrue(snippet.length() == 2 * ctx + 3);
        assertFalse(snippet.contains("丙"));
    }
}
