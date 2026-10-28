package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型服务 chat 请求体（对应 /kw/chat/chat，纯 LLM 对话）
 *
 * <p>遵循"能不传就不传"原则：类级 {@link JsonInclude.Include#NON_NULL}，任一字段为 null 时
 * 从 JSON 省略，由模型服务按自身 {@code configs/model_config.yaml} 默认值处理，避免后端
 * 覆盖默认值（如不再下发 {@code model_name}、{@code temperature}、{@code conversation_id}、
 * {@code history_len}、{@code max_tokens}、{@code prompt_name}）。唯一例外：{@code stream} 为
 * 原始类型 boolean 且恒为 true（SSE 流式必需，不可省略）。</p>
 *
 * <p>{@code deep_think} 模型已支持（见 {@code docs/智能问答接口.md}），直接下发（null 时省略=模型默认 false）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelChatRequest(
        String query,
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("history_len") Integer historyLen,
        List<ModelHistoryItem> history,
        boolean stream,
        @JsonProperty("model_name") String modelName,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("prompt_name") String promptName,
        @JsonProperty("deep_think") Boolean deepThinking
) {
}