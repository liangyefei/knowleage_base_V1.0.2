package com.hisilicon.knowledge.platform.knowledge.query;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 图片资产 URL 签名器（markdown 在线查看的图片外链防护）
 * <p>对 {@code GET /kb/documents/{documentId}/assets} 的访问参数计算 HMAC-SHA256 签名，
 * 使图片 URL 无法伪造、无法越权访问存储上的任意文件，且短时效自动过期。</p>
 *
 * <p>本类包含<b>两个互不通用的签名域</b>（域标签见 {@link #CHAT_ASSET_DOMAIN}）：
 * <ul>
 *   <li><b>文档图片域</b>——{@link #sign}/{@link #verify}，载荷为三元组，服务
 *       {@code GET /kb/documents/{documentId}/assets}（{@link DocumentAssetResource}）：图片必须
 *       登记为 documents 行，可访问范围收敛在该文档目录子树内；</li>
 *   <li><b>对话兜底域</b>——{@link #signChatAsset}/{@link #verifyChatAsset}，载荷为
 *       {@code "chat-asset" + "\n" + path + "\n" + expires}，服务
 *       {@code GET /kb/chat/assets}（{@link ChatImageAssetResource}）：模型端解析产物
 *       （非平台文档树文件，database 里没有行）没有文档可绑定，改用<b>受配置约束的目录白名单</b>
 *       作为可访问范围。两域使用<b>派生的独立密钥</b>（{@link #chatDomainKey}，载荷另带显式标签），
 *       <b>签名不可跨端点重放</b>。</li>
 * </ul></p>
 *
 * <p>签名载荷<b>钉死为三元组</b>（与 {@link DocumentAssetResource} 校验侧严格一致）：
 * {@code documentId + "\n" + normalizedPath + "\n" + expires}（expires 为秒级时间戳）。
 * <b>刻意不把 url-prefix 纳入签名</b>——前缀只是 URL 的呈现层装饰（换域名/加网关路径），
 * 不参与签名计算：前缀变更不会使已签发的 URL 失效（未过期即可继续用），资产接口校验时
 * 也只验三元组、完全不感知前缀。换密钥或改路径内容才会使旧 URL 失效，这是预期行为。</p>
 *
 * <p>配置项（生产经环境变量注入，如 {@code KB_ASSET_SIGNATURE_SECRET}，不落 application.yml/镜像）：
 * <ul>
 *   <li>{@code kb.asset.signature-secret}——HMAC 密钥。<b>未配置（空白）时的明确策略（fail-closed）：</b>
 *       启动时 WARN 告警；拒绝签发（{@link #isConfigured()} 为 false，markdown 下载保持图片引用原样）；
 *       拒绝校验（{@link #verify} 恒 false，资产接口一律 A0301）。功能整体降级为"图片不显示"，不产生安全开口。</li>
 *   <li>{@code kb.asset.url-expiry-seconds}——URL 有效期（秒），默认 3600。</li>
 *   <li>{@code kb.asset.url-prefix}——生成 URL 的前缀，默认空 = <b>根相对路径</b>
 *       {@code /kb/documents/{documentId}/assets?…}（浏览器按页面 origin 解析，天然多环境自适配）；
 *       跨域/独立域名部署时可配置为绝对前缀（如 {@code https://api.example.com}）。
 *       仅由签发侧（{@link MarkdownImageRewriter}）拼接在 URL 最前面，<b>不参与签名载荷</b>（见上）。</li>
 * </ul></p>
 */
@ApplicationScoped
@Startup
public class AssetUrlSigner {

    private static final Logger logger = LoggerFactory.getLogger(AssetUrlSigner.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 对话兜底图片资产的签名域标签（{@link #signChatAsset}/{@link #verifyChatAsset} 载荷首段）。
     * <p>域隔离手段：文档图片域载荷首段是 documentId，本域首段是固定标签，两者形态本就不同；
     * 标签把"这是另一个授权域"写成显式约定——将来某一域载荷段数变动时也不会因巧合而互相验证
     * 通过（跨端点重放攻击）。</p>
     */
    private static final String CHAT_ASSET_DOMAIN = "chat-asset";

    private final String secret;
    private final long urlExpirySeconds;
    private final String urlPrefix;
    private final String rootPath;

    /**
     * 对话兜底域独立密钥：SHA-256(secret + 0x00 + "chat-asset")（HKDF 式域派生）。
     * <p><b>为什么不能只靠载荷形态区分两个签名域</b>：文档域载荷首段是 documentId，若某文档 id
     * 恰好等于 {@link #CHAT_ASSET_DOMAIN} 字符串，两域载荷逐字节相同、签名可跨端点重放——
     * {@code AssetUrlSignerTest#shouldNotCrossVerifyBetweenSignatureDomains} 实测抓到过该缺陷。
     * 派生独立密钥后，即使载荷巧合相同，跨域签名也必然 HMAC 不符（密钥不同）。</p>
     * <p>文档域继续用原始 secret（密钥不派生），<b>已签发的文档图片 URL 不受影响</b>。</p>
     */
    private final byte[] chatDomainKey;

    /**
     * CDI 构造注入。密钥/前缀/根路径用 {@link Optional} 接收——{@code @ConfigProperty(defaultValue="")}
     * 在 MP Config 语义中等同于"无默认值"（属性变必填，未配置直接启动失败），Optional 才是
     * "可缺省、缺省时功能降级"的正确表达。
     * <p>根路径取 {@code quarkus.http.root-path}（本应用生产恒为 {@code /hisilicon-knowledge}，
     * API 全量挂在 {@code /kb/...} 之前）。它只参与 URL 的呈现拼接、<b>不参与签名载荷</b>——
     * 资产接口校验只吃 documentId/path/expires 三元组，因此带不带根路径、带不带前缀均不改变
     * 已签发 URL 的有效性，跨网关/改前缀不会批量失效。</p>
     */
    @Inject
    public AssetUrlSigner(
            @ConfigProperty(name = "kb.asset.signature-secret") Optional<String> secret,
            @ConfigProperty(name = "kb.asset.url-expiry-seconds", defaultValue = "3600") long urlExpirySeconds,
            @ConfigProperty(name = "kb.asset.url-prefix") Optional<String> urlPrefix,
            @ConfigProperty(name = "quarkus.http.root-path") Optional<String> httpRootPath) {
        this(secret.orElse(""),
                urlExpirySeconds,
                urlPrefix.orElse(""),
                httpRootPath.map(AssetUrlSigner::normalizeRootPath).orElse(""));
    }

    /** 直构便捷入口（单元/集成测试用，绕开 CDI 配置注入；根路径默认空）。CDI 走 {@code @Inject} 构造器 */
    public AssetUrlSigner(String secret, long urlExpirySeconds, String urlPrefix) {
        this(secret, urlExpirySeconds, urlPrefix, "");
    }

    /** 直构便捷入口（含根路径，供 root-path 相关测试注入明确值） */
    public AssetUrlSigner(String secret, long urlExpirySeconds, String urlPrefix, String rootPath) {
        this.secret = secret;
        this.urlExpirySeconds = urlExpirySeconds;
        this.urlPrefix = urlPrefix;
        this.rootPath = normalizeRootPath(rootPath);
        this.chatDomainKey = deriveChatDomainKey(secret);
    }

    /** 对话兜底域独立密钥派生：SHA-256(secret + 0x00 + 域标签)——密钥不同即签名不可跨域互认 */
    private static byte[] deriveChatDomainKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(CHAT_ASSET_DOMAIN.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("对话兜底域签名密钥派生失败", e);
        }
    }

    /** 根路径规范化：{@code null}/空/{@code /} 一律归为空串（无前缀），去尾斜杠防双斜杠 */
    private static String normalizeRootPath(String rootPath) {
        if (rootPath == null) {
            return "";
        }
        String p = rootPath;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return "/".equals(p) ? "" : p;
    }

    /** 启动告警：密钥未配置时功能降级（拒绝签发/校验），尽早暴露配置缺失 */
    @PostConstruct
    void warnIfSecretMissing() {
        if (!isConfigured()) {
            logger.warn("kb.asset.signature-secret is not configured: the markdown image asset API is disabled"
                    + "(signing and signature verification are rejected, image references are left as-is). In production inject it via the environment variable KB_ASSET_SIGNATURE_SECRET");
        }
    }

    /** 密钥是否已配置（非空白）。未配置时 {@link #sign} 抛异常、{@link #verify} 恒 false */
    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }

    /** URL 有效期配置（秒） */
    public long urlExpirySeconds() {
        return urlExpirySeconds;
    }

    /** 生成 URL 的可配置前缀（空 = 相对路径） */
    public String urlPrefix() {
        return urlPrefix;
    }

    /** 应用根路径（{@code quarkus.http.root-path}，空 = 无前缀；不参与签名载荷） */
    public String rootPath() {
        return rootPath;
    }

    /**
     * 当前时刻对应的过期时间戳（秒级）＝ now + urlExpirySeconds
     */
    public long expiryEpochSecondNow() {
        return System.currentTimeMillis() / 1000 + urlExpirySeconds;
    }

    /**
     * 计算签名：HMAC-SHA256(documentId + "\n" + normalizedPath + "\n" + expires)，小写 hex。
     *
     * @param documentId     文档 ID（md 所属文档，绑定图片可访问范围）
     * @param normalizedPath 存储相对路径（{@code /} 分隔、已规范化、位于 md 所在目录子树内）
     * @param expiresEpochSec 过期时间戳（秒级）
     * @return 小写 hex 签名
     * @throws IllegalStateException 密钥未配置（调用方应先检查 {@link #isConfigured()}）
     */
    public String sign(String documentId, String normalizedPath, long expiresEpochSec) {
        if (!isConfigured()) {
            throw new IllegalStateException("kb.asset.signature-secret 未配置，拒绝签发图片资产签名");
        }
        return hmacHex(payload(documentId, normalizedPath, expiresEpochSec));
    }

    /**
     * 校验签名（含过期）：过期、密钥未配置、签名缺失或 HMAC 不符均返回 false。
     * <p>用 {@link MessageDigest#isEqual} 常量时间比较，防时序侧信道逐字节猜签名。</p>
     *
     * @param documentId     请求中的文档 ID
     * @param normalizedPath 请求中的存储相对路径（原始值，未做任何变换）
     * @param expiresEpochSec 请求中的过期时间戳（秒级）
     * @param sig            请求中的签名
     * @return true = 签名有效且未过期
     */
    public boolean verify(String documentId, String normalizedPath, long expiresEpochSec, String sig) {
        if (!isConfigured() || sig == null || sig.isBlank()) {
            return false;
        }
        if (expiresEpochSec <= System.currentTimeMillis() / 1000) {
            return false;
        }
        String expected = hmacHex(payload(documentId, normalizedPath, expiresEpochSec));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算<b>对话兜底域</b>签名：HMAC-SHA256(chat-asset + "\n" + normalizedPath + "\n" + expires)。
     * <p>用于模型端解析产物图片（{@code /kb-files/mineru_output/…} 这类非平台文档树文件，
     * documents 表里没有行，签不出文档图片域的签名）。可访问范围由签发侧与服务侧的
     * <b>目录白名单</b>共同约束，见 {@code kb.chat.image-fallback-dirs}。</p>
     *
     * @param normalizedPath  存储相对路径（{@code /} 分隔、已规范化、已通过白名单目录匹配）
     * @param expiresEpochSec 过期时间戳（秒级）
     * @return 小写 hex 签名
     * @throws IllegalStateException 密钥未配置（调用方应先检查 {@link #isConfigured()}）
     */
    public String signChatAsset(String normalizedPath, long expiresEpochSec) {
        if (!isConfigured()) {
            throw new IllegalStateException("kb.asset.signature-secret 未配置，拒绝签发对话图片资产签名");
        }
        return hmacHex(chatDomainKey, chatAssetPayload(normalizedPath, expiresEpochSec));
    }

    /**
     * 校验<b>对话兜底域</b>签名（含过期）：过期、密钥未配置、签名缺失或 HMAC 不符均返回 false。
     *
     * @param normalizedPath  请求中的存储相对路径（原始值，未做任何变换）
     * @param expiresEpochSec 请求中的过期时间戳（秒级）
     * @param sig             请求中的签名
     * @return true = 签名有效且未过期
     */
    public boolean verifyChatAsset(String normalizedPath, long expiresEpochSec, String sig) {
        if (!isConfigured() || sig == null || sig.isBlank()) {
            return false;
        }
        if (expiresEpochSec <= System.currentTimeMillis() / 1000) {
            return false;
        }
        String expected = hmacHex(chatDomainKey, chatAssetPayload(normalizedPath, expiresEpochSec));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    /** 对话兜底域载荷：域标签 + 路径 + 过期时间（换行拼接，消除拼接歧义；独立密钥见 {@link #chatDomainKey}） */
    private static String chatAssetPayload(String normalizedPath, long expiresEpochSec) {
        return CHAT_ASSET_DOMAIN + "\n" + normalizedPath + "\n" + expiresEpochSec;
    }

    /** 签名载荷：三段用换行拼接（换行不会出现在 documentId/路径中，消除拼接歧义） */
    private static String payload(String documentId, String normalizedPath, long expiresEpochSec) {
        return documentId + "\n" + normalizedPath + "\n" + expiresEpochSec;
    }

    /** HMAC-SHA256 → 小写 hex（文档域：原始 secret 作为密钥，历史签名口径不变） */
    private String hmacHex(String payload) {
        return hmacHex(secret.getBytes(StandardCharsets.UTF_8), payload);
    }

    /** HMAC-SHA256 → 小写 hex（每次新建 Mac 实例：Mac 非线程安全，响应式并发下不可复用） */
    private String hmacHex(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 签名计算失败", e);
        }
    }
}
