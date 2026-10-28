package com.hisilicon.knowledge.platform.commons.dict.chat;

import io.smallrye.mutiny.Uni;

/**
 * 知识图谱模型调用端口（《API-GRAPH.md》dual-mcp 图谱端点集，跨模块解耦：llm 提供 impl，knowledge 注入）。
 *
 * <p>覆盖 6 个模型端点：{@code GET /projects}、{@code POST /graph_search}（代码图谱）、
 * {@code POST /graph_node}（代码图谱节点详情）、{@code POST /graph_kg}（文档知识图谱，dual-mcp 代理
 * mempalace，公网/平台侧不直连 mempalace）、{@code POST /graph_suggest}（搜索联想双源）、
 * {@code POST /graph_fragment}（关系来源文档片段，《API-GRAPH-FRAGMENT.md》）。各端点响应均为
 * 直接对象（无 code/msg/data 信封），HTTP 200=成功。</p>
 *
 * <p>放于 commons-dict（llm 与 knowledge 均已依赖的共享层），避免 knowledge 编译依赖 llm（严守架构文档
 * §3.14「llm 不依赖 knowledge」）。接口仅返回本包共享 DTO，不泄漏 llm 领域类型。运行期由 service 聚合注入
 * llm 的实现；knowledge 测试侧无 llm 实现类，由 MockKnowledgeGraphClient 满足 CDI 注入。</p>
 *
 * <p>失败语义：模型非 200 或调用异常 → 抛 {@code BusinessException(C0001, modelServiceError)} 国际化消息，
 * 经 {@code BusinessExceptionMapper} 统一返回 {@code code=0} 外壳内 {@code code=C0001}。{@code locale}
 * 仅用于失败消息国际化。"空结果"语义（graph_search center=null、graph_kg count=0）为模型侧 200 正常响应，
 * 非失败，由编排层决定是否转 A0430。</p>
 */
public interface KnowledgeGraphClient {

    /**
     * 已索引项目列表（GET /projects）。
     *
     * @param locale 语言环境（仅用于失败消息国际化）
     * @return 项目列表（含 nodes/edges 规模与 kb_name）；非 200/异常 → 抛 C0001
     */
    Uni<GraphProjectsResult> projects(String locale);

    /**
     * 代码图谱邻接查询（POST /graph_search）。
     *
     * @param project    项目（= kbId，模型侧亦接受项目名，平台统一传 kbId）
     * @param q          实体关键字（符号名/文档名/文件名，模型侧支持部分匹配）
     * @param limit      邻接边数上限（1–500，null 走模型默认 80）
     * @param candidates 同名/近义其它匹配实体数（0–20，null 走模型默认 6）
     * @param locale     语言环境（仅用于失败消息国际化）
     * @return 邻接子图（center/candidates/nodes/edges/hint）；非 200/异常 → 抛 C0001
     */
    Uni<GraphSearchResult> search(String project, String q, Integer limit, Integer candidates, String locale);

    /**
     * 代码图谱节点详情（POST /graph_node）。
     *
     * @param project    项目（= kbId）
     * @param name       实体名（id 为 null 时必填）
     * @param id         节点 id（图上点击时携带，优先于 name，可为 null）
     * @param filePath   同名消歧提示（后缀匹配，可为 null）
     * @param withSource 是否附源码段（null 走模型默认 true）
     * @param locale     语言环境（仅用于失败消息国际化）
     * @return 节点详情（属性/邻边统计/源码段）；非 200/异常 → 抛 C0001
     */
    Uni<GraphNodeResult> node(String project, String name, Integer id, String filePath, Boolean withSource,
                              String locale);

    /**
     * 文档知识图谱一跳邻接查询（POST /graph_kg，dual-mcp 代理 mempalace）。
     *
     * @param palace 知识库 id（kbId）。带=仅查该库图谱；null=全库（向后兼容，模型按库过滤 2026-10 上线）
     * @param q      KG 实体名（精确匹配：文档全名/kbId/普通实体）
     * @param locale 语言环境（仅用于失败消息国际化）
     * @return 一跳邻接子图（nodes/edges/count/hint，palace 回显）；200+count=0 表示实体不在图（非失败）；非 200/异常 → 抛 C0001
     */
    Uni<GraphKgResult> kg(String palace, String q, String locale);

    /**
     * 搜索框联想（POST /graph_suggest，双源：代码图谱 + 文档图谱）。
     *
     * @param project 项目（= kbId，代码源作用域，可为 null——KG 为全局单图不受 project 限制）
     * @param palace  知识库 id（= kbId，KG 源按库过滤，可为 null 走模型缺省推导；平台显式传 kbId）
     * @param q       实体名片段
     * @param limit   候选条数（1–30，null 走模型默认 10）
     * @param locale  语言环境（仅用于失败消息国际化）
     * @return 候选列表（code 优先、kg 补充）；空列表为正常态透传；非 200/异常 → 抛 C0001
     */
    Uni<GraphSuggestResult> suggest(String project, String palace, String q, Integer limit, String locale);

    /**
     * 文档图谱关系片段查询（POST /graph_fragment，《API-GRAPH-FRAGMENT.md》2026-10-23；
     * dual-mcp 代理 mempalace）。
     *
     * <p>入参 subject/predicate/object 来自 /graph_kg 响应 edges[]（前端在图谱页关系边面板持有后传入，
     * 后端不做二次调用）。</p>
     *
     * @param palace    知识库 id（kbId，必填），片段只在该库范围内查找
     * @param subject   关系主体实体名（必填）
     * @param predicate 关系谓词（可选，null/空白=模型按「主体+客体」匹配）
     * @param object    关系客体实体名（可选）
     * @param locale    语言环境（仅用于失败消息国际化）
     * @return 片段列表（fragments[].docViewUrl 已重写为平台 /kg/doc-view 查询串）；
     *         count=0 为数据真值（关系来源文档未入 mempalace 等），正常透传；非 200/异常 → 抛 C0001
     */
    Uni<GraphFragmentResult> graphFragment(String palace, String subject, String predicate, String object,
                                           String locale);

    /**
     * 已建立映射关系的文件清单（POST /doc_code/files，《API-DOCCODE-FILES.md》；codemap 页浏览映射全貌）。
     *
     * @param project 项目（= kbId，模型侧亦接受项目名，平台统一传 kbId）
     * @param type    列表类型：code=被文档引用过的代码文件（模型默认）；doc=引用过代码符号的文档文件；null=模型默认
     * @param locale  语言环境（仅用于失败消息国际化）
     * @return 文件清单（project/type/count/files/elapsed_ms，files[].url 已重写为平台 /kg/doc-view 查询串）；
     *         count=0 为数据真值（该库尚无映射），正常透传；非 200/异常 → 抛 C0001
     */
    Uni<DocCodeFileListResult> docCodeFiles(String project, String type, String locale);
}
