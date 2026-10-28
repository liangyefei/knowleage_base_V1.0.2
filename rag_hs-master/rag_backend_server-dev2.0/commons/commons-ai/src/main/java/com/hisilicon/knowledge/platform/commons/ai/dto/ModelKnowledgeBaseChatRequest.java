package com.hisilicon.knowledge.platform.commons.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型服务 multi_knowledge_base_chat 请求体（对应 /kw/chat/multi_knowledge_base_chat）
 *
 * <p>{@code knowledge_base_names} = 平台知识库 id 列表（即 Milvus collection 名），由前端直传 kbIds
 * 经 {@link com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver}
 * 向量库就绪过滤所得子集。</p>
 *
 * <p>遵循"能不传就不传"原则：类级 {@link JsonInclude.Include#NON_NULL}，任一字段为 null 时
 * 从 JSON 省略，由模型服务按自身 {@code configs/model_config.yaml} 默认值处理，避免后端
 * 覆盖默认值（如不再下发 {@code model_name=doubao-1.5-pro-32k-api}、{@code temperature}、
 * {@code top_k}、{@code score_threshold}、{@code max_tokens}、{@code prompt_name}）。
 * 唯一例外：{@code stream} 为原始类型 boolean 且恒为 true（SSE 流式必需，不可省略）。</p>
 *
 * <p>{@code deep_think} 模型已支持（见 {@code docs/智能问答接口.md}），直接下发（null 时省略=模型默认 false）。
 * {@code search_scope} / {@code chip_models} / {@code doc_types} / {@code version} 为规则2 预留字段：
 * search_scope/doc_types/version 由消息级配置取值并受 {@code kb.model.knowledge-base-chat.send-filters}
 * 控制是否实际下发（默认 false 时为 null 省略）；{@code chip_models} 现恒 null 省略（kbIds 已作为
 * knowledge_base_names 下发，芯片型号不再作为检索过滤字段）。</p>
 *
 * <p>{@code enable_internet_search} 联网搜索三态控制（模型已支持，见
 * {@code RAG问答接口变更说明.md} v1.0）：{@code true}=强制启用并融合知识库+联网结果、
 * {@code false}=强制禁用（知识库未命中也不搜）、{@code null}=自动模式（知识库未命中且模型端
 * 全局配置开启时才搜）。null 时经类级 NON_NULL 从 JSON 省略，正好落到模型端 null 默认=自动模式，
 * 与 {@code deep_think} 同一处理方式，直接下发、不受 send-filters 门控（仅本端点支持）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelKnowledgeBaseChatRequest(
        String query,
        @JsonProperty("knowledge_base_names") List<String> knowledgeBaseNames,
        @JsonProperty("user_id") String userId,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("score_threshold") Double scoreThreshold,
        List<ModelHistoryItem> history,
        boolean stream,
        @JsonProperty("model_name") String modelName,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("prompt_name") String promptName,
        @JsonProperty("search_scope") List<String> searchScope,
        @JsonProperty("chip_models") List<String> chipModels,
        @JsonProperty("deep_think") Boolean deepThinking,
        @JsonProperty("doc_types") List<String> docTypes,
        @JsonProperty("version") String version,
        @JsonProperty("enable_internet_search") Boolean enableInternetSearch
) {
}