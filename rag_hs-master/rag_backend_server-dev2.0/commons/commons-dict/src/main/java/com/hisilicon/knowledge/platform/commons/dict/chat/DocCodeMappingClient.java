package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

/**
 * 代码↔文档映射查询端口（dual-mcp {@code POST /doc_code}，commons-ai 侧实现，knowledge 侧消费）。
 *
 * <p>与 {@link KnowledgeGraphClient} 同模式：端口与端口 DTO 定义在 commons-dict，
 * commons-ai 提供真实实现（{@code KgServiceApi#docCode}），service 模块运行期聚合注入；
 * knowledge 测试侧以 {@code @Mock} 桩覆盖。knowledge 模块编译期不依赖 llm。</p>
 */
public interface DocCodeMappingClient {

    /**
     * 调 dual-mcp {@code POST /doc_code}。
     *
     * @param query 映射查询参数（file 必填，project=kbId，direction=doc2code/code2doc）
     * @return 映射结果（直接对象透传，无信封）；模型非 2xx 的 {@code WebApplicationException}
     *         原样抛出（由 knowledge 门面按 HTTP 状态码转换为业务错误码），其余调用异常包装为 C0001
     */
    Uni<DocCodeMappingResult> docCode(DocCodeMappingQuery query);
}
