package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeFileListResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeGraphClient;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.command.DocCodeFilesCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphFragmentCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphKgCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphSearchCommand;
import com.hisilicon.knowledge.platform.knowledge.command.GraphSuggestCommand;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.KgNoResultException;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识图谱查询服务（《API-GRAPH.md》端点集的编排层，替代旧双服务择优编排）。
 *
 * <p>模型侧 dual-mcp 新端点：GET /projects、POST /graph_search、POST /graph_node、POST /graph_kg
 * （文档 KG 由 dual-mcp 代理 mempalace，平台不直连 mempalace）。本服务做参数 trim/校验、端口调用与
 * "空结果→A0430"转换；模型调用失败统一 C0001（commons-ai 实现侧转换）。与旧 /query 择优编排不同：新接口语义下
 * 前端自行并行查代码图谱与文档图谱两个视图，后端不再做择优合并。</p>
 *
 * <p>空结果语义（模型 200 正常响应，非失败）：graph_search {@code center=null 且 candidates 空}、
 * graph_node {@code node=null}、graph_kg {@code count=0} → {@link KgNoResultException}（A0430
 * "未找到相关信息"）；projects 空列表为正常态透传。graph_search 有 hint 的部分命中正常透传（前端展示提示）。</p>
 *
 * <p>排查日志：入口记参数；空结果/A0430 记 info；各模型调用的 url+请求参数+响应全文由 commons-ai 实现侧记录
 * （见 KnowledgeGraphClientImpl）。</p>
 */
