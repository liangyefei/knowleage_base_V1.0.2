package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.commons.web.security.OperatorIdHolder;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentStoragePathResolver;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentFileContentCommand;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.MarkdownImageRewriter;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文件内容查看接口（按知识库 ID + 模型侧项目内相对路径取文件内容）
 *
 * <p><b>为什么需要它</b>：模型侧（代码图谱等）给出的文件标识是<b>项目内相对路径</b>
 * （如 {@code hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c}），
 * 不带平台 {@code documentId}，因此走不了 {@link DocumentResource#download}。
 * 平台落盘路径为 {@code kb/{KB物理目录段}/{项目内相对路径}}，其中 KB 物理目录段由 kbId 查库得出
 * （{@code knowledge_bases.dir_name}，创建时唯一化定格，存量 NULL 回退按名称——与上传落盘同源）。</p>
 *
 * <p><b>与 download 的关系</b>：响应形态一致（原始文件字节 + {@code Content-Type} +
 * {@code Content-Disposition}），md 文件同样把相对图片引用重写为免登录签名 URL
 * （{@link MarkdownImageRewriter} + {@link DocumentAssetResource}）。差异只在定位方式与语义：
 * <ul>
 *   <li>本接口以<b>路径</b>为事实源，不做版本解析、不做下线/废弃版本拦截（A0508）、不做孤儿文档校验——
 *       入参本就是"模型给出的路径"，落盘文件存在即可读；</li>
 *   <li>{@code Content-Disposition} 用 {@code inline}（查看语义），download 用 {@code attachment}。</li>
 * </ul></p>
 *
 * <p><b>安全链路</b>（任一不过即拒绝，全部留痕）：
 * <ol>
 *   <li>{@code @Authenticated}（与 download 同口径，Bearer 令牌）；</li>
 *   <li>路径形态：拒绝反斜杠/盘符/绝对路径 → 经 {@link MarkdownImageRewriter#normalizeRelative}
 *       消解 {@code .}/空段、{@code ..} 越出存储根即拒绝 → A0400；</li>
 *   <li>知识库存在且未软删（{@code status=1}）→ 否则 A0430「知识库不存在」；</li>
 *   <li>组装后路径恒居 {@code kb/{KB段}/} 子树内（前缀由服务端拼装，调用方无法指定其它库/存储根）；</li>
 *   <li><b>真实路径复核</b>（{@link FileStorageService#existingRegularFilesWithin}）：解析软链后的真实路径
 *       必须仍在 {@code kb/{KB段}/} 内且为常规文件，否则按「文件不存在」拒绝——字符串白名单挡不住
 *       {@code kb/{KB段}/x.c -> ../../.trash/…} 这类软链逃逸；</li>
 *   <li>读取失败 → A0706（统一文件读取失败文案）。</li>
 * </ol></p>
 *
 * <p><b>已知边界（接受）</b>：
 * <ul>
 *   <li>KB/类别目录段在上传落盘时做过清洗、唯一化（同名加 {@code _1}）与 60 字符截断，
 *       模型侧路径若未同步这些变换则组装路径不命中，返回 A0430「文件不存在」——
 *       日志会打印 KB 目录段与组装后的完整路径便于定位，本期不做 documents 表后缀模糊兜底；</li>
 *   <li>不做百分号解码兜底（调用方按原样传模型给出的路径）；</li>
 *   <li>md 文件仍整文件读入内存（需全文重写图片引用，文本体积小）；其余文件经
     *       {@link FileStorageService#resolveReadableFile} 流式返回，不整文件进堆。</li>
 * </ul></p>
 */
@Path("/kb/documents")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
public class DocumentFileContentResource {

    private static final Logger logger = LoggerFactory.getLogger(DocumentFileContentResource.class);

    /** markdown 扩展名（与 {@link MarkdownImageRewriter#isMarkdownPath} 同口径） */
    private static final String MARKDOWN_EXT = "md";

    /** 无扩展名白名单文件名（文本类；与上传白名单 ALLOWED_FILENAMES 同源） */
    private static final String MAKEFILE_NAME = "makefile";

    /** 非文本/图片/PDF 类型统一响应类型 */
    private static final String OCTET_STREAM = "application/octet-stream";

    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

    /**
     * 扩展名 → 响应 Content-Type。
     * <p>依据：zip/普通上传入库的代码类文件 {@code mime_type} 恒为 {@code application/octet-stream}
     * （见 {@link DocumentCommandService} 的 zip 条目落库），DB 里的 mime 对本接口没有指导意义，
     * 故按扩展名推导。代码/配置类统一 {@code text/plain; charset=utf-8}——浏览器与前端 fetch 均可直接
     * 按文本展示；新增扩展名最差退化为 {@link #OCTET_STREAM}，不影响可读性。</p>
     */
    private static final Map<String, String> EXT_CONTENT_TYPES = Map.ofEntries(
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", TEXT_PLAIN_UTF8),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            // 代码
            Map.entry("c", TEXT_PLAIN_UTF8),
            Map.entry("cc", TEXT_PLAIN_UTF8),
            Map.entry("cpp", TEXT_PLAIN_UTF8),
            Map.entry("cxx", TEXT_PLAIN_UTF8),
            Map.entry("h", TEXT_PLAIN_UTF8),
            Map.entry("hpp", TEXT_PLAIN_UTF8),
            Map.entry("py", TEXT_PLAIN_UTF8),
            Map.entry("ts", TEXT_PLAIN_UTF8),
            Map.entry("tsx", TEXT_PLAIN_UTF8),
            Map.entry("ets", TEXT_PLAIN_UTF8),
            Map.entry("java", TEXT_PLAIN_UTF8),
            Map.entry("js", TEXT_PLAIN_UTF8),
            Map.entry("mjs", TEXT_PLAIN_UTF8),
            Map.entry("cjs", TEXT_PLAIN_UTF8),
            // 配置
            Map.entry("ini", TEXT_PLAIN_UTF8),
            Map.entry("dts", TEXT_PLAIN_UTF8),
            Map.entry("dtsi", TEXT_PLAIN_UTF8),
            Map.entry("dtso", TEXT_PLAIN_UTF8),
            Map.entry("xml", TEXT_PLAIN_UTF8),
            Map.entry("sh", TEXT_PLAIN_UTF8),
            Map.entry("cmake", TEXT_PLAIN_UTF8),
            // 图片 / PDF
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("pdf", "application/pdf"));

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentStoragePathResolver storagePathResolver;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final MarkdownImageRewriter markdownImageRewriter;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    OperatorIdHolder operatorIdHolder;

    public DocumentFileContentResource(KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentStoragePathResolver storagePathResolver,

                                       DocumentRepository documentRepository,
                                       FileStorageService fileStorageService,
                                       MarkdownImageRewriter markdownImageRewriter) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.storagePathResolver = storagePathResolver;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.markdownImageRewriter = markdownImageRewriter;
    }

    /**
     * 查看文件内容（kbId + 模型侧项目内相对路径）
     *
     * <p>成功：HTTP 200 + 文件字节（md 命中文档行时图片引用已重写为签名 URL）；失败：HTTP 200 +
     * 统一业务信封（A0400 参数不正确 / A0430 知识库或文件不存在 / A0706 文件读取失败）。</p>
     *
     * @param command kbId（知识库 ID）+ path（模型侧项目内相对路径）均必填
     */
    @POST
    @Path("/file-content")
    @WithSession
    public Uni<Response> fileContent(
            @Valid @NotNull(message = "{app.common.body.notnull}") DocumentFileContentCommand command) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        String operatorId = operatorIdHolder.get();
        String kbId = command.kbId().trim();
        // 路径形态校验（最廉价的攻击面先拒）：反斜杠/盘符/绝对路径、.. 越出存储根
        String relativePath = normalizeRelativePath(command.path(), appMessages);
        logger.info("File content request, kbId={}, path={}, operatorId={}, locale={}",
                kbId, command.path(), operatorId, locale);

        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found, file content rejected: kbId={}", kbId);
                    return new BusinessException(ErrorCodeEnum.USER_ERROR_A0430, appMessages.kbNotFound());
                })
                .flatMap(kb -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(kb.getStatus())) {
                        logger.error("Knowledge base is deleted, file content rejected: kbId={}, status={}",
                                kbId, kb.getStatus());
                        return Uni.createFrom().failure(new BusinessException(
                                ErrorCodeEnum.USER_ERROR_A0430, appMessages.kbNotFound()));
                    }
                    // KB 物理目录段（kb/{KB段}）：复用上传落盘的同源规则（dirName 优先、存量 NULL 回退名称）
                    return storagePathResolver.storageDirForCategory(kbId, null);
                })
                .flatMap(kbRoot -> {
                    // 幂等：调用方若已传存储相对路径（kb/{KB段}/…）则不重复加前缀
                    String storagePath = relativePath.startsWith(kbRoot + "/")
                            ? relativePath : kbRoot + "/" + relativePath;
                    String ext = MarkdownImageRewriter.imageExtensionOf(storagePath).orElse("");
                    return fileStorageService.existingRegularFilesWithin(kbRoot, List.of(storagePath))
                            .flatMap(found -> {
                                if (found.isEmpty()) {
                                    logger.warn("File content not found (missing/symlink escape/not a regular file): "
                                                    + "kbId={}, kbRoot={}, storagePath={}",
                                            kbId, kbRoot, storagePath);
                                    return Uni.createFrom().failure(new BusinessException(
                                            ErrorCodeEnum.USER_ERROR_A0430, appMessages.fileNotExist()));
                                }
                                // md 才需要 documentId：图片签名 URL 绑定文档域（非 md 跳过该查询）
                                return resolveDocumentId(storagePath, ext)
                                        .flatMap(docId -> {
                                            // md 需要全文重写图片引用，维持全量读取（文本、体积小）；
                                            // 其余文件流式返回（Path 实体由 RESTEasy Reactive chunked
                                            // 边读边写，首字节时间与文件大小解耦，不整文件进堆）
                                            if (MARKDOWN_EXT.equals(ext)) {
                                                return readAndBuild(storagePath, ext, docId, appMessages);
                                            }
                                            return fileStorageService.resolveReadableFile(storagePath)
                                                    .onFailure().transform(err -> {
                                                        logger.error("Failed to resolve file for streaming: path={}, error={}",
                                                                storagePath, err.getMessage());
                                                        return new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                                                                appMessages.fileCannotRead());
                                                    })
                                                    .map(path -> (Response) Response.ok((Object) path)
                                                            .header(HttpHeaders.CONTENT_TYPE, contentTypeOf(storagePath, ext))
                                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                                    buildInlineContentDisposition(storagePath))
                                                            .build());
                                        });
                            });
                });
    }

    /**
     * 路径形态校验与规范化：去前导 {@code /} → 拒绝反斜杠/盘符 → 消解 {@code .}/空段，
     * {@code ..} 越出存储根即拒绝。返回值为不含前导斜杠的存储相对路径段（供拼装 KB 目录前缀）。
     */
    private static String normalizeRelativePath(String rawPath, AppMessages appMessages) {
        String path = rawPath.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            logger.warn("File content path illegal (backslash/drive letter): path={}", rawPath);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        String normalized = MarkdownImageRewriter.normalizeRelative(path).orElse("");
        if (normalized.isEmpty()) {
            logger.warn("File content path illegal (empty or escapes the storage root): path={}", rawPath);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        return normalized;
    }

    /**
     * md 文件的文档行解析（仅用于图片引用重写：签名 URL 需绑定 documentId）。
     * <p>解析口径与 download 一致（documents 当前路径优先，版本表兜底）。非 md、或路径无文档行时返回
     * null —— 后者不是错误：模型侧产出的 md 也可能没有平台文档行，此时原样返回内容。</p>
     */
    private Uni<String> resolveDocumentId(String storagePath, String ext) {
        if (!MARKDOWN_EXT.equals(ext)) {
            return Uni.createFrom().<String>nullItem();
        }
        return documentRepository.findRefsByFilePaths(List.of(storagePath))
                .map(refs -> {
                    DocumentRepository.PathRef ref = refs.get(storagePath);
                    return ref == null ? null : ref.documentId();
                });
    }

    /** 读取文件字节并组装响应；读取失败统一 A0706 */
    private Uni<Response> readAndBuild(String storagePath, String ext, String docId, AppMessages appMessages) {
        return fileStorageService.read(storagePath)
                .onFailure().transform(err -> {
                    logger.error("Failed to read file content: path={}, error={}", storagePath, err.getMessage());
                    return new BusinessException(ErrorCodeEnum.ERROR_READ_FILE, appMessages.fileCannotRead());
                })
                .map(bytes -> buildResponse(storagePath, ext, docId, bytes));
    }

    /**
     * 响应组装：md 且解析到文档行时重写相对图片引用为免登录签名 URL（与 download 同一实现），
     * 其余文件字节流原样返回；{@code Content-Disposition} 为 inline（查看语义）。
     */
    private Response buildResponse(String storagePath, String ext, String docId, byte[] bytes) {
        String contentType = contentTypeOf(storagePath, ext);
        if (MARKDOWN_EXT.equals(ext) && docId != null) {
            String rewritten = markdownImageRewriter.rewrite(docId, storagePath, bytes);
            return Response.ok(rewritten.getBytes(StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildInlineContentDisposition(storagePath))
                    .build();
        }
        return Response.ok(bytes)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, buildInlineContentDisposition(storagePath))
                .build();
    }

    /** Content-Type：无扩展名时识别白名单文件名（makefile），其余按扩展名映射，未知类型 octet-stream */
    private static String contentTypeOf(String storagePath, String ext) {
        if (ext == null || ext.isEmpty()) {
            String name = storagePath.substring(storagePath.lastIndexOf('/') + 1);
            return MAKEFILE_NAME.equalsIgnoreCase(name) ? TEXT_PLAIN_UTF8 : OCTET_STREAM;
        }
        return EXT_CONTENT_TYPES.getOrDefault(ext, OCTET_STREAM);
    }

    /** RFC 5987 文件名编码（支持中文）：inline; filename*=UTF-8''{encode} */
    private static String buildInlineContentDisposition(String storagePath) {
        String fileName = storagePath.substring(storagePath.lastIndexOf('/') + 1);
        // URLEncoder.encode(String, Charset)（Java 10+）不抛受检异常，无需兜底 catch
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename*=UTF-8''" + encoded;
    }
}
