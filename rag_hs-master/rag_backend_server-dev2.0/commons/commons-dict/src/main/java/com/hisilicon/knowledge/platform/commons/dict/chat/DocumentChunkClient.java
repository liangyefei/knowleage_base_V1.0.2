package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 文档切片模型调用端口（跨模块解耦：llm 提供 impl，knowledge 注入）。
 *
 * <p>按 {@code collection_name}（知识库 id）+ {@code filename}（模型挂载路径 {@code /kb-files/} + 文档相对路径）
 * 调用模型服务 {@code get_doc_by_filename}，返回文档切片列表。</p>
 *
 * <p>放于 commons-dict（llm 与 knowledge 均已依赖的共享层），避免 knowledge 编译依赖 llm（严守架构文档
 * §3.14「llm 不依赖 knowledge」，且保持 siblings 模块独立）。接口仅返回 {@code List<DocChunk>}（共享 DTO），
 * 不泄漏 llm 领域类型。运行期由 service 聚合注入 llm 的实现；knowledge 测试侧无 llm 实现类，由
 * MockDocumentChunkClient 满足 CDI 注入（同 MockKnowledgeBaseChatResolver 做法）。</p>
 *
 * <p>失败语义：模型非 200 或调用异常 → 抛 {@code BusinessException(C0001, modelServiceError)} 国际化消息，
 * 经 {@code BusinessExceptionMapper} 统一返回 {@code code=0} 外壳内 {@code code=C0001}。{@code locale}
 * 仅用于失败消息国际化。</p>
 */
public interface DocumentChunkClient {

    /**
     * 按知识库 + 文件名查询文档切片。
     *
     * @param collectionName 知识库 id（模型 {@code collection_name}）
     * @param fullFilename   模型挂载全路径（{@code /kb-files/} + 文档相对 filePath）
     * @param locale         语言环境（仅用于失败消息国际化）
     * @return 文档切片列表（模型 {@code data} 透传）；模型非 200/异常 → 抛 C0001
     */
    Uni<List<DocChunk>> getDocByFilename(String collectionName, String fullFilename, String locale);
}
