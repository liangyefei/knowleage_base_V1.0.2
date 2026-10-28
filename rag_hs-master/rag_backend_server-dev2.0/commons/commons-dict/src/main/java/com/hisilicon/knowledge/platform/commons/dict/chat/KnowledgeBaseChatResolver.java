package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 知识库对话解析器端口（跨模块解耦：knowledge 提供 impl，llm 注入）。
 *
 * <p>对话发送消息时，前端直接传入要检索的知识库 id 列表（{@code kbIds}，逗号分隔由 llm
 * 侧拆分为 List）。本解析器对前端传入的 kbIds 做「向量库就绪」守卫：仅保留
 * {@code status=1} 且 {@code process_status='COMPLETED'} 的知识库（向量库就绪可对话），
 * 返回按 {@code created_at} 升序的就绪子集（全量列表，不再取首个）。</p>
 *
 * <p>语义变更（相对旧版）：旧版按搜索范围（official 元数据）+ 芯片型号（chip_model 元数据）
 * + 文档类型/版本跨三层元数据解析「匹配」KB，取首个 id。现改为前端直传 KB id，解析器仅做
 * 就绪过滤——选择权交还前端，解析器只保证不把未就绪的 KB 传给模型。search_scope/
 * doc_types/version 不再进入解析器，仅随消息持久化并（受 {@code send-filters} 门控）下发模型。</p>
 *
 * <p>放于 commons-dict（llm 与 knowledge 均已依赖的属性/元数据共享层），避免 llm 编译依赖
 * knowledge（严守架构文档 §3.14「llm 不依赖 knowledge」）。接口仅返回 {@code List<String>}
 * （KB id），不泄漏 knowledge 领域类型。运行期由 service 聚合注入 knowledge 的实现。</p>
 */
public interface KnowledgeBaseChatResolver {

    /**
     * 对前端传入的知识库 id 列表做就绪过滤，返回可对话的就绪子集（全量列表）。
     *
     * @param kbIds 前端传入的知识库 id 列表（已拆分、已清洗）；空/null → 空列表
     *              （由调用方据此决定走纯 LLM /kw/chat/chat 退路）
     * @return 就绪（{@code status=1} 且 {@code process_status='COMPLETED'}）知识库 id 列表
     *         （{@code created_at} 升序）；无就绪返回空列表
     */
    Uni<List<String>> resolveKbIds(List<String> kbIds);
}