@ApplicationScoped
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final KnowledgeGraphClient graphClient;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeGraphService(KnowledgeGraphClient graphClient,
                                 KnowledgeBaseRepository knowledgeBaseRepository) {
        this.graphClient = graphClient;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 已索引项目列表（透传，空列表为正常态）。
     */
    public Uni<GraphProjectsResult> projects(String locale) {
        log.info("Knowledge graph project list query");
        return graphClient.projects(locale);
    }

    /**
     * 代码图谱邻接查询。
     *
     * @return 邻接子图；无命中（center=null 且 candidates 空）→ A0430
     */
    public Uni<GraphSearchResult> search(GraphSearchCommand command, String locale) {
        String kbId = command.kbId().trim();
        String q = command.q().trim();
        log.info("Code graph adjacency query, kbId={}, q={}, limit={}, candidates={}", kbId, q, command.limit(),
                command.candidates());
        return graphClient.search(kbId, q, command.limit(), command.candidates(), locale)
                .flatMap(r -> {
                    boolean noHit = (r.center() == null)
                            && (r.candidates() == null || r.candidates().isEmpty());
                    if (noHit) {
                        log.info("Code graph has no hit, kbId={}, q={}, hint={}", kbId, q, r.hint());
                        return Uni.createFrom().failure(noResult(locale));
                    }
                    return Uni.createFrom().item(r);
                });
    }

    /**
     * 代码图谱节点详情。
     *
     * @return 节点详情；name 与 id 均缺 → A0420；node=null → A0430
     */
    public Uni<GraphNodeResult> node(GraphNodeCommand command, String locale) {
        String kbId = command.kbId().trim();
        // 模型 /graph_node 的 name 语义是**节点精确名**（2026-10 实测：Module 节点名为整条相对路径，
        // File/Document 节点名带扩展名；name="README" 查不到 name="README.md"）。故这里只 trim，
        // 不做截路径/去扩展名——那样会把 Module 节点的路径名截成 File 节点的文件名、把 "README.md"
        // 截成 "README"，导致查错节点或 node=null（A0430）。
        String name = command.name() == null ? null : command.name().trim();
        if ((name == null || name.isEmpty()) && command.id() == null) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, LocaleUtil.getAppMessage(locale).invalidParameter()));
        }
        log.info("Code graph node detail query, kbId={}, name={}, id={}, withSource={}", kbId, name, command.id(),
                command.withSource());
        return graphClient.node(kbId, name, command.id(), command.filePath(), command.withSource(), locale)
                .flatMap(r -> {
                    if (r.node() == null) {
                        log.info("Code graph node not found, kbId={}, name={}, id={}", kbId, name, command.id());
                        return Uni.createFrom().failure(noResult(locale));
                    }
                    return Uni.createFrom().item(r);
                });
    }

    /**
     * 文档知识图谱一跳邻接查询。
     *
     * @return 一跳邻接子图；count=0（实体不在图）→ A0430。kbId 可选：带=按库过滤（palace），缺省=全库
     */
    public Uni<GraphKgResult> kg(GraphKgCommand command, String locale) {
        String q = command.q().trim();
        String palace = command.kbId() == null || command.kbId().isBlank() ? null : command.kbId().trim();
        log.info("Document knowledge graph query, q={}, palace={}", q, palace);
        return graphClient.kg(palace, q, locale)
                .flatMap(r -> {
                    if (r.count() == null || r.count() == 0) {
                        log.info("Document knowledge graph has no result, q={}, hint={}", q, r.hint());
                        return Uni.createFrom().failure(noResult(locale));
                    }
                    return Uni.createFrom().item(r);
                });
    }

    /**
     * 搜索联想（双源：代码图谱 + 文档图谱）。
     *
     * <p>与搜索不同：联想无结果是<b>正常态</b>（空 suggestions 原样透传），不转 A0430——
     * 联想场景下每次击键都可能无命中，报错只会打断输入。</p>
     */
    public Uni<GraphSuggestResult> suggest(GraphSuggestCommand command, String locale) {
        String kbId = command.kbId().trim();
        String q = command.q().trim();
        log.info("Search suggestion query, kbId={}, q={}, limit={}", kbId, q, command.limit());
        return graphClient.suggest(kbId, kbId, q, command.limit(), locale);
    }

    /**
     * 已建立映射关系的文件清单（POST /doc_code/files，《API-DOCCODE-FILES.md》）。
     *
     * <p>与搜索类端点不同：{@code count=0}（该库尚无映射）为<b>数据真值</b>，正常透传不转 A0430——
     * codemap 页需要展示"暂无映射"空态而非报错。type 非法在平台侧前置拦截（A0420），不透传给模型。</p>
     */
    public Uni<DocCodeFileListResult> docCodeFiles(DocCodeFilesCommand command, String locale) {
        String kbId = command.kbId().trim();
        String type = command.type() == null || command.type().isBlank() ? null : command.type().trim();
        if (type != null && !"code".equals(type) && !"doc".equals(type)) {
            log.info("Doc-code file list rejected, invalid type, kbId={}, type={}", kbId, type);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, LocaleUtil.getAppMessage(locale).invalidDocCodeType()));
        }
        log.info("Doc-code mapped file list query, kbId={}, type={}", kbId, type);
        return graphClient.docCodeFiles(kbId, type, locale);
    }

    /**
     * 文档图谱关系片段查询（POST /graph_fragment，《API-GRAPH-FRAGMENT.md》2026-10-23）。
     *
     * <p>与 {@link #kg} 不同：{@code count=0}（关系来源文档未入 mempalace、无对应片段等）为<b>数据真值</b>
     * （文档 §2.6），正常透传空列表不转 A0430——图谱页「片段」按钮就地展开需要展示"暂无片段"空态而非报错，
     * 与 {@code docCodeFiles} 同策略。</p>
     *
     * <p>predicate/object 空白转 null（模型侧语义：缺省按「主体+客体」匹配，空串等价缺省，不透传多余字段）。</p>
     */
    public Uni<GraphFragmentResult> graphFragment(GraphFragmentCommand command, String locale) {
        String palace = command.kbId().trim();
        String subject = command.subject().trim();
        String predicate = blankToNull(command.predicate());
        String object = blankToNull(command.object());
        log.info("Graph fragment query, palace={}, subject={}, predicate={}, object={}",
                palace, subject, predicate, object);
        return graphClient.graphFragment(palace, subject, predicate, object, locale);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * doc_view 的 file 参数规整：按 kbId 查知识库取存储目录段（dirName），若 file 为本库存储相对路径
     * {@code kb/{dirName}/{相对路径}}，裁掉 {@code kb/{dirName}/} 前缀，转成模型项目内相对路径
     * （模型侧 /doc_view 的 file 语义为项目内相对路径，如 {@code hi_aiot_solution/...}）。
     *
     * <p>降级策略（不报错，模型侧 doc_view 自有 404 兜底）：kbId 查不到知识库（图谱可能含未在本平台
     * 注册的项目）、dirName 为空、或 file 不带该前缀（已是相对路径/指向其它库）→ 原样透传。</p>
     *
     * <p>只做前缀裁剪，不做 {@code ../} 清洗（模型侧 realpath 校验拦截越界）。</p>
     *
     * @param kbId    知识库 ID（= dual-mcp project）
     * @param rawFile 前端/图谱侧传入的 file 原值（可能为存储相对路径或已是项目相对路径）
     * @return 裁剪后的 file（规整失败时即原值）
     */
    @WithSession
    public Uni<String> normalizeDocViewFile(String kbId, String rawFile) {
        String file = rawFile.trim();
        if (file.startsWith("/")) {
            file = file.substring(1);
        }
        String candidate = file;
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().transform(entity -> {
                    if (entity == null || entity.getDirName() == null || entity.getDirName().isBlank()) {
                        log.warn("doc_view file normalize skipped, kb not found or dirName blank, kbId={}, file={}",
                                kbId, candidate);
                        return candidate;
                    }
                    String prefix = "kb/" + entity.getDirName() + "/";
                    if (candidate.startsWith(prefix)) {
                        String normalized = candidate.substring(prefix.length());
                        log.info("doc_view file normalize, kbId={}, dirName={}, rawFile={}, normalized={}",
                                kbId, entity.getDirName(), candidate, normalized);
                        return normalized;
                    }
                    // 已是项目相对路径（图谱返回的 file_path）或其它库前缀：原样透传
                    log.info("doc_view file normalize not applied (no storage prefix), kbId={}, dirName={}, file={}",
                            kbId, entity.getDirName(), candidate);
                    return candidate;
                })
                .onFailure().invoke(t -> log.warn(
                        "doc_view file normalize failed (kb lookup error), pass-through, kbId={}, file={}, error: {}",
                        kbId, candidate, t.toString()))
                .onFailure().recoverWithItem(candidate);
    }

    private static KgNoResultException noResult(String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return new KgNoResultException(appMessages.kgNoResult());
    }
}
