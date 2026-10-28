package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelKnowledgeBaseChatRequest} 序列化契约单测（wire 字段名 + null 省略三态）。
 *
 * <p>重点验证本次新增的 {@code enable_internet_search} 三态：{@code true}/{@code false} 以
 * snake_case 下发；{@code null} 因类级 {@code NON_NULL} 从 JSON 省略——正好落到模型端
 * {@code null} 默认即"自动模式"，与 {@code deep_think} 同一处理方式。</p>
 */
class ModelKnowledgeBaseChatRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ModelKnowledgeBaseChatRequest requestWith(Boolean enableInternetSearch) {
        return new ModelKnowledgeBaseChatRequest(
                "今天杭州天气", List.of("kb-001"), "user-1", null, null, List.of(), true,
                null, null, null, null, null, null, null, null, null, enableInternetSearch);
    }

    /** true → 下发 snake_case 字段，值为 true */
    @Test
    void shouldSerializeInternetSearchTrue() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(requestWith(Boolean.TRUE)));
        assertTrue(node.has("enable_internet_search"), "字段名应为 enable_internet_search");
        assertTrue(node.get("enable_internet_search").asBoolean());
        // 必填字段仍在
        assertEquals("今天杭州天气", node.get("query").asText());
        assertEquals("kb-001", node.get("knowledge_base_names").get(0).asText());
        assertTrue(node.get("stream").asBoolean(), "stream 恒 true（SSE 必需）");
    }

    /** false → 显式下发 false（与 null=自动模式区分，模型据此永不联网） */
    @Test
    void shouldSerializeInternetSearchFalse() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(requestWith(Boolean.FALSE)));
        assertTrue(node.has("enable_internet_search"), "false 也必须下发，不能被省略");
        assertFalse(node.get("enable_internet_search").asBoolean());
    }

    /** null → JSON 省略（模型端取默认 null=自动模式：知识库未命中且全局配置开启才搜） */
    @Test
    void shouldOmitInternetSearchWhenNull() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(requestWith(null)));
        assertFalse(node.has("enable_internet_search"), "null 应省略，交由模型端默认（自动模式）");
    }
}