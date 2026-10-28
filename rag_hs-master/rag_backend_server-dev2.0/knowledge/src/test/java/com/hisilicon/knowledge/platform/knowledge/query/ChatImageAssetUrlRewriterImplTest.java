package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.LocalFileStorageService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话图片引用网络地址化实现单元测试（纯 JUnit，无容器）
 * <p>覆盖：/kb-files 引用改写与签名可验证、路径解析防线（前缀/scheme/越界/反斜杠/白名单）、
 * 百分号编码、批量去重查库、文档缺失与密钥未配置降级、多引用重建保序、
 * 含空格路径命中正常改写与未命中降级尖括号包裹、
 * <b>兜底链路</b>（模型端解析产物图片：文档未命中 → 放行目录白名单 → 判存 → 对话兜底域签名）。</p>
 */
class ChatImageAssetUrlRewriterImplTest {

    /** G.PRM.04：签名 URL 解析模式提为类常量，避免方法体内重复编译 */
    private static final Pattern SIGNED_URL_PATTERN =
            Pattern.compile("path=([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)");

    private static final String SECRET = "unit-test-secret";
    private static final String IMAGE_DOC_ID = "doc-img-1";
    /** 与《docs/流式输出带图片.txt》样例同形的引用 */
    private static final String SAMPLE_REF =
            "/kb-files/kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/image-20260820171505891.png";
    private static final String SAMPLE_PATH =
            "kb/CV610/docs/03参考案例/Sample指导说明/vie/figures/image-20260820171505891.png";
    /** 模型端解析产物图片（documents 表无行，需走兜底）：与线上实际引用同形 */
    private static final String MINERU_REF =
            "/kb-files/mineru_output/1c25e985f396984e/images/page_0_image_31.jpg";
    private static final String MINERU_PATH = "mineru_output/1c25e985f396984e/images/page_0_image_31.jpg";
    /** 兜底放行目录（与 application.yml kb.chat.image-fallback-dirs 同形） */
    private static final String FALLBACK_DIRS = "mineru_output";

    private ChatImageAssetUrlRewriterImpl rewriter(FakeDocumentRepository repo) {
        return rewriter(repo, new FakeFileStorageService());
    }

    private ChatImageAssetUrlRewriterImpl rewriter(FakeDocumentRepository repo, FakeFileStorageService storage) {
        return new ChatImageAssetUrlRewriterImpl(repo, new AssetUrlSigner(SECRET, 3600, ""),
                storage, new ChatImageAssetPolicy(FALLBACK_DIRS));
    }

    // ======================== 正常改写 ========================

