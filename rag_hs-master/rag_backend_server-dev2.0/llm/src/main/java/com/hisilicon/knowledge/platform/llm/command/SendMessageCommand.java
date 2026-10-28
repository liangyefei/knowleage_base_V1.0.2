package com.hisilicon.knowledge.platform.llm.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发送消息命令
 *
 * <p>消息级配置（规则2）：searchKbEnabled/searchExternalEnabled/kbIds/deepThinking
 * /docTypes/version 均可选，前端每次发消息带上当前配置区状态。非 null 时覆盖默认；省略（null）
 * 时用硬编码默认（searchKb/searchExternal=true、kbIds/docTypes=null=不限/全部、deepThinking=false、
 * version=null=最新版）。逐字段覆盖，不要求全传。</p>
 *
 * @param conversationId        会话 ID（必填）
 * @param content               消息内容（≤4000 字；无文件时必填，有文件可省略，由模型基于上传文件作答）
 * @param searchKbEnabled       官方库开关（消息级覆盖；null=默认 true。仅随消息持久化 + rule-2 下发模型 search_scope）
 * @param searchExternalEnabled 非官方库开关（消息级覆盖；null=默认 true。同上）
 * @param kbIds                 知识库 id，逗号分隔（如 "kb001,kb002"；null/空→走纯 LLM /kw/chat/chat）。
 *                              前端直传要检索的知识库 id；经就绪过滤后作为 knowledge_base_names 下发 multi 端点
 * @param deepThinking          深度思考（消息级覆盖；null=默认 false）
 * @param docTypes              文档类型（逗号分隔，DOCUMENT/CODE/CONFIG/OTHER；null=全部类型。
 *                              仅随消息持久化 + rule-2 下发模型 doc_types，仅 multi 路径生效）
 * @param version               文档版本（如 "v2"/"1.0"；null=最新版。同 docTypes）
 * @param enableInternetSearch  联网搜索三态（消息级；null=自动模式，知识库未命中且模型端全局配置
 *                              开启时才搜 / true=强制启用并融合知识库+联网结果 / false=强制禁用）。
 *                              仅 multi 路径（/kw/chat/multi_knowledge_base_chat）下发，不持久化
 * @param source                来源渠道标识（可选；null/空=CHAT_QA 智能问答。
 *                              允许值：CHAT_QA 智能问答 / RECEPTION_BOT 前台机器人 / AGENT_CALL Agent 调用。
 *                              仅随 qa_kb_stats 统计行持久化，供命中率仪表盘按来源筛选）
 */
public record SendMessageCommand(
        @NotBlank(message = "{app.chat.conversationId.notblank}")
        String conversationId,

        @Size(max = 4000, message = "{app.chat.content.size}")
        String content,

        Boolean searchKbEnabled,

        Boolean searchExternalEnabled,

        String kbIds,

        Boolean deepThinking,

        String docTypes,

        String version,

        Boolean enableInternetSearch,

        String source
) {
}
