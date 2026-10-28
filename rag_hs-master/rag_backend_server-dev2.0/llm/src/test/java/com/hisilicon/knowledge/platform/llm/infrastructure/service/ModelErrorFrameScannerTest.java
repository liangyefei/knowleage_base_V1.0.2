package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelErrorFrameScanner 单测（纯 JUnit，无容器）：
 * 错误帧识别、answer/正常载荷不误判、内容安全文案判定、解析降级。
 */
class ModelErrorFrameScannerTest {

    private final ModelErrorFrameScanner scanner = new ModelErrorFrameScanner(new ObjectMapper());

    /** 真实形态：模型内容安全拦截帧（route_reasoning 之后的那一帧） */
    @Test
    void shouldDetectContentSafetyErrorFrame() {
        String error = scanner.feed(
                "{\"error\": \"生成内容被内容安全系统拦截，请调整问题后重试\", \"message_id\": \"\"}");

        assertEquals("生成内容被内容安全系统拦截，请调整问题后重试", error);
        assertTrue(ModelErrorFrameScanner.isContentSafety(error));
    }

    /** answer / docs / route_reasoning / 纯文本 载荷都不是错误帧 */
    @Test
    void shouldIgnoreNonErrorFrames() {
        assertNull(scanner.feed("{\"type\":\"answer\",\"answer\":\"正常回答\",\"message_id\":\"\"}"));
        assertNull(scanner.feed("{\"type\":\"route_reasoning\",\"content\":\"已检索知识库\"}"));
        assertNull(scanner.feed("{\"type\":\"docs\",\"docs\":[]}"));
        assertNull(scanner.feed("纯文本回答"));
        assertNull(scanner.feed("这不是 JSON"));
    }

    /** answer 载荷内出现 error 字段不算流级错误帧（回答文本优先） */
    @Test
    void shouldIgnoreAnswerFrameCarryingErrorField() {
        assertNull(scanner.feed("{\"type\":\"answer\",\"answer\":\"error 字段\",\"error\":\"内容安全系统\"}"));
    }

    /** error 为 null / 非文本 / 空白 → 不视为错误帧（避免误中止生成） */
    @Test
    void shouldIgnoreBlankErrorValue() {
        assertNull(scanner.feed("{\"error\":null,\"message_id\":\"\"}"));
        assertNull(scanner.feed("{\"error\":\"  \",\"message_id\":\"\"}"));
        assertNull(scanner.feed("{\"error\":{\"detail\":\"x\"}}"));
        assertNull(scanner.feed(""));
        assertNull(scanner.feed(null));
    }

    /** 非内容安全文案 → 通用错误（下发 event:error，不误标涉敏） */
    @Test
    void shouldNotTreatOtherModelErrorsAsContentSafety() {
        String error = scanner.feed("{\"error\":\"上游模型服务超时，请稍后重试\"}");

        assertEquals("上游模型服务超时，请稍后重试", error);
        assertFalse(ModelErrorFrameScanner.isContentSafety(error));
        assertFalse(ModelErrorFrameScanner.isContentSafety(null));
        assertFalse(ModelErrorFrameScanner.isContentSafety("  "));
    }
}