    @Test
    void shouldRewriteKbFilesRefWithVerifiableSignature() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);

        String out = rewriter(repo).rewrite("前文\n\n![image-20260820171505891](" + SAMPLE_REF + ")\n后文")
                .await().atMost(Duration.ofSeconds(5));

        Matcher m = SIGNED_URL_PATTERN.matcher(out);
        assertTrue(m.find(), "应生成 path/expires/sig 三参数: " + out);
        String path = urlDecode(m.group(1));
        assertEquals(SAMPLE_PATH, path, "签名 path 应为解码后的存储相对路径: " + out);
        assertTrue(new AssetUrlSigner(SECRET, 3600, "").verify(IMAGE_DOC_ID, path,
                        Long.parseLong(m.group(2)), m.group(3)),
                "签名应可通过 verify 往返校验");
        assertFalse(out.contains("/kb-files/"), "原始挂载前缀引用不应保留: " + out);
        // md 语法与 alt 文本必须保留：仅替换括号内引用值
        assertTrue(out.contains("![image-20260820171505891](/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "应保持 ![alt](URL) 结构: " + out);
        assertTrue(Pattern.compile("&sig=[0-9a-f]+\\)").matcher(out).find(),
                "URL 应以右括号收尾、保持 md 结构: " + out);
        assertTrue(out.startsWith("前文"), "非图片部分应原样保留");
        assertTrue(out.endsWith("后文"), "非图片部分应原样保留");
    }

    @Test
    void shouldRewriteMultipleRefsAndDedupeLookup() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(
                SAMPLE_PATH, IMAGE_DOC_ID,
                "kb/CV610/docs/图2.jpg", "doc-img-2");

        String out = rewriter(repo).rewrite(
                        "![一](" + SAMPLE_REF + ") 中间 ![二](/kb-files/kb/CV610/docs/图2.jpg) ![三](" + SAMPLE_REF + ")")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("/kb/documents/" + IMAGE_DOC_ID + "/assets?path="), "引用一应改写: " + out);
        assertTrue(out.contains("/kb/documents/doc-img-2/assets?path="), "引用二应改写: " + out);
        assertEquals(3, out.split("assets\\?path=", -1).length - 1, "三处引用（含两处同路径）都应改写");
        assertEquals(List.of(SAMPLE_PATH, "kb/CV610/docs/图2.jpg"),
                List.copyOf(repo.lastQueriedPaths), "同路径应去重后批量查库");
    }

    @Test
    void shouldDecodePercentEncodedRef() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String encodedRef = "/kb-files/kb/kb-1/docs/my%20images/%E5%9B%BE1.png";
        repo.pathOwners = Map.of("kb/kb-1/docs/my images/图1.png", IMAGE_DOC_ID);

        String out = rewriter(repo).rewrite("![已编码](" + encodedRef + ")")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("path=" + urlEncode("kb/kb-1/docs/my images/图1.png")),
                "编码引用应解码后定位文档并重新编码: " + out);
    }

    @Test
    void shouldKeepTitleSyntaxAndAngleBracketWrapper() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of("kb/kb-1/docs/图1.png", IMAGE_DOC_ID);

        String out = rewriter(repo).rewrite("![alt](/kb-files/kb/kb-1/docs/图1.png \"标题文字\")")
                .await().atMost(Duration.ofSeconds(5));
        assertTrue(out.contains("/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "带 title 的引用应改写: " + out);

        String out2 = rewriter(repo).rewrite("![alt](</kb-files/kb/kb-1/docs/图1.png>)")
                .await().atMost(Duration.ofSeconds(5));
        assertTrue(out2.contains("/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "尖括号包裹的引用应改写: " + out2);
    }

    // ======================== 跳过与安全边界 ========================

    @Test
    void shouldSkipNonKbFilesRef() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String md = "![相对](images/图1.png)\n![站点绝对](/kb/other/a.png)\n![锚点](#section)";
        String out = rewriter(repo).rewrite(md).await().atMost(Duration.ofSeconds(5));

        assertEquals(md, out, "非 /kb-files 前缀引用应保持原样");
        assertEquals(null, repo.lastQueriedPaths, "无可改写引用不应查库");
    }

    @Test
    void shouldSkipSchemeAndBackslashRef() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String md = "![外链](https://example.com/a.png)\n![反斜杠](/kb-files/kb\\a.png)";
        String out = rewriter(repo).rewrite(md).await().atMost(Duration.ofSeconds(5));

        assertEquals(md, out, "http 外链与反斜杠引用应保持原样");
    }

    @Test
    void shouldSkipTraversalEscapingStorageRoot() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String md = "![越界](/kb-files/../etc/secret.png)";
        String out = rewriter(repo).rewrite(md).await().atMost(Duration.ofSeconds(5));

        assertEquals(md, out, "越出存储根的引用应保持原样");
        assertEquals(null, repo.lastQueriedPaths, "不可签发引用不应查库");
    }

    @Test
    void shouldSkipNonImageExtension() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String md = "![文档](/kb-files/kb/kb-1/docs/readme.md)";
        String out = rewriter(repo).rewrite(md).await().atMost(Duration.ofSeconds(5));

        assertEquals(md, out, "非图片扩展名引用应保持原样");
    }

    @Test
    void shouldKeepOriginalRefWhenDocumentMissing() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();

        String text = "![图](" + SAMPLE_REF + ")";
        String out = rewriter(repo).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "路径查不到所属文档应保持原样（图片不显示但回答完整）");
    }

    // ======================== 降级保留：含空格引用尖括号包裹 ========================

    @Test
    void shouldRewriteSpaceRefWhenDocumentHit() {
        // 命中路径含空格：与落盘 file_path（保留内部空格）精确匹配，正常改写为 %20 编码 URL
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String spacePath = "kb/CV610/docs/my docs/图 1.png";
        repo.pathOwners = Map.of(spacePath, IMAGE_DOC_ID);

        String out = rewriter(repo).rewrite("![图 1](/kb-files/" + spacePath + ")")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("path=" + urlEncode(spacePath)),
                "含空格路径命中文档应正常改写: " + out);
        assertFalse(out.contains("</kb-files"), "成功改写不应走降级包裹: " + out);
    }

    @Test
    void shouldWrapSpaceRefWhenDocumentMissing() {
        // 含空格引用查不到文档：原样保留不符合 CommonMark（断链成纯文本），降级为尖括号包裹
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();

        String text = "![图 1](/kb-files/kb/CV610/docs/my docs/图 1.png)";
        String out = rewriter(repo).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals("![图 1](</kb-files/kb/CV610/docs/my docs/图 1.png>)", out,
                "含空格的未命中引用应包裹为尖括号形态: " + out);
    }

    @Test
    void shouldWrapOnlyPathPartWhenDocumentMissingWithTitle() {
        // title 语法降级：只包裹引号前的 path 段，title 原样保留
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();

        String text = "![图 1](/kb-files/kb/CV610/docs/my docs/图 1.png \"标题 文字\")";
        String out = rewriter(repo).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals("![图 1](</kb-files/kb/CV610/docs/my docs/图 1.png> \"标题 文字\")", out,
                "降级应只包裹 path 段、title 原样: " + out);
    }

    @Test
    void shouldNotTouchSpaceRefOutsideRewriteScope() {
        // 非图片扩展名（解析防线即拒绝，非 span）：不做包裹，逐字节原样
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String md = "![文档](/kb-files/kb/kb-1/docs/my docs/readme.md)";
        String out = rewriter(repo).rewrite(md).await().atMost(Duration.ofSeconds(5));

        assertEquals(md, out, "解析防线拒绝的引用应原样保留（不包裹）");
    }

    @Test
    void shouldReturnOriginalWhenSecretUnconfigured() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);
        ChatImageAssetUrlRewriterImpl unconfigured = new ChatImageAssetUrlRewriterImpl(
                repo, new AssetUrlSigner("", 3600, ""),
                new FakeFileStorageService(), new ChatImageAssetPolicy(FALLBACK_DIRS));

        String text = "![图](" + SAMPLE_REF + ")";
        String out = unconfigured.rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "密钥未配置应降级为引用原样");
        assertEquals(null, repo.lastQueriedPaths, "降级时不应查库");
    }

    @Test
    void shouldHandleNullAndEmptyText() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        assertEquals("", rewriter(repo).rewrite(null).await().atMost(Duration.ofSeconds(5)));
        assertEquals("", rewriter(repo).rewrite("").await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void shouldRewriteRefEmbeddedInJsonStringValue() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);

        // 模型 answer 分片即为 JSON 文本，引用嵌在 string 值内——改写值不得破坏 JSON 结构
        String json = "{\"answer\": \"看图 ![image](" + SAMPLE_REF + ")\", \"type\": \"answer\"}";
        String out = rewriter(repo).rewrite(json).await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("\"answer\": \"看图 ![image](/kb/documents/"),
                "改写应保留 JSON 前缀: " + out);
        assertFalse(out.contains("kb-files"), "JSON 内引用也应被改写: " + out);
        assertTrue(out.contains("\"type\": \"answer\"}"), "JSON 结构应完整保留: " + out);
    }

    // ======================== HTML img 引用 ========================

    @Test
    void shouldRewriteHtmlImgSrcWithAttributesPreserved() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);

        String html = "<img src=\"" + SAMPLE_REF + "\" alt=\"蓝牙配对请求\">";
        String out = rewriter(repo).rewrite("前文 " + html + " 后文")
                .await().atMost(Duration.ofSeconds(5));

        Matcher m = SIGNED_URL_PATTERN.matcher(out);
        assertTrue(m.find(), "HTML img 应生成签名三参数: " + out);
        assertTrue(new AssetUrlSigner(SECRET, 3600, "").verify(IMAGE_DOC_ID, SAMPLE_PATH,
                        Long.parseLong(m.group(2)), m.group(3)),
                "HTML img 签名应可通过 verify 往返校验");
        assertTrue(out.contains("<img src=\"/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "src 值应替换为签名 URL（统一双引号）: " + out);
        assertTrue(out.contains("\" alt=\"蓝牙配对请求\">"), "src 之后的 alt 属性应原样保留: " + out);
        assertFalse(out.contains("/kb-files/"), "原始挂载前缀不应保留: " + out);
    }

    @Test
    void shouldRewriteHtmlImgSingleQuotesAndSrcAfterOtherAttrs() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of("kb/kb-1/docs/图1.png", IMAGE_DOC_ID);

        String out = rewriter(repo).rewrite("<IMG alt='图' width=100 src='/kb-files/kb/kb-1/docs/图1.png'>")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("<IMG alt='图' width=100 src=\"/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "大写标签/单引号/src 在后均应识别，前导属性原样: " + out);
    }

    @Test
    void shouldRewriteHtmlAndMarkdownMixed() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(
                SAMPLE_PATH, IMAGE_DOC_ID,
                "kb/CV610/docs/图2.jpg", "doc-img-2");

        String out = rewriter(repo).rewrite(
                        "![一](" + SAMPLE_REF + ") 与 <img src=\"/kb-files/kb/CV610/docs/图2.jpg\" alt=\"二\">")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("![一](/kb/documents/" + IMAGE_DOC_ID + "/assets?path="), "md 引用应改写: " + out);
        assertTrue(out.contains("<img src=\"/kb/documents/doc-img-2/assets?path="), "HTML 引用应改写: " + out);
        assertEquals(List.of(SAMPLE_PATH, "kb/CV610/docs/图2.jpg"),
                List.copyOf(repo.lastQueriedPaths), "两类引用应合并一次批量查库");
    }

    @Test
    void shouldKeepHtmlImgForNonKbFilesOrInvalidPaths() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        String html = "<img src=\"/kb/other/a.png\"> 与 <img src='https://e.com/a.png'> 与 "
                + "<img src=\"/kb-files/kb\\a.png\"> 与 <img src=\"/kb-files/../etc/s.png\"> 与 "
                + "<img src=\"/kb-files/kb/kb-1/docs/readme.md\" alt=\"文档\">";
        String out = rewriter(repo).rewrite(html).await().atMost(Duration.ofSeconds(5));

        assertEquals(html, out, "非 /kb-files、scheme、反斜杠、越界、非图片扩展名的 img 应原样保留");
        assertEquals(null, repo.lastQueriedPaths, "不可签发引用不应查库");
    }

    @Test
    void shouldKeepHtmlImgWhenDocumentMissing() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();

        String html = "<img src=\"" + SAMPLE_REF + "\" alt=\"蓝牙配对请求\">";
        String out = rewriter(repo).rewrite(html).await().atMost(Duration.ofSeconds(5));

        assertEquals(html, out, "HTML 引用查不到所属文档应逐字节原样保留");
    }

    // ======================== 兜底链路：模型端解析产物图片 ========================

    @Test
    void shouldSignFallbackUrlForParserOutputImage() {
        // 线上实际形态：![](/kb-files/mineru_output/{hash}/images/page_0_image_31.jpg)
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH);

        String out = rewriter(repo, storage).rewrite("![](" + MINERU_REF + ")")
                .await().atMost(Duration.ofSeconds(5));

        Matcher m = Pattern.compile("/kb/chat/assets\\?path=([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)").matcher(out);
        assertTrue(m.find(), "应生成对话兜底签名 URL: " + out);
        String path = urlDecode(m.group(1));
        assertEquals(MINERU_PATH, path, "签名 path 应为解码后的存储相对路径");
        assertTrue(new AssetUrlSigner(SECRET, 3600, "").verifyChatAsset(path,
                        Long.parseLong(m.group(2)), m.group(3)),
                "兜底签名应可通过 verifyChatAsset 往返校验");
        assertFalse(out.contains("/kb-files/"), "原始挂载前缀引用不应保留: " + out);
        assertTrue(out.startsWith("![](") && out.endsWith(")"), "md 语法（含空 alt）应保留: " + out);
        assertEquals(FALLBACK_DIRS, storage.lastDir, "应按命中的放行目录判存");
    }

    @Test
    void shouldSignFallbackUrlWithSpacePaddedRef() {
        // 线上实发形态（模型流式输出实测）：括号内两侧带空格 —— ![图8-1]( /kb-files/mineru_output/… )
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH);

        String text = "图 8-1 如下所示：\n\n![图8-1]( " + MINERU_REF + " )\n\n> **图片描述**";
        String out = rewriter(repo, storage).rewrite(text).await().atMost(Duration.ofSeconds(5));

        Matcher m = Pattern.compile(
                "!\\[图8-1\\]\\(/kb/chat/assets\\?path=([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)\\)").matcher(out);
        assertTrue(m.find(), "空格填充的 md 引用应改写为签名 URL: " + out);
        assertEquals(MINERU_PATH, urlDecode(m.group(1)), "签名 path 应为解码后的存储相对路径");
        assertFalse(out.contains("/kb-files/"), "原始挂载前缀引用不应保留: " + out);
        assertTrue(out.contains("> **图片描述**"), "图片前后的正文应原样保留");
    }

    @Test
    void shouldNotSignFallbackWhenFileMissing() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(); // 目录内文件已被模型端清理

        String text = "![](" + MINERU_REF + ")";
        String out = rewriter(repo, storage).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "文件不存在应保持原样（不签发注定 404 的 URL）");
    }

    @Test
    void shouldNotSignFallbackForDirOutsideWhitelist() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        // 即使文件真的存在，白名单外的目录也不得签发（不是"存在就签"）
        storage.existing = Set.of("mineru_output_old/x.png", "kb/CV610pro/docs/图1.png");

        String text = "![a](/kb-files/mineru_output_old/x.png) ![b](/kb-files/kb/CV610pro/docs/图1.png)";
        String out = rewriter(repo, storage).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "白名单外路径应保持原样");
        assertNull(storage.lastPaths, "白名单外路径连判存都不应触达存储");
    }

    @Test
    void shouldBatchExistenceCheckPerAllowedDir() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH, "mineru_output/other/g.png");

        String out = rewriter(repo, storage).rewrite(
                        "![](" + MINERU_REF + ") ![](/kb-files/mineru_output/other/g.png)")
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(2, out.split("assets\\?path=", -1).length - 1, "两张图都应签发: " + out);
        assertEquals(2, storage.lastPaths.size(), "同一放行目录应合并为一次批量判存");
    }

    @Test
    void shouldNotCheckExistenceForDocumentHitPaths() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(); // 磁盘上没有：命中文档的路径不应受兜底判存影响

        String out = rewriter(repo, storage).rewrite("![图](" + SAMPLE_REF + ")")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "命中文档应走文档域签名: " + out);
        assertNull(storage.lastPaths, "命中文档的路径不应占用兜底判存");
    }

    @Test
    void shouldSignBothDomainsInSameAnswer() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of(SAMPLE_PATH, IMAGE_DOC_ID);
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH);

        String out = rewriter(repo, storage).rewrite(
                        "图一 ![doc](" + SAMPLE_REF + ")\n图二 ![](" + MINERU_REF + ")")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("![doc](/kb/documents/" + IMAGE_DOC_ID + "/assets?path="),
                "文档域引用应改写为文档资产 URL: " + out);
        assertTrue(out.contains("![](") && out.contains("/kb/chat/assets?path="),
                "兜底域引用应改写为对话资产 URL: " + out);
        assertFalse(out.contains("/kb-files/"), "原始挂载前缀不应残留: " + out);
        assertEquals(List.of(MINERU_PATH), List.copyOf(storage.lastPaths),
                "兜底判存只应收录未命中文档的路径");
    }

    @Test
    void shouldRewriteHtmlImgViaFallback() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH);

        String out = rewriter(repo, storage)
                .rewrite("<img src=\"" + MINERU_REF + "\" alt=\"解析产物\">")
                .await().atMost(Duration.ofSeconds(5));

        assertTrue(out.contains("<img src=\"/kb/chat/assets?path="), "HTML img 也应走兜底: " + out);
        assertTrue(out.contains("\" alt=\"解析产物\">"), "src 之后的属性应原样保留: " + out);
    }

    @Test
    void shouldKeepOriginalWhenFallbackDisabled() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.existing = Set.of(MINERU_PATH);
        ChatImageAssetUrlRewriterImpl disabled = new ChatImageAssetUrlRewriterImpl(
                repo, new AssetUrlSigner(SECRET, 3600, ""), storage, new ChatImageAssetPolicy(""));

        String text = "![](" + MINERU_REF + ")";
        String out = disabled.rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "兜底关闭（放行目录未配置）时应保持原样");
        assertNull(storage.lastPaths, "兜底关闭时不应触达存储");
    }

    @Test
    void shouldDegradeWhenExistenceCheckFails() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.pathOwners = Map.of();
        FakeFileStorageService storage = new FakeFileStorageService();
        storage.fail = true;

        String text = "![](" + MINERU_REF + ")";
        String out = rewriter(repo, storage).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "判存失败应按不可用降级，不影响回答完整性（不抛异常）");
    }

    @Test
    void shouldReturnOriginalWhenDocumentLookupFails() {
        FakeDocumentRepository repo = new FakeDocumentRepository();
        repo.fail = true;

        String text = "![图](" + SAMPLE_REF + ")";
        String out = rewriter(repo).rewrite(text).await().atMost(Duration.ofSeconds(5));

        assertEquals(text, out, "查库失败应整段原样返回（既有降级语义不变）");
    }

    // ======================== 测试假仓储 ========================

    /** 进程内假文档仓储：仅路径→id 解析可编程，其余安全默认 */
    static class FakeDocumentRepository implements DocumentRepository {
        Map<String, String> pathOwners = Map.of();
        volatile Collection<String> lastQueriedPaths;
        volatile boolean fail;

        @Override
        public Uni<Map<String, String>> findIdsByFilePaths(Collection<String> filePaths) {
            lastQueriedPaths = filePaths;
            if (fail) {
                return Uni.createFrom().failure(new IllegalStateException("模拟查库失败"));
            }
            return Uni.createFrom().item(pathOwners);
        }

        // ===== 以下不被改写流程触达，安全默认 =====

        @Override public Uni<DocumentEntity> findById(DocumentId id) { return Uni.createFrom().nullItem(); }
        @Override public Uni<DocumentEntity> persist(DocumentEntity entity) { return Uni.createFrom().item(entity); }
        @Override public Uni<DocumentEntity> update(DocumentEntity entity) { return Uni.createFrom().item(entity); }
        @Override public Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findReviewingByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByIds(Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Long> countByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds, Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<Long> countByKbIdAndStatusIn(String kbId, Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds, Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<String>> findVersionFilePaths(String documentId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<String>> findVersionFilePathsByDocumentIds(Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Void> deleteAllByIds(Collection<String> documentIds, Collection<String> statuses) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> archiveVersion(DocumentVersion version) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> delete(DocumentId id) { return Uni.createFrom().voidItem(); }
        @Override public Uni<DocumentVersion> findVersion(String documentId, String versionNumber) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> markProcessing(DocumentId id) { return Uni.createFrom().voidItem(); }
    }

    // ======================== 辅助 ========================

    /**
     * 进程内假存储服务：只实现兜底判存（真实实现另含软链真实路径复核，见
     * {@code LocalFileStorageServiceTest}），判存口径与真实实现一致——存在 + 放行目录段级包含。
     */
    static class FakeFileStorageService extends LocalFileStorageService {
        /** 模拟磁盘现存路径（判存通过的集合） */
        Set<String> existing = Set.of();
        /** 模拟判存 IO 失败 */
        volatile boolean fail;
        volatile String lastDir;
        volatile Collection<String> lastPaths;

        FakeFileStorageService() {
            // 只触达被 override 的方法，vertx 传 null 暴露意外触达
            super("mock-storage-root", null);
        }

        @Override
        public Uni<Set<String>> existingRegularFilesWithin(String relativeDir,
                                                           Collection<String> relativePaths) {
            lastDir = relativeDir;
            lastPaths = relativePaths;
            if (fail) {
                return Uni.createFrom().failure(new IllegalStateException("模拟判存失败"));
            }
            Set<String> found = new HashSet<>();
            for (String path : relativePaths) {
                if (existing.contains(path) && path.startsWith(relativeDir + "/")) {
                    found.add(path);
                }
            }
            return Uni.createFrom().item(found);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
