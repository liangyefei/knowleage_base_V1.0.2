package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingClient;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingQuery;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.command.DocCodeMappingCommand;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码↔文档映射查询服务（CQRS 查询侧，knowledge 门面 → dual-mcp {@code POST /doc_code}）。
 *
 * <p>版本解析口径与 download 完全一致：{@code version == currentVersion} 取
 * {@code documents.file_path}（当前版文件）；否则查 {@code document_versions} 版本行取
 * {@code file_path}；版本不存在 → A0430；版本行已下线（ARCHIVED/DEPRECATED）→ A0508。</p>
 *
 * <p><b>file 参数 = 模型项目内相对路径</b>：解析出的版本文件路径为存储相对路径
 * （{@code kb/{KB目录段}/{类别段…}/{文件}}），调用模型前裁掉 {@code kb/{KB目录段}/} 前缀
 * （模型项目根即 KB 存储目录，见 {@code GET /projects} 的 {@code root_path}；图谱 DOCUMENTS_CODE
 * 边的 file 亦为项目内相对路径）。裁剪规则与 doc_view 同源，见 {@link #normalizeModelFile}。
 * {@code project = kbId}（kbId == dual-mcp 项目名），{@code exact=false} 路径包含匹配。</p>
 *
 * <p><b>为什么必须裁前缀</b>：模型 {@code /doc_code} 在 {@code exact=false} 下按路径包含匹配，
 * 带 {@code kb/{目录段}/} 前缀时匹配不到任何文档，会退化为按文件名模糊匹配，返回一批同名文件的
 * 无关映射（count&gt;0，静默错数据）。2026-10 实测：{@code file=hi_aiot_solution/README.md}
 * 精确命中该文档 2 条映射；{@code file=kb/CV610ALL/hi_aiot_solution/README.md} 返回 10 条
 * 其它 README.md 的映射。</p>
 *
 * <p><b>空结果降级</b>：为避免用户看到假空，首次查询 count=0 时降级用文件名
 * （路径末段）重试一次，并在 hint 追加说明。</p>
 *
 * <p><b>方向自动纠正</b>：direction 入参仅做合法性校验（doc2code/code2doc，非法 A0420），
 * 实际方向按文档 docType 推导——docType=CODE → code2doc，否则 → doc2code。理由：
 * 前端第 3 步已按方向过滤文件树，用户只选择了文件，方向语义由 docType 推导比信任前端
 * 回传更稳（前端方向状态与所选文件错位时静默纠正，避免无意义失败）；file-options 树
 * 本身已按方向过滤，正常流程下二者一致。</p>
 *
 * <p>模型调用经 {@link DocCodeMappingClient} 端口（llm 实现调 /doc_code，读出并缓冲
 * 非 2xx 响应体）。模型错误转换：404（项目图谱/种子文件不存在）→ A0430；
 * 400/422（模型参数校验失败）→ A0420 且 message 携带模型响应体 detail（截断）；
 * 其余（5xx/网络/null）→ C0001 modelServiceError。成功响应（含 count=0 + hint）原样
 * 透传给门面，由门面包 {@code ResponseResult} 信封。</p>
 */
@ApplicationScoped
public class DocCodeMappingQueryService {

    private static final Logger logger = LoggerFactory.getLogger(DocCodeMappingQueryService.class);

    /** 方向常量（与 DocumentGlobalSearchQueryService 同值，文件下拉接口约定的方向语义） */
    public static final String DIRECTION_DOC2CODE = "doc2code";
    public static final String DIRECTION_CODE2DOC = "code2doc";

    /** 模型响应体 detail 拼入 message 的最大长度（防超长错误体撑爆响应） */
    private static final int MAX_DETAIL_LENGTH = 300;

    private final DocumentRepository documentRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocCodeMappingClient docCodeMappingClient;

    public DocCodeMappingQueryService(DocumentRepository documentRepository,
                                      CategoryPanacheRepository categoryPanacheRepository,
                                      KnowledgeBaseRepository knowledgeBaseRepository,
                                      DocCodeMappingClient docCodeMappingClient) {
        this.documentRepository = documentRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.docCodeMappingClient = docCodeMappingClient;
    }

    /**
     * 按文档 + 版本 + 方向查询代码↔文档映射。
     *
     * @param command documentId/version/direction 必填；withSource/withDoc/limit 可选透传
     * @param locale  语言环境（错误提示 i18n）
     * @return 模型映射结果（直接对象，门面负责 ResponseResult 信封）
     */
    @WithSession
    public Uni<DocCodeMappingResult> map(DocCodeMappingCommand command, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String dir = command.direction() == null ? null : command.direction().trim();
        if (!DIRECTION_DOC2CODE.equals(dir) && !DIRECTION_CODE2DOC.equals(dir)) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidDirectionValue()));
        }
        String documentId = command.documentId().trim();
        return documentRepository.findById(DocumentId.of(documentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document not found: id={}", documentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> assertVisible(entity, appMessages).replaceWith(entity))
                .flatMap(entity -> resolveVersionFilePath(entity, command.version(), locale)
                        // 存储路径 → 模型项目内相对路径（裁 kb/{KB目录段}/ 前缀），见 normalizeModelFile
                        .flatMap(filePath -> normalizeModelFile(entity.getKbId(), filePath)
                                .flatMap(modelFile -> {
                                    // direction 语义自动纠正（见类 javadoc）：docType=CODE → code2doc
                                    String effectiveDirection =
                                            DocumentEntity.DOC_TYPE_CODE.equals(entity.getDocType())
                                                    ? DIRECTION_CODE2DOC : DIRECTION_DOC2CODE;
                                    return callModel(modelFile, entity, effectiveDirection,
                                            command, locale, appMessages);
                                })));
    }

    /**
     * 下载可见性守卫同款：孤儿文档（categoryId 指向已删/不存在/跨库类别）视同不存在。
     */
    private Uni<Void> assertVisible(DocumentEntity entity, AppMessages appMessages) {
        if (entity.getCategoryId() == null) {
            return Uni.createFrom().voidItem();
        }
        return categoryPanacheRepository.count("id = ?1 and kbId = ?2 and status = 1",
                        entity.getCategoryId(), entity.getKbId())
                .flatMap(c -> {
                    if (c > 0) {
                        return Uni.createFrom().voidItem();
                    }
                    logger.error("Orphan document not visible: documentId={}, categoryId={}",
                            entity.getId().value(), entity.getCategoryId());
                    return Uni.createFrom().failure(new DocumentNotFoundException(appMessages.documentNotFound()));
                });
    }

    /**
     * 版本解析（与 download 同口径）：当前版取 documents.file_path；历史版查 document_versions
     * 版本行（不存在 A0430、已下线 ARCHIVED/DEPRECATED A0508）取其 file_path。
     * 历史版本行 file_path 与当前版同为存储相对路径，剥离逻辑共用。
     */
    private Uni<String> resolveVersionFilePath(DocumentEntity entity, String version, String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String target = version == null ? null : version.trim();
        if (target.equals(entity.getCurrentVersion())) {
            // 当前版：文件取 documents 行（版本表无该行时的正常态）
            return Uni.createFrom().item(entity.getFilePath());
        }
        return documentRepository.findVersion(entity.getId().value(), target)
                .onItem().ifNull().failWith(() -> {
                    logger.error("Document version not found: documentId={}, version={}", entity.getId().value(), target);
                    return new DocumentNotFoundException(appMessages.documentVersionNotFound());
                })
                .flatMap(v -> {
                    String status = v.status() != null ? v.status() : DocumentVersion.STATUS_PUBLISHED;
                    if (!DocumentVersion.STATUS_PUBLISHED.equals(status)) {
                        String message = DocumentVersion.STATUS_ARCHIVED.equals(status)
                                ? appMessages.versionArchived() : appMessages.versionDeprecated();
                        logger.warn("Version already offline, mapping rejected: documentId={}, version={}, status={}",
                                entity.getId().value(), target, status);
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0508, message));
                    }
                    return Uni.createFrom().item(v.filePath());
                });
    }

    /**
     * 存储相对路径 → 模型项目内相对路径：裁掉 {@code kb/{KB目录段}/} 前缀。
     *
     * <p>模型 {@code /doc_code} 的 file 语义为项目内相对路径（模型项目根 = KB 存储目录
     * {@code kb/{dirName}}，见 {@code GET /projects} 的 {@code root_path}；代码图谱 DOCUMENTS_CODE
     * 边与 {@code /graph_node} 返回的 file_path 同为该口径）。裁剪规则与 doc_view
     * （{@code KnowledgeGraphService#normalizeDocViewFile}）同源。</p>
     *
     * <p>降级（不报错、不阻断映射，模型自会返回未命中 hint）：KB 查不到、dirName 为空
     * （存量行）、file 不带该前缀（已是项目相对路径）→ 原样透传；查询异常同样透传原值。</p>
     */
    private Uni<String> normalizeModelFile(String kbId, String rawFile) {
        String file = rawFile == null ? "" : rawFile.trim();
        if (file.isEmpty()) {
            return Uni.createFrom().item(file);
        }
        String candidate = file.startsWith("/") ? file.substring(1) : file;
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().transform(entity -> {
                    if (entity == null || entity.getDirName() == null || entity.getDirName().isBlank()) {
                        logger.warn("doc_code file normalize skipped, kb not found or dirName blank, kbId={}, file={}",
                                kbId, candidate);
                        return candidate;
                    }
                    String prefix = "kb/" + entity.getDirName() + "/";
                    if (candidate.startsWith(prefix)) {
                        String normalized = candidate.substring(prefix.length());
                        logger.info("doc_code file normalize, kbId={}, dirName={}, rawFile={}, normalized={}",
                                kbId, entity.getDirName(), candidate, normalized);
                        return normalized;
                    }
                    logger.info("doc_code file normalize not applied (no storage prefix), kbId={}, dirName={}, file={}",
                            kbId, entity.getDirName(), candidate);
                    return candidate;
                })
                .onFailure().invoke(t -> logger.warn(
                        "doc_code file normalize failed (kb lookup error), pass-through, kbId={}, file={}, error: {}",
                        kbId, candidate, t.toString()))
                .onFailure().recoverWithItem(candidate);
    }

    /** 路径末段文件名（空结果重试用） */
    private static String baseName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * 经端口调模型 /doc_code：file=存储相对路径原样透传（不剥离），project=kbId，
     * exact=false 路径包含匹配（模型无 version 入参，版本差异体现在 file_path 上）。
     * <p>首查 count=0（或缺失）时按文件名重试一次（图谱路径口径不确定的兜底），
     * 重试结果 hint 追加「未找到相关信息」提示，避免假空。</p>
     * 成功响应透传；404→A0430、400/422→A0420（含模型响应体 detail）、其余→C0001。
     */
    private Uni<DocCodeMappingResult> callModel(String modelFile, DocumentEntity entity, String direction,
                                                DocCodeMappingCommand command, String locale,
                                                AppMessages appMessages) {
        String fileName = baseName(modelFile);
        return doCall(modelFile, entity, direction, command, appMessages)
                .flatMap(first -> {
                    if (!isEmptyResult(first) || fileName.equals(modelFile)) {
                        return Uni.createFrom().item(first);
                    }
                    logger.info("Path not matched, retrying by file name, documentId={}, file={}, fileName={}",
                            entity.getId().value(), modelFile, fileName);
                    return doCall(fileName, entity, direction, command, appMessages)
                            .map(retry -> {
                                // 重试仍未命中才追加提示；命中则原样透传
                                if (isEmptyResult(retry)) {
                                    String hint = (retry.hint() == null ? "" : retry.hint() + "；")
                                            + appMessages.kgNoResult();
                                    return new DocCodeMappingResult(retry.file(), retry.project(),
                                            retry.direction(), retry.matched(), retry.count(),
                                            retry.results(), hint, retry.elapsedMs());
                                }
                                return retry;
                            });
                });
    }

    private static boolean isEmptyResult(DocCodeMappingResult result) {
        return result.count() == null || result.count() == 0;
    }

    private Uni<DocCodeMappingResult> doCall(String file, DocumentEntity entity, String direction,
                                             DocCodeMappingCommand command, AppMessages appMessages) {
        DocCodeMappingQuery query = new DocCodeMappingQuery(
                file, entity.getKbId(), direction,
                command.limit(), command.withSource(), command.withDoc(),
                Boolean.FALSE);
        logger.info("Call dual-mcp code↔document mapping, documentId={}, version={}, file={}, project={}, direction={}",
                entity.getId().value(), command.version(), file, entity.getKbId(), direction);
        return docCodeMappingClient.docCode(query)
                .onFailure().invoke(t -> logger.error("dual-mcp /doc_code call failed, documentId={}, file={}, error: {}",
                        entity.getId().value(), file, t.toString(), t))
                .onFailure().transform(t -> toBusinessException(t, appMessages))
                .onItem().transform(result -> {
                    // count=0 + hint 亦属成功：随响应透传，由前端展示提示
                    logger.info("dual-mcp /doc_code response, documentId={}, matched={}, count={}, hint={}",
                            entity.getId().value(), result.matched(), result.count(), result.hint());
                    return result;
                });
    }

    /**
     * 模型调用异常 → 平台业务异常：404→A0430；400/422→A0420 且 message 携带模型响应体
     * detail（响应体已由 llm 端口实现 bufferEntity 缓冲）；其余→C0001。
     */
    private static BusinessException toBusinessException(Throwable t, AppMessages appMessages) {
        if (t instanceof BusinessException be) {
            // 端口实现已转换的业务异常原样传播
            return be;
        }
        if (t instanceof WebApplicationException wae) {
            int status = wae.getResponse() != null ? wae.getResponse().getStatus() : 0;
            String detail = readBody(wae);
            if (status == 404) {
                return new BusinessException(ErrorCodeEnum.USER_ERROR_A0430, appMessages.kgNoResult());
            }
            if (status == 400 || status == 422) {
                logger.error("Model parameter validation failed, status={}, detail={}", status, detail);
                String message = appMessages.invalidParameter() + ": " + truncate(detail);
                return new BusinessException(ErrorCodeEnum.USER_ERROR_A0420, message);
            }
        }
        return new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, appMessages.modelServiceError(), t);
    }

    /** 读取（已由端口实现缓冲的）模型错误响应体；读取失败返回 null */
    private static String readBody(WebApplicationException wae) {
        try {
            if (wae.getResponse() == null) {
                return null;
            }
            Object entity = wae.getResponse().getEntity();
            if (entity instanceof String s) {
                return s;
            }
            // 真实 REST Client 异常：响应体由 llm 端口实现 bufferEntity 缓冲后可重复读
            wae.getResponse().bufferEntity();
            return wae.getResponse().readEntity(String.class);
        } catch (jakarta.ws.rs.ProcessingException | java.lang.IllegalStateException e) {
            return null;
        }
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
}
