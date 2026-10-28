package com.hisilicon.knowledge.platform.knowledge.query;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * markdown 相对图片引用网络地址化（在线查看时图片可显示）
 *
 * <p>背景：zip 上传会把图片作为 md 的同类别兄弟文档落盘，存储路径 =
 * {@code kb/{KB名}/{类别链名…}/{清洗后文件名}}，即 md 内相对图片引用与
 * {@code dirname(md.file_path)} 拼接后物理存在。下载 md 时将
 * {@code ![alt](ref)} 与 {@code <img src="ref">} 中的相对引用重写为
 * {@code {root-path}/kb/documents/{documentId}/assets?path=…&expires=…&sig=…}（免登录图片资产接口，
 * 见 {@link DocumentAssetResource}；根路径前缀取值 {@code quarkus.http.root-path}，见
 * {@link AssetUrlSigner#rootPath()}），浏览器渲染时经签名 URL 取图。</p>
 *
 * <p>处理范围：仅重写相对引用。跳过任意 scheme（http/https/data:/ftp: 等，含协议相对
 * {@code //}）、纯锚点 {@code #…}、站点绝对 {@code /…}（无法映射存储子树）；
 * 解析出基准目录子树、含 {@code ..} 越界或非图片扩展名（png/jpg/jpeg/bmp）的引用
 * 一律保持原样（资产接口同样校验，双重防线）。</p>
 *
 * <p><b>已知边界（接受）：</b>
 * <ul>
 *   <li>md 按非 UTF-8 编码保存时，重写按 UTF-8 解码/编码可能出现乱码——上传链路统一
 *       UTF-8，此为历史存量极端场景，按 UTF-8 处理；</li>
 *   <li>同名唯一化（{@code {name}_1.{ext}}）后按原始名解析的引用指向不存在文件，
 *       图片 404 不显示——二期再引入 DB 回退解析；</li>
 *   <li>改写不成功的 markdown 语法引用若含空格（未编码空格路径不符合 CommonMark 链接语法，
 *       原样保留会渲染成纯文本），降级为尖括号包裹形态 {@code ![alt](&lt;my dir/图 1.png&gt;)}
 *       保持合法链接语义（{@link #wrapSpaceRef}；无空格引用逐字节原样，HTML img 属性值带引号
 *       本就合法不改）；</li>
 *   <li>纯 CPU 正则重写，无磁盘 IO；密钥未配置时整体降级为引用原样返回（见
 *       {@link AssetUrlSigner} 的配置策略）。</li>
 * </ul></p>
 */
@ApplicationScoped
public class MarkdownImageRewriter {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownImageRewriter.class);

    /** 可外链的图片扩展名白名单（与上传白名单的图片子集一致；小写） */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp");

    /**
     * markdown 图片语法 {@code ![alt](ref "title")}——ref 段整段捕获（含可选 title）。
     * <p>包内共用：markdown 下载重写与对话图片网络化（{@link ChatImageAssetUrlRewriterImpl}）同一语法口径。</p>
     */
    static final Pattern MD_IMAGE = Pattern.compile("(!\\[[^\\]]*\\]\\()([^)\\n]+)(\\))");

    /**
     * HTML img 标签 src 属性（单/双引号，大小写不敏感）。
     * <p>只锚定到 src 值本身（两组），src 之后的其余属性（alt/width/…）不在匹配范围内、原样保留。
     * 包内共用：markdown 下载重写与对话图片网络化（{@link ChatImageAssetUrlRewriterImpl}）同一识别口径。</p>
     */
    static final Pattern HTML_IMG_SRC =
            Pattern.compile("(<img\\b[^>]*?\\bsrc\\s*=\\s*)(\"[^\"]*\"|'[^']*')",
                    Pattern.CASE_INSENSITIVE);

    /** 任意 URI scheme（http:/https:/data:/ftp:…含协议相对之外的绝对 scheme）；包内共用 */
    static final Pattern SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*:.*");

    private final AssetUrlSigner signer;

    public MarkdownImageRewriter(AssetUrlSigner signer) {
        this.signer = signer;
    }

    /**
     * 判断存储路径是否为 markdown 文件（按扩展名判定，与上传侧同源——经 {@link FileTypeUtil}）
     * <p>供 download 接口做最小接入判定用（facade 包无法直接访问包私有的 FileTypeUtil）。</p>
     *
     * @param filePath 文件存储相对路径（{@code documents.file_path} 或版本表 {@code file_path}）
     * @return true = 扩展名为 md
     */
    public static boolean isMarkdownPath(String filePath) {
        return FileTypeUtil.fromPath(filePath).filter("md"::equals).isPresent();
    }

    /**
     * 从存储路径反解小写扩展名（无扩展名为空 {@code Optional}）
     * <p>供资产接口做图片扩展名白名单判定用（FileTypeUtil 为包私有，facade 经此桥接，
     * 与上传/查询侧扩展名口径同源）。</p>
     *
     * @param filePath 文件存储相对路径
     * @return 小写扩展名（如 {@code "png"}）；无扩展名或入参为空为空 {@code Optional}
     */
    public static Optional<String> imageExtensionOf(String filePath) {
        return FileTypeUtil.fromPath(filePath);
    }

    /**
     * 重写 md 内容中的相对图片引用为签名 URL
     *
     * @param documentId md 所属文档 ID（签名绑定，限定可访问的图片范围）
     * @param mdFilePath md 的存储相对路径（基准目录 = 其所在目录）
     * @param mdBytes    md 文件字节（按 UTF-8 解码，见类注释已知边界）
     * @return 重写后的 md 文本；密钥未配置时原样返回（功能降级）
     */
    public String rewrite(String documentId, String mdFilePath, byte[] mdBytes) {
        String md = new String(mdBytes, StandardCharsets.UTF_8);
        if (!signer.isConfigured()) {
            logger.warn("Image signature secret not configured, markdown image references left as-is: documentId={}", documentId);
            return md;
        }
        String baseDir = baseDirOf(mdFilePath);
        md = rewritePattern(MD_IMAGE, documentId, baseDir, md, false);
        md = rewritePattern(HTML_IMG_SRC, documentId, baseDir, md, true);
        return md;
    }

    /**
     * 按正则重写：第 2 组为引用值，匹配范围外的内容原样保留；引用不可安全外链时整段保持原样。
     * <p>markdown 语法三组（前缀/引用值/后缀），替换为 前缀+URL+后缀；HTML img 两组
     * （前缀/带引号值），替换为 前缀+双引号URL（引号统一为双引号，属性等其余部分本就在
     * 匹配范围外）。{@code appendReplacement} 需字面量，{@code quoteReplacement} 防止
     * URL 中的 {@code $}/{@code \} 被当引用语义。</p>
     */
    private String rewritePattern(Pattern pattern, String documentId, String baseDir,
                                  String md, boolean htmlImg) {
        Matcher matcher = pattern.matcher(md);
        StringBuilder sb = new StringBuilder(md.length());
        while (matcher.find()) {
            String originalValue = matcher.group(2);
            // HTML img 的第 2 组捕获值带引号（"[^"]*"|'[^']*'），先剥离再解析
            String candidate = htmlImg ? stripQuotes(originalValue) : originalValue;
            String url = toSignedUrl(documentId, baseDir, candidate).orElse(null);
            String replacement;
            if (url != null) {
                replacement = htmlImg
                        ? matcher.group(1) + "\"" + url + "\""
                        : matcher.group(1) + url + matcher.group(3);
                logger.debug("Image reference rewritten: documentId={}, ref={}", documentId, originalValue);
            } else if (htmlImg) {
                // HTML 属性值自带引号，含空格本就合法，失败原样保留
                replacement = matcher.group();
            } else {
                // markdown 语法降级保留：含空格的未编码引用不符合 CommonMark（断链成纯文本），
                // 包裹为尖括号形态 ![alt](<path with spaces.png> "title") 保持合法链接语义
                replacement = matcher.group(1) + wrapSpaceRef(candidate) + matcher.group(3);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 剥离首尾成对的单/双引号（HTML 属性值捕获形态） */
    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 相对引用 → 签名 URL；不可安全外链（scheme/锚点/绝对路径/越界/非图片扩展名/签名失败）
     * 返回空 {@code Optional} 表示保持原样
     */
    private Optional<String> toSignedUrl(String documentId, String baseDir, String rawRef) {
        String ref = rawRef.trim();
        // markdown 尖括号包裹（含空格路径的合法写法）：<path with spaces.png>
        if (ref.startsWith("<") && ref.endsWith(">")) {
            ref = ref.substring(1, ref.length() - 1).trim();
        }
        if (ref.isEmpty() || ref.startsWith("#") || ref.startsWith("/")) {
            return Optional.empty();
        }
        // title 语法：path "title"——截掉引号起的内容（无引号的原始空格路径不受影响）
        int quote = firstQuoteIndex(ref);
        if (quote >= 0) {
            ref = ref.substring(0, quote).trim();
        }
        if (ref.isEmpty() || ref.startsWith("//") || SCHEME.matcher(ref).matches()) {
            return Optional.empty();
        }
        // 引用是 URL 编码形态时先解码（与资产接口 decode 后校验的口径一致）
        Optional<String> decoded = decodePercent(ref);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        // 反斜杠/盘符直接拒绝（Windows 语义分隔符与盘符在存储路径中不存在）
        String decodedRef = decoded.get();
        if (decodedRef.indexOf('\\') >= 0 || decodedRef.indexOf(':') >= 0) {
            return Optional.empty();
        }
        Optional<String> normalized = resolveWithinBase(baseDir, decodedRef);
        if (normalized.isEmpty() || normalized.get().isEmpty()) {
            return Optional.empty();
        }
        String path = normalized.get();
        if (!IMAGE_EXTENSIONS.contains(FileTypeUtil.fromPath(path).orElse(""))) {
            return Optional.empty();
        }
        long expires = signer.expiryEpochSecondNow();
        String sig = signer.sign(documentId, path, expires);
        return Optional.of(buildUrl(documentId, path, expires, sig));
    }

    /**
     * 生成图片资产 URL：{@code {url-prefix}{quarkus.http.root-path}/kb/documents/{documentId}/assets?path=…&expires=…&sig=…}
     * <p>签发侧共用静态入口：markdown 下载重写（实例方法 {@link #buildUrl}）与对话图片网络化
     * （{@link ChatImageAssetUrlRewriterImpl}）同一拼接口径。前缀两层组合（均<b>不参与签名载荷</b>，
     * 见 {@link AssetUrlSigner}）：
     * <ul>
     *   <li>{@code urlPrefix}——跨域/独立域名部署时可配置绝对前缀（如 {@code https://api.example.com}），
     *       默认空；</li>
     *   <li>{@code rootPath}——应用根路径（本应用生产恒为 {@code /hisilicon-knowledge}），
     *       默认空 = 无前缀。**必须携带**：否则浏览器请求 {@code /kb/documents/...} 会漏掉
     *       根路径一级、命中真实挂载之外路径而 404。</li>
     * </ul>
     * 默认（两者皆空）为<b>根相对路径</b> {@code /kb/documents/{documentId}/assets?…}。</p>
     * <p>query 值按 RFC 3986 百分号编码（空格 → {@code %20} 而非 {@code +}，
     * 与 query 参数的标准解码一致，中文/空格路径安全）。</p>
     */
    static String buildAssetUrl(AssetUrlBase base, String documentId,
                                String normalizedPath, long expires, String sig) {
        return normalizeUrlPrefix(base.urlPrefix()) + base.rootPath()
                + "/kb/documents/" + urlEncode(documentId)
                + "/assets?path=" + urlEncode(normalizedPath)
                + "&expires=" + expires
                + "&sig=" + sig;
    }

    /**
     * 生成<b>对话兜底</b>图片资产 URL：
     * {@code {url-prefix}{quarkus.http.root-path}/kb/chat/assets?path=…&expires=…&sig=…}
     * <p>用于模型端解析产物图片（{@code /kb-files/mineru_output/…} 这类 documents 表无行的文件）：
     * 没有 documentId 可绑定，因此 URL 不带文档段，可访问范围由目录白名单
     * （{@link ChatImageAssetPolicy}）+ 存储层真实路径复核共同约束。签名域与文档图片域
     * <b>不可互用</b>（见 {@link AssetUrlSigner#signChatAsset}）。</p>
     * <p>前缀组合规则与 {@link #buildAssetUrl} 完全一致（均不参与签名载荷），默认即根相对路径
     * {@code /kb/chat/assets?…}。</p>
     */
    static String buildChatAssetUrl(AssetUrlBase base,
                                    String normalizedPath, long expires, String sig) {
        return normalizeUrlPrefix(base.urlPrefix()) + base.rootPath()
                + "/kb/chat/assets?path=" + urlEncode(normalizedPath)
                + "&expires=" + expires
                + "&sig=" + sig;
    }

    /** 前缀规范化：去尾斜杠，避免与根相对基路径拼出双斜杠（空前缀 → 根相对 /kb/...） */
    private static String normalizeUrlPrefix(String urlPrefix) {
        String prefix = urlPrefix == null ? "" : urlPrefix;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /** 实例便捷重载：前缀/根路径取自 {@link AssetUrlSigner} 配置 */
    private String buildUrl(String documentId, String normalizedPath, long expires, String sig) {
        return buildAssetUrl(urlBase(), documentId, normalizedPath, expires, sig);
    }

    private AssetUrlBase urlBase() {
        return new AssetUrlBase(signer.urlPrefix(), signer.rootPath());
    }

    /** RFC 3986 编码：URLEncoder 的 {@code +}（表单空格）替换为 {@code %20}；包内共用 */
    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 解码引用中的百分号编码（{@code %E5%9B%BE.png} → 中文文件名）。
     * <p>先把 {@code +} 还原为 {@code %2B} 再用 URLDecoder（其表单语义会把 {@code +} 解成空格，
     * 而路径中的 {@code +} 是合法文件名字符）；含非法转义序列时返回空 {@code Optional}（保持原样）。包内共用。</p>
     */
    static Optional<String> decodePercent(String ref) {
        if (ref.indexOf('%') < 0) {
            return Optional.of(ref);
        }
        try {
            return Optional.of(URLDecoder.decode(ref.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 相对引用解析到基准目录子树内并规范化（{@code /} 分隔）。
     * <p>逐段消解 {@code .}/空段；{@code ..} 弹栈，栈空（越出基准目录/存储根）或结果
     * 不在 baseDir 子树内（{@code ..} 越出 md 所在目录）即拒绝。签发侧拒绝即保持原样；
     * 资产接口（{@link DocumentAssetResource}）复用 {@link #normalizeRelative} 做同语义校验，
     * 确保签发与校验两侧的路径语义完全一致。</p>
     *
     * @param baseDir 基准存储目录（{@code /} 分隔，可为空串 = 存储根）
     * @param ref     已解码的相对路径（调用方须先行拒绝反斜杠/盘符）
     * @return 规范化后的存储相对路径；越界或无法解析为空 {@code Optional}
     */
    public static Optional<String> resolveWithinBase(String baseDir, String ref) {
        String joined = baseDir.isEmpty() ? ref : baseDir + "/" + ref;
        Optional<String> normalized = normalizeRelative(joined);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        // 前缀包含校验：规范化结果必须仍位于基准目录子树内（单独 pop 守卫只保证不越出存储根，
        // 拦不住 "../兄弟目录" 场景——md 的可引用范围必须收敛在其所在目录子树内）
        if (!baseDir.isEmpty() && !normalized.get().startsWith(baseDir + "/")) {
            return Optional.empty();
        }
        return normalized;
    }

    /**
     * 独立规范化存储相对路径（不与基准目录拼接）：逐段消解 {@code .}/空段，
     * {@code ..} 弹栈，栈空（越出存储根）返回空 {@code Optional}。
     * <p>资产接口专用：签名 path 参数即完整存储相对路径，规范化后由调用方做子树包含校验。</p>
     *
     * @param ref 已解码的相对路径（调用方须先行拒绝反斜杠/盘符）
     * @return 规范化后的存储相对路径；越出存储根或解析为空返回空 {@code Optional}
     */
    public static Optional<String> normalizeRelative(String ref) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : ref.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    return Optional.empty();
                }
                stack.pollLast();
                continue;
            }
            stack.addLast(segment);
        }
        return Optional.of(String.join("/", stack));
    }

    /** md 所在存储目录：反斜杠归一为 {@code /}，取末个 {@code /} 之前部分（无分隔符 = 存储根） */
    private static String baseDirOf(String mdFilePath) {
        if (mdFilePath == null || mdFilePath.isBlank()) {
            return "";
        }
        String normalized = mdFilePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    /** 引用中首个引号位置（title 语法起点）；无引号返回 -1。包内共用 */
    static int firstQuoteIndex(String ref) {
        int dq = ref.indexOf('"');
        int sq = ref.indexOf('\'');
        if (dq < 0) {
            return sq;
        }
        return sq < 0 ? dq : Math.min(dq, sq);
    }

    /**
     * markdown 图片引用降级保留形态（改写不成功时保持链接语义，包内共用）：
     * 含空格的未编码引用不符合 CommonMark 链接语法（前端渲染断链成纯文本，比无空格路径的
     * 404 链接更差），包裹为尖括号形态 {@code &lt;path with spaces.png&gt;}；
     * title 语法 {@code path "title"} 只包裹引号前的 path 段（{@code &lt;path&gt; "title"}）。
     * <p>幂等：已尖括号包裹、无空格、path 段为空或含 {@code &lt;}（尖括号目标不允许）的引用原样返回。
     * 无空格引用返回原值——保证无空格路径的降级行为与历史逐字节一致。</p>
     */
    static String wrapSpaceRef(String rawRef) {
        String ref = rawRef.trim();
        if (ref.isEmpty() || ref.indexOf(' ') < 0) {
            return rawRef;
        }
        if (ref.startsWith("<") && ref.endsWith(">")) {
            return rawRef;  // 已是尖括号形态，不二次包裹（幂等）
        }
        int quote = firstQuoteIndex(ref);
        String pathPart;
        String titlePart;
        if (quote >= 0) {
            pathPart = ref.substring(0, quote).trim();
            titlePart = " " + ref.substring(quote).trim();
        } else {
            pathPart = ref;
            titlePart = "";
        }
        if (pathPart.isEmpty() || pathPart.indexOf('<') >= 0) {
            return rawRef;  // 无 path 段或 path 含 <（尖括号目标不允许），保持原样
        }
        return "<" + pathPart + ">" + titlePart;
    }
}
