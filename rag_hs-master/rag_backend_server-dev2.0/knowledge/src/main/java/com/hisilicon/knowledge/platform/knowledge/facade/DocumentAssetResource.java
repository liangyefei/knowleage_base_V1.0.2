package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import com.hisilicon.knowledge.platform.knowledge.query.MarkdownImageRewriter;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 图片资产接口（markdown 在线查看的图片外链，<b>不要求登录</b>）
 *
 * <p>类上<b>有意不加</b> {@code @Authenticated}：{@code AuthenticationFilter} 仅拦截标注了
 * {@code @Authenticated} 的接口，浏览器 {@code <img>} 标签发起的图片请求不会携带
 * Authorization 头，鉴权由签名替代——URL 由 {@link AssetUrlSigner} 以 HMAC-SHA256 签发，
 * 绑定 documentId + 存储路径 + 过期时间，无法伪造、无法篡改、短时效自动过期。</p>
 *
 * <p>安全链路（任一不过即拒绝，日志留痕）：
 * <ol>
 *   <li>参数完整性（path/expires/sig 非空、expires 为合法数字）→ A0400；</li>
 *   <li>签名 + 过期校验（先于任何 DB/存储访问，不给探测性请求泄露信息）→ A0301；</li>
 *   <li>文档存在性 → A0430；</li>
 *   <li>路径穿越防护：解码后拒绝反斜杠/盘符，规范化拒绝 {@code ..} 越界，
 *       且必须落在该 md 文档所在存储目录子树内（与签发侧
 *       {@link MarkdownImageRewriter#resolveWithinBase} 同一实现）→ A0400；</li>
 *   <li>扩展名白名单（png/jpg/jpeg/bmp）→ A0701。</li>
 * </ol></p>
 *
 * <p>URL 由 {@link MarkdownImageRewriter} 在 download 接口重写 md 时生成：
 * {@code {root-path}/kb/documents/{documentId}/assets?path=…&expires=…&sig=…}，其中根路径前缀
 * 取值 {@code quarkus.http.root-path}（本应用生产恒为 {@code /hisilicon-knowledge}）。
 * query 参数经 JAX-RS 单次百分号解码，签名针对解码后的存储路径计算，双重编码攻击在签名校验处即被拒绝。</p>
 */
@Path("/kb/documents")
public class DocumentAssetResource {

    private static final Logger logger = LoggerFactory.getLogger(DocumentAssetResource.class);

    /** 图片扩展名 → 响应 Content-Type（仅白名单扩展名可达此处） */
    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "bmp", "image/bmp");

    /** 图片扩展名白名单（与签发侧一致） */
    private static final Set<String> IMAGE_EXTENSIONS = MarkdownImageRewriter.IMAGE_EXTENSIONS;

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final AssetUrlSigner assetUrlSigner;

    @Context
    HttpHeaders httpHeaders;

    public DocumentAssetResource(DocumentRepository documentRepository,
                                 FileStorageService fileStorageService,
                                 AssetUrlSigner assetUrlSigner) {
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.assetUrlSigner = assetUrlSigner;
    }

    /**
     * 获取 md 引用的图片资产
     * <p>成功返回图片字节（Content-Type 按扩展名，{@code Cache-Control: private, max-age=3600}
     * 与签名有效期对齐，浏览器侧缓存不跨用户共享）；失败按项目惯例返回统一错误信封。</p>
     *
     * @param documentId md 所属文档 ID（签名载荷第一段）
     * @param path       图片存储相对路径（签名载荷第二段，已百分号编码传输）
     * @param expires    过期时间戳（秒级，签名载荷第三段）
     * @param sig        HMAC-SHA256 签名（小写 hex）
     */
    @GET
    @Path("/{documentId}/assets")
    @WithSession
    public Uni<Response> getAsset(@PathParam("documentId") String documentId,
                                  @QueryParam("path") String path,
                                  @QueryParam("expires") String expires,
                                  @QueryParam("sig") String sig) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 1. 参数完整性（不存在即无意义，先拒最廉价的攻击面）
        if (documentId == null || documentId.isBlank()
                || path == null || path.isBlank()
                || sig == null || sig.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        long expiresEpoch = parseExpires(expires, appMessages);
        String trimmedDocumentId = documentId.trim();

        // 2. 签名 + 过期校验：针对请求原值（解码后）验证，先于 DB/存储访问
        if (!assetUrlSigner.verify(trimmedDocumentId, path, expiresEpoch, sig)) {
            logger.warn("Image asset signature verification failed (invalid/tampered/expired/secret missing): documentId={}, path={}, expires={}",
                    trimmedDocumentId, path, expiresEpoch);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301, appMessages.verifySignatureFailed());
        }

        // 3. 文档存在性（签名已通过，此查询可安全暴露 404 语义）
        return documentRepository.findById(DocumentId.of(trimmedDocumentId))
                .onItem().ifNull().failWith(() -> {
                    logger.warn("Document owning image asset not found: documentId={}", trimmedDocumentId);
                    return new DocumentNotFoundException(appMessages.documentNotFound());
                })
                .flatMap(entity -> {
                    // 4+5. 路径穿越防护 + 扩展名白名单（签发侧同源实现）
                    String normalized = validateAssetPath(entity.getFilePath(), path, appMessages);
                    String ext = MarkdownImageRewriter.imageExtensionOf(normalized).orElse("");
                    return fileStorageService.read(normalized)
                            .onFailure().transform(err -> {
                                logger.error("Failed to read image asset: documentId={}, path={}, error={}",
                                        trimmedDocumentId, normalized, err.getMessage());
                                return new BusinessException(ErrorCodeEnum.ERROR_READ_FILE,
                                        appMessages.fileCannotRead());
                            })
                            .map(bytes -> Response.ok(bytes)
                                    .header(HttpHeaders.CONTENT_TYPE, IMAGE_CONTENT_TYPES.get(ext))
                                    .header("Cache-Control", "private, max-age=" + assetUrlSigner.urlExpirySeconds())
                                    .build());
                });
    }

    /** 过期时间戳解析：缺失/非数字 → A0400（业务信封，与全局异常处理口径一致） */
    private static long parseExpires(String expires, AppMessages appMessages) {
        if (expires == null || expires.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        try {
            return Long.parseLong(expires.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
    }

    /**
     * 路径穿越防护（签名校验通过后的第二道防线，签名本身已绑定合法路径）：
     * <ol>
     *   <li>拒绝反斜杠（Windows 分隔语义，存储路径恒为 {@code /}）与盘符 {@code X:}；</li>
     *   <li>拒绝存储绝对路径（{@code /} 开头）——引用只能落在 md 所在目录子树内；</li>
     *   <li>独立规范化消解 {@code .}/空段，{@code ..} 越栈（越出存储根）拒绝；</li>
     *   <li>子树包含：规范化结果必须位于该 md 文档所在存储目录（baseDir）之内
     *       （兄弟目录/上级目录一律拒绝，与签发侧 {@link MarkdownImageRewriter#resolveWithinBase}
     *       的收敛口径一致）；</li>
     *   <li>扩展名必须为图片白名单（png/jpg/jpeg/bmp）→ A0701。</li>
     * </ol>
     * URL 编码绕过已在签名前消解：query 参数经 JAX-RS 单次解码后再校验（先 decode 再 validate），
     * 双重编码残余为字面量目录名，规范化不构成 {@code ..}。
     */
    private static String validateAssetPath(String mdFilePath, String rawPath, AppMessages appMessages) {
        if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf(':') >= 0
                || rawPath.startsWith("/")) {
            logger.warn("Image asset path illegal (backslash/drive letter/absolute path): path={}", rawPath);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        String baseDir = baseDirOf(mdFilePath);
        String normalized = MarkdownImageRewriter.normalizeRelative(rawPath).orElse("");
        if (normalized.isEmpty()
                || (!baseDir.isEmpty() && !normalized.startsWith(baseDir + "/"))) {
            logger.warn("Image asset path escapes base directory subtree (path traversal): path={}, baseDir={}, normalized={}",
                    rawPath, baseDir, normalized);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        String ext = MarkdownImageRewriter.imageExtensionOf(normalized).orElse(null);
        if (ext == null || !IMAGE_EXTENSIONS.contains(ext)) {
            logger.warn("Image asset extension not in whitelist: path={}, ext={}", normalized, ext);
            // 用 A0701（非 A0400）：BusinessExceptionMapper 对 A0400/A0410 统一覆盖为
            // "参数不正确"，具体扩展名信息只留日志；A0701 的明确文案可直达客户端
            throw new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported(ext == null ? "" : ext));
        }
        return normalized;
    }

    /** md 所在存储目录（与签发侧 {@link MarkdownImageRewriter} 的基准目录口径一致） */
    private static String baseDirOf(String mdFilePath) {
        if (mdFilePath == null || mdFilePath.isBlank()) {
            return "";
        }
        String normalized = mdFilePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }
}
