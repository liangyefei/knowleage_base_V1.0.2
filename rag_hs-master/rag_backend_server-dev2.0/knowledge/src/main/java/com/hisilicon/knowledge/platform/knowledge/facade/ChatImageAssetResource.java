package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import com.hisilicon.knowledge.platform.knowledge.query.ChatImageAssetPolicy;
import com.hisilicon.knowledge.platform.knowledge.query.MarkdownImageRewriter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 对话兜底图片资产接口（模型端解析产物图片的外链，<b>不要求登录</b>）
 *
 * <p><b>为什么需要它</b>：模型端解析 PDF 时把抽出的图片写进自己的产物目录
 * （如 {@code /kb-files/mineru_output/{任务hash}/images/…}）。这些文件真实存在，但
 * <b>不是平台文档树文件、documents 表里没有行</b>，{@link DocumentAssetResource} 的两道校验
 * （文档存在性、路径落在该文档目录子树内）必然过不去，模型回答里的引用只能原样下发——
 * 浏览器请求 {@code /kb-files/…}（平台不提供该前缀的静态服务）必然 404，图片不显示。</p>
 *
 * <p><b>安全链路</b>（任一不过即拒绝，全部留痕）：类上<b>有意不加</b> {@code @Authenticated}
 * ——浏览器 {@code <img>} 请求不带 Authorization 头，鉴权由签名替代：
 * <ol>
 *   <li>参数完整性（path/sig 非空、expires 合法数字）→ A0400；</li>
 *   <li><b>对话兜底域签名</b>校验（{@link AssetUrlSigner#verifyChatAsset}，先于任何存储访问）
 *       → A0301。域标签隔离保证<b>文档图片域的签名在此恒不通过</b>（反之亦然），端点之间不可重放；</li>
 *   <li>路径形态（拒绝反斜杠/盘符/绝对路径、规范化不可越出存储根）+ <b>放行目录白名单</b>
 *       （{@link ChatImageAssetPolicy}）→ A0400。没有文档可绑定，白名单就是可访问范围的唯一来源——
 *       {@code .trash/} 软删文件、其它知识库的文档路径、模型端将来新增的任何目录一律签不进来；</li>
 *   <li>扩展名白名单（png/jpg/jpeg/bmp）→ A0701；</li>
 *   <li><b>真实路径复核</b>（{@link FileStorageService#existingRegularFilesWithin}）：解析软链后的
 *       真实路径必须仍在放行目录内且为常规文件，否则按"读不到"拒绝——路径字符串白名单挡不住
 *       {@code mineru_output/x.png -> ../../.trash/…} 这类软链逃逸。</li>
 * </ol></p>
 *
 * <p>URL 由 {@link com.hisilicon.knowledge.platform.knowledge.query.ChatImageAssetUrlRewriterImpl}
 * 签发，形如 {@code {root-path}/kb/chat/assets?path=…&expires=…&sig=…}（根路径前缀
 * {@code quarkus.http.root-path}，本应用生产恒为 {@code /hisilicon-knowledge}）。</p>
 *
 * <p><b>生命周期</b>：本端点只保证"文件当前存在则可读"，不承担清理——产物目录的属主是模型端，
 * 它按归档/废弃事件清理对应图片，此时文档本身也不可见（产品已确认口径一致）。</p>
 *
 * <p>路由说明：{@code @Path("/kb/chat")} 与 {@code llm} 模块的 {@code ChatResource}（{@code @Path("/chat")}）
 * 不同前缀、无冲突；与 {@link DocumentAssetResource}（{@code /kb/documents/{id}/assets}）各自独立。</p>
 */
@Path("/kb/chat")
public class ChatImageAssetResource {

    private static final Logger logger = LoggerFactory.getLogger(ChatImageAssetResource.class);

    /** 图片扩展名 → 响应 Content-Type（仅白名单扩展名可达此处；与 {@link DocumentAssetResource} 同口径） */
    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "bmp", "image/bmp");

    private final FileStorageService fileStorageService;
    private final AssetUrlSigner assetUrlSigner;
    private final ChatImageAssetPolicy chatImageAssetPolicy;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    public ChatImageAssetResource(FileStorageService fileStorageService,
                                  AssetUrlSigner assetUrlSigner,
                                  ChatImageAssetPolicy chatImageAssetPolicy) {
        this.fileStorageService = fileStorageService;
        this.assetUrlSigner = assetUrlSigner;
        this.chatImageAssetPolicy = chatImageAssetPolicy;
    }

    /**
     * 获取对话兜底图片资产
     *
     * @param path    图片存储相对路径（签名载荷，已百分号编码传输）
     * @param expires 过期时间戳（秒级，签名载荷）
     * @param sig     对话兜底域 HMAC-SHA256 签名（小写 hex）
     */
    @GET
    @Path("/assets")
    public Uni<Response> getAsset(@QueryParam("path") String path,
                                  @QueryParam("expires") String expires,
                                  @QueryParam("sig") String sig) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        // 1. 参数完整性
        if (path == null || path.isBlank() || sig == null || sig.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        long expiresEpoch = parseExpires(expires, appMessages);

        // 2. 对话兜底域签名 + 过期校验（针对请求原值验证，先于任何存储访问）
        if (!assetUrlSigner.verifyChatAsset(path, expiresEpoch, sig)) {
            logger.warn("Chat image asset signature verification failed (invalid/tampered/expired/secret missing/cross-origin replay): path={}, expires={}",
                    path, expiresEpoch);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0301, appMessages.verifySignatureFailed());
        }

        // 3. 路径形态 + 放行目录白名单
        String normalized = validateFallbackPath(path, appMessages);

        // 4. 扩展名白名单
        String ext = MarkdownImageRewriter.imageExtensionOf(normalized).orElse("");
        if (!MarkdownImageRewriter.IMAGE_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCodeEnum.ERROR_UPLOAD_FILE_TYPE,
                    appMessages.fileTypeNotSupported(ext));
        }

        // 5. 真实路径复核（软链逃逸/目录/不存在一律不是"可读文件"）+ 读取
        String allowedDir = chatImageAssetPolicy.matchAllowedDir(normalized);
        return fileStorageService.existingRegularFilesWithin(allowedDir, List.of(normalized))
                .chain(found -> {
                    if (found.isEmpty()) {
                        logger.warn("Chat image asset not readable (missing/symlink escape/not regular file): path={}, allowedDir={}",
                                normalized, allowedDir);
                        return Uni.createFrom().<Response>failure(new BusinessException(
                                ErrorCodeEnum.ERROR_READ_FILE, appMessages.fileCannotRead()));
                    }
                    return fileStorageService.read(normalized)
                            .onFailure().transform(err -> {
                                logger.error("Failed to read chat image asset: path={}, error={}",
                                        normalized, err.getMessage());
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
     * 兜底路径校验（签名通过后的第二道防线；签名本身已绑定合法路径，此处防"合法签名 + 非法路径"）：
     * <ol>
     *   <li>拒绝反斜杠（Windows 分隔语义，存储路径恒为 {@code /}）、盘符 {@code X:}、
     *       存储绝对路径（{@code /} 开头）；</li>
     *   <li>独立规范化消解 {@code .}/空段，{@code ..} 越出存储根 → 拒绝；</li>
     *   <li>规范化结果必须命中放行目录白名单（<b>段级</b>匹配，{@code mineru_output_old/} 不会被
     *       {@code mineru_output} 认领）——没有文档行可绑定，白名单就是可访问范围的唯一来源。</li>
     * </ol>
     *
     * @return 规范化后的存储相对路径（读取用值）
     */
    private String validateFallbackPath(String rawPath, AppMessages appMessages) {
        if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf(':') >= 0 || rawPath.startsWith("/")) {
            logger.warn("Chat image asset path illegal (backslash/drive letter/absolute path): path={}", rawPath);
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        String normalized = MarkdownImageRewriter.normalizeRelative(rawPath).orElse("");
        if (normalized.isEmpty()
                || chatImageAssetPolicy.matchAllowedDir(normalized) == null) {
            logger.warn("Chat image asset path not within fallback allowed dirs: path={}, normalized={}, fallbackDirs={}",
                    rawPath, normalized, chatImageAssetPolicy.allowedDirs());
            throw new BusinessException(ErrorCodeEnum.USER_ERROR_A0400, appMessages.invalidParameter());
        }
        return normalized;
    }
}
