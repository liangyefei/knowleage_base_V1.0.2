package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.ChatImageAssetUrlRewriter;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 对话图片引用网络地址化实现（{@link ChatImageAssetUrlRewriter} 端口的 knowledge 侧实现）。
 *
 * <p>改写模型回答中的 markdown 图片引用 {@code ![alt](/kb-files/{documents.file_path})} 与
 * 裸 HTML img 标签 {@code <img src="/kb-files/{documents.file_path}" alt="…">} 为
 * 图片资产签名 URL（{@link DocumentAssetResource} 免登录资产接口），与 markdown 下载在线查看
 * 共用签名/校验/缓存链路。处理步骤：</p>
 * <ol>
 *   <li>按 md 图片语法（与 {@link MarkdownImageRewriter} 同一模式）和 HTML img src 属性
 *       （同一 {@link MarkdownImageRewriter#HTML_IMG_SRC} 口径：大小写不敏感、属性顺序任意、
 *       单/双引号）找出<b>完整</b>引用；</li>
 *   <li>引用值解析为存储相对路径：剥 {@code /kb-files/} 挂载前缀（与 docs 出处改写
 *       {@code ChatDocsEventTransformer} 同一前缀语义）→ 百分号解码 → 拒绝反斜杠/盘符 →
 *       规范化消解 {@code .}/空段与 {@code ..} → 图片扩展名白名单（png/jpg/jpeg/bmp）；</li>
 *   <li>{@link DocumentRepository#findIdsByFilePaths} 批量解析图片所属文档 id
 *       （zip 上传时图片作为 md 的同类别兄弟文档落库，路径即 {@code documents.file_path}）；</li>
 *   <li>{@link AssetUrlSigner} 按三元组 {@code (图片文档id, 存储路径, expires)} 签发，
 *       URL 拼接与 markdown 下载重写完全一致（{@link MarkdownImageRewriter#buildAssetUrl}）。</li>
 * </ol>
 *
 * <p><b>兜底链路（模型端解析产物图片）</b>：模型端解析 PDF 时把抽出的图片写进自己的产物目录
 * （{@code /kb-files/mineru_output/…}），这些文件<b>不是平台文档树文件、documents 表里没有行</b>，
 * 文档域签名必然签不出来。对这类"文档未命中"的引用追加一条兜底：命中配置的放行目录白名单
 * （{@link ChatImageAssetPolicy}，{@code kb.chat.image-fallback-dirs}）+ 存储层真实路径复核
 * （{@link FileStorageService#existingRegularFilesWithin}，堵软链逃逸）+ 文件确实存在
 * → 用<b>对话兜底签名域</b>（{@link AssetUrlSigner#signChatAsset}）签发，指向免登录的
 * {@code /kb/chat/assets}（{@code ChatImageAssetResource}）。三条件全过才签，任一不过都保持原样。</p>
 *
 * <p>降级语义（整体不抛异常，回答完整性优先于图片显示）：引用非 {@code /kb-files/} 前缀、
 * 扩展名不在白名单、路径查不到所属文档且兜底签不出、兜底判存失败、签名密钥未配置
 * （启动期已 WARN，此处静默）→ 该引用原样保留；解析/签名失败 → 整体返回原文并 WARN。
 * 签名校验侧自洽：图片文档自身的 {@code file_path} 基准目录即其父目录，签名 path（图片自身路径）
 * 恒落在其子树内，{@code DocumentAssetResource} 现有校验天然通过，资产侧零改动。</p>
 *
 * <p><b>可观测性</b>：两条路都签不出来的引用 = 前端必然取不到图（空 alt 的引用连破图提示都没有），
 * 因此一律 WARN 留痕，并区分两种根因（不在放行目录 / 在放行目录但文件不存在）。此处原是 DEBUG，
 * 正是"图片静默不显示、长期无人发现"的原因——不要再降回去。</p>
 *
 * <p>已知边界（接受）：同路径在文档表唯一化改名（{@code {name}_1.{ext}}）后按原始名引用、
 * 或文档已物理删除，且兜底也覆盖不到时，图片不显示（引用原样保留 + WARN）——与 docs 出处
 * "查不到不补 id"的既有口径一致。兜底目录内的图片由<b>模型端</b>按归档/废弃事件清理，平台不承担
 * 其生命周期：文件被清掉后历史问答里的图片失效，产品已确认与"文档同时不可见"口径一致。</p>
 */
@ApplicationScoped
public class ChatImageAssetUrlRewriterImpl implements ChatImageAssetUrlRewriter {

    private static final Logger logger = LoggerFactory.getLogger(ChatImageAssetUrlRewriterImpl.class);

    /** 模型挂载视图前缀（引用形如 {@code /kb-files/{documents.file_path}}），允许不带首斜杠 */
    private static final String KB_FILES_PREFIX = "kb-files/";

    private final DocumentRepository documentRepository;
    private final AssetUrlSigner signer;
    private final FileStorageService fileStorageService;
    private final ChatImageAssetPolicy chatImageAssetPolicy;

    public ChatImageAssetUrlRewriterImpl(DocumentRepository documentRepository, AssetUrlSigner signer,
                                         FileStorageService fileStorageService,
                                         ChatImageAssetPolicy chatImageAssetPolicy) {
        this.documentRepository = documentRepository;
        this.signer = signer;
        this.fileStorageService = fileStorageService;
        this.chatImageAssetPolicy = chatImageAssetPolicy;
    }

    @Override
    @WithSession
    public Uni<String> rewrite(String text) {
        if (text == null || text.isEmpty()) {
            return Uni.createFrom().item(text == null ? "" : text);
        }
        if (!signer.isConfigured()) {
            // fail-closed 降级：密钥未配置一律原样返回（AssetUrlSigner 启动期已 WARN，此处不刷屏）
            return Uni.createFrom().item(text);
        }

        // 1. 扫描完整引用（markdown 语法 + HTML img src），解析出可签发的存储路径
        List<ImageSpan> spans = new ArrayList<>();
        Set<String> storagePaths = new LinkedHashSet<>();
        Matcher mdMatcher = MarkdownImageRewriter.MD_IMAGE.matcher(text);
        while (mdMatcher.find()) {
            int start = mdMatcher.start();
            int end = mdMatcher.end();
            String alt = mdMatcher.group(1);
            String refValue = mdMatcher.group(2);
            String suffix = mdMatcher.group(3);
            toStoragePath(mdMatcher.group(2)).ifPresent(storagePath -> {
                spans.add(new ImageSpan(start, end, false, alt, refValue, suffix, storagePath));
                storagePaths.add(storagePath);
            });
        }
        // HTML img：捕获值带引号，剥离后与 md 引用共用同一解析防线；src 后属性不在匹配范围、原样保留
        Matcher htmlMatcher = MarkdownImageRewriter.HTML_IMG_SRC.matcher(text);
        while (htmlMatcher.find()) {
            String quoted = htmlMatcher.group(2);
            String value = quoted.substring(1, quoted.length() - 1);
            int start = htmlMatcher.start();
            int end = htmlMatcher.end();
            String alt = htmlMatcher.group(1);
            toStoragePath(value).ifPresent(storagePath -> {
                spans.add(new ImageSpan(start, end, true, alt, value, "\"", storagePath));
                storagePaths.add(storagePath);
            });
        }
        List<ImageSpan> mergedSpans = mergeOverlapping(spans);
        if (mergedSpans.isEmpty()) {
            return Uni.createFrom().item(text);
        }

        // 2. 批量解析图片所属文档 id（zip 上传的图片即兄弟文档）→ 3. 文档未命中的走兜底判存
        // → 4. 统一替换（文档域签名 / 对话兜底域签名 / 原样保留）
        // deferred：仓储调用抛出的异常折叠为 Uni failure，进入下方统一降级（不再 catch Exception）
        Uni<Map<String, String>> idsUni =
                Uni.createFrom().deferred(() -> documentRepository.findIdsByFilePaths(storagePaths));
        return idsUni
                .chain(ids -> resolveFallbackAssets(ids, storagePaths)
                        .map(existingAssets -> rebuild(text, mergedSpans, ids, existingAssets)))
                .onFailure().recoverWithItem(t -> {
                    logger.warn("Chat image reference rewriting failed, the whole section is returned as-is: error={}", t.toString());
                    return text;
                });
    }

    /**
     * 兜底判存：对<b>文档未命中</b>且命中放行目录白名单的路径做批量存在性校验
     * （存储层同时复核真实路径仍在放行目录内，堵软链逃逸）。
     *
     * <p>只对未命中路径判存：命中文档的走文档域签名，不占兜底；白名单外的路径连判存都不做
     * （不是"存在就签"，是"在白名单内且存在才签"）。按放行目录分组，每个目录一次
     * {@code executeBlocking}——避免"每张图一次 worker 往返"。判存失败按"都不存在"降级，
     * 不影响回答完整性。</p>
     */
    private Uni<Set<String>> resolveFallbackAssets(Map<String, String> ids, Set<String> storagePaths) {
        if (!chatImageAssetPolicy.enabled()) {
            return Uni.createFrom().item(Set.of());
        }
        Map<String, List<String>> pathsByDir = new LinkedHashMap<>();
        for (String path : storagePaths) {
            if (ids.containsKey(path)) {
                continue;
            }
            String allowedDir = chatImageAssetPolicy.matchAllowedDir(path);
            if (allowedDir != null) {
                pathsByDir.computeIfAbsent(allowedDir, k -> new ArrayList<>()).add(path);
            }
        }
        if (pathsByDir.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        Uni<Set<String>> pending = Uni.createFrom().item(new HashSet<String>());
        for (Map.Entry<String, List<String>> entry : pathsByDir.entrySet()) {
            String dir = entry.getKey();
            List<String> paths = entry.getValue();
            pending = pending.chain(acc -> fileStorageService.existingRegularFilesWithin(dir, paths)
                    .map(found -> {
                        acc.addAll(found);
                        return acc;
                    }));
        }
        return pending.onFailure().recoverWithItem(t -> {
            logger.warn("Chat image fallback existence probe failed, unmatched paths are treated as unavailable: error={}", t.toString());
            return Set.of();
        });
    }

    /**
     * 引用值 → 存储相对路径；不可安全签发（非 /kb-files 前缀/scheme/越界/非图片扩展名）返回空 {@code Optional}。
     * <p>与 {@link MarkdownImageRewriter#toSignedUrl} 的解析防线同源（title 语法、尖括号包裹、
     * 百分号解码、反斜杠/盘符拒绝、规范化、白名单），差异仅在锚定方式：下载重写按 md 所在目录
     * 解析<b>相对</b>引用，此处剥挂载前缀后按<b>存储根</b>定位（对话场景没有所属 md 目录上下文）。</p>
     */
    private Optional<String> toStoragePath(String rawRef) {
        String ref = rawRef.trim();
        // markdown 尖括号包裹（含空格路径的合法写法）：<path with spaces.png>
        if (ref.length() >= 2 && ref.startsWith("<") && ref.endsWith(">")) {
            ref = ref.substring(1, ref.length() - 1).trim();
        }
        if (ref.isEmpty() || ref.startsWith("#")) {
            return Optional.empty();
        }
        // title 语法：path "title"——截掉引号起的内容
        int quote = MarkdownImageRewriter.firstQuoteIndex(ref);
        if (quote >= 0) {
            ref = ref.substring(0, quote).trim();
        }
        if (ref.isEmpty() || ref.startsWith("//") || MarkdownImageRewriter.SCHEME.matcher(ref).matches()) {
            return Optional.empty();
        }
        Optional<String> decoded = MarkdownImageRewriter.decodePercent(ref);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        // 反斜杠/盘符直接拒绝（Windows 语义分隔符与盘符在存储路径中不存在）
        String decodedRef = decoded.get();
        if (decodedRef.indexOf('\\') >= 0 || decodedRef.indexOf(':') >= 0) {
            return Optional.empty();
        }
        // 仅处理模型挂载视图引用（允许缺首斜杠）；其余（相对/站点绝对/其它）保持原样
        String withoutSlash = decodedRef;
        while (withoutSlash.startsWith("/")) {
            withoutSlash = withoutSlash.substring(1);
        }
        if (!withoutSlash.startsWith(KB_FILES_PREFIX)) {
            return Optional.empty();
        }
        String storagePath = withoutSlash.substring(KB_FILES_PREFIX.length());
        if (storagePath.isEmpty()) {
            return Optional.empty();
        }
        String normalized = MarkdownImageRewriter.normalizeRelative(storagePath).orElse("");
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (!MarkdownImageRewriter.IMAGE_EXTENSIONS.contains(FileTypeUtil.fromPath(normalized).orElse(""))) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * 按扫描位置重建文本：命中文档或兜底可用的引用替换为 {@code ![alt](签名URL)}
     * （保留 md 语法与 alt 文本，仅替换括号内的引用值），其余逐段原样拼接。
     * <p>同批引用共用同一 expires（一次 rewrite 一次时间基准）；签名针对解码后的存储路径计算，
     * 与资产接口"先 decode 再 verify"的口径一致。</p>
     *
     * @param existingAssets 兜底判存确认存在且真实路径在放行目录内的路径集合（可能为空集）
     */
    private String rebuild(String text, List<ImageSpan> spans, Map<String, String> ids,
                           Set<String> existingAssets) {
        long expires = signer.expiryEpochSecondNow();
        StringBuilder sb = new StringBuilder(text.length());
        int pos = 0;
        for (ImageSpan span : spans) {
            sb.append(text, pos, span.start());
            sb.append(replacement(span, ids, existingAssets, expires));
            pos = span.end();
        }
        sb.append(text, pos, text.length());
        return sb.toString();
    }

    /**
     * 单个引用的替换值，两条签发路径 + 一条降级：
     * <ol>
     *   <li><b>文档域</b>：解析到文档 id → {@code 前缀 + URL + 后缀}（md 保 {@code ![alt](URL)} 语法，
     *       HTML 为 {@code <img …src="URL"}，引号统一双引号）；</li>
     *   <li><b>对话兜底域</b>：文档未命中但兜底判存通过（放行目录内 + 真实路径在目录内 + 是常规文件）
     *       → 用 {@link AssetUrlSigner#signChatAsset} 签发 {@code /kb/chat/assets}；</li>
     *   <li><b>降级</b>：两条都签不出 → 原样保留（md 降级尖括号包裹保链接语义；HTML 属性值带引号
     *       本就合法，逐字节原样），并 WARN 留痕——这种引用前端必然取不到图。</li>
     * </ol>
     */
    private AssetUrlBase urlBase() {
        return new AssetUrlBase(signer.urlPrefix(), signer.rootPath());
    }

    private String replacement(ImageSpan span, Map<String, String> ids, Set<String> existingAssets,
                               long expires) {
        String docId = ids.get(span.storagePath());
        // 降级保留：md 含空格的未编码引用不符合 CommonMark（断链成纯文本），包裹为尖括号形态
        // （MarkdownImageRewriter.wrapSpaceRef，幂等——已包裹/无空格原样返回）；HTML 原样保留
        String keepOriginal = span.htmlImg()
                ? span.mdPrefix() + "\"" + span.refValue() + "\""
                : span.mdPrefix() + MarkdownImageRewriter.wrapSpaceRef(span.refValue()) + span.mdSuffix();
        if (docId != null && !docId.isBlank()) {
            try {
                String sig = signer.sign(docId, span.storagePath(), expires);
                String url = MarkdownImageRewriter.buildAssetUrl(urlBase(),
                        docId, span.storagePath(), expires, sig);
                return span.htmlImg()
                        ? span.mdPrefix() + "\"" + url + "\""
                        : span.mdPrefix() + url + span.mdSuffix();
            } catch (java.lang.IllegalStateException e) {
                logger.warn("Chat image signing failed, reference left as-is: path={}, documentId={}, error={}",
                        span.storagePath(), docId, e.getMessage());
                return keepOriginal;
            }
        }
        // 对话兜底域：模型端解析产物（documents 无行），白名单目录 + 真实路径复核 + 文件存在
        if (existingAssets.contains(span.storagePath())) {
            try {
                String sig = signer.signChatAsset(span.storagePath(), expires);
                String url = MarkdownImageRewriter.buildChatAssetUrl(urlBase(),
                        span.storagePath(), expires, sig);
                logger.debug("Chat image reference signed via fallback (produced by the model side): path={}", span.storagePath());
                return span.htmlImg()
                        ? span.mdPrefix() + "\"" + url + "\""
                        : span.mdPrefix() + url + span.mdSuffix();
            } catch (java.lang.IllegalStateException e) {
                logger.warn("Chat image fallback signing failed, reference left as-is: path={}, error={}",
                        span.storagePath(), e.getMessage());
                return keepOriginal;
            }
        }
        // 两条路都签不出：前端必然取不到图（空 alt 引用连破图提示都没有），必须留痕并区分根因
        if (chatImageAssetPolicy.matchAllowedDir(span.storagePath()) != null) {
            logger.warn("Chat image reference is under a fallback allowed directory but the file is missing/unreadable (possibly cleaned up by the model side), "
                            + "left as-is and not shown in the frontend: path={}, fallbackDirs={}",
                    span.storagePath(), chatImageAssetPolicy.allowedDirs());
        } else {
            logger.warn("Chat image reference did not match a document and is not under a fallback allowed directory, left as-is and not shown in the frontend: path={}, fallbackDirs={}",
                    span.storagePath(), chatImageAssetPolicy.allowedDirs());
        }
        return keepOriginal;
    }

    /**
     * md 与 HTML 两类 span 按位置合并排序（两类正则理论上不相交；防属性值中夹 md 语法的病态
     * 文本重叠，重叠保留首个）
     */
    private static List<ImageSpan> mergeOverlapping(List<ImageSpan> spans) {
        if (spans.size() <= 1) {
            return spans;
        }
        spans.sort(Comparator.comparingInt(ImageSpan::start));
        List<ImageSpan> merged = new ArrayList<>(spans.size());
        int lastEnd = -1;
        for (ImageSpan span : spans) {
            if (span.start() < lastEnd) {
                continue;
            }
            merged.add(span);
            lastEnd = span.end();
        }
        return merged;
    }

    /**
     * 引用扫描结果：原文区间 + 语法段（前缀/引用值/后缀）+ 解析出的存储相对路径 + 形态
     * （md：前缀 {@code ![alt](}、后缀 {@code )}；HTML：前缀 {@code <img … src=}、后缀 {@code "}）
     */
    private record ImageSpan(int start, int end, boolean htmlImg, String mdPrefix, String refValue,
                             String mdSuffix, String storagePath) {
    }
}
