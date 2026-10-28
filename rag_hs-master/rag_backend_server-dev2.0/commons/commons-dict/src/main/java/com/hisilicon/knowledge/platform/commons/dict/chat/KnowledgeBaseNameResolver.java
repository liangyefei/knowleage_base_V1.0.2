package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;

/**
 * 知识库名称解析器端口（跨模块解耦：knowledge 提供 impl，llm 注入）。
 *
 * <p>命中率仪表盘等统计接口需要把知识库 id（芯片标识）翻译为知识库名称（如
 * {@code diting → 谛听}）直接下发前端；llm 侧无 knowledge_bases 表访问能力，
 * 亦严守「llm 不编译依赖 knowledge」（架构文档 §3.14），故参照
 * {@link KnowledgeBaseChatResolver} 同一模式放于 commons-dict 共享层。</p>
 *
 * <p>接口仅返回 id→name 映射，不泄漏 knowledge 领域类型。运行期由 service 聚合注入
 * knowledge 的实现。</p>
 */
public interface KnowledgeBaseNameResolver {

    /**
     * 批量解析知识库名称。
     *
     * @param kbIds 知识库 id 列表；空/null → 空映射
     * @return id→name 映射（仅含能解析到的行；查不到的 id 由调用方决定回退语义）
     */
    Uni<Map<String, String>> resolveNames(List<String> kbIds);
}
