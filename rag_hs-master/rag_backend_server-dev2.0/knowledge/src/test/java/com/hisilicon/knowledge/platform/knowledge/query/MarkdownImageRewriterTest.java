package com.hisilicon.knowledge.platform.knowledge.query;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * markdown 图片引用重写器单元测试（纯 JUnit，无容器）
 * <p>覆盖：md 语法/HTML img 重写、中文与空格路径 RFC 3986 编码、多图、无图、
 * scheme/锚点/绝对路径跳过、越界与非图片扩展名保持原样、签名可验证、密钥未配置降级、
 * 降级保留时含空格引用的尖括号包裹（幂等/title 段/无空格回归）。</p>
 */
class MarkdownImageRewriterTest {

    /** G.PRM.04：签名 URL 解析模式提为类常量，避免方法体内重复编译 */
    private static final Pattern SIGNED_URL_PATTERN =
            Pattern.compile("path=([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)");

    private static final String SECRET = "unit-test-secret";
    private static final String DOC_ID = "doc-1";
    private static final String MD_PATH = "kb/kb-1/docs/手册.md";

    private final AssetUrlSigner signer = new AssetUrlSigner(SECRET, 3600, "");
    private final MarkdownImageRewriter rewriter = new MarkdownImageRewriter(signer);

    // ======================== md 语法重写 ========================

    @Test
    void shouldRewriteRelativeImage() {
        String md = "# 标题\n\n![架构图](images/图1.png)\n";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/images/图1.png")),
                "相对图片应重写为签名 URL: " + out);
        assertFalse(out.contains("](images/"), "原始相对引用不应保留");
        assertTrue(out.startsWith("# 标题"), "非图片部分应原样保留");
    }

    @Test
    void shouldRewriteHtmlImgTag() {
        String md = "前文\n<img src=\"pic/photo.jpg\" alt=\"照片\" width=\"100\" />\n后文";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/pic/photo.jpg")),
                "img src 相对路径应重写: " + out);
        assertTrue(out.contains("alt=\"照片\""), "标签其余属性应原样保留");
        assertTrue(out.contains("width=\"100\""), "标签其余属性应原样保留");
    }

    @Test
    void shouldRewriteHtmlImgTagWithSingleQuotes() {
        String md = "<img src='pic/a.bmp'>";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/pic/a.bmp")),
                "单引号 img src 也应重写: " + out);
    }

    @Test
    void shouldRewriteMultipleImages() {
        String md = "![一](a.png) 中间文字 <img src=\"sub/b.jpg\"> ![二](c.jpeg)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/a.png")));
        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/sub/b.jpg")));
        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/c.jpeg")));
        assertFalse(out.contains("](a.png)"), "全部图片均应被重写");
    }

    @Test
    void shouldEncodeChineseAndSpacePerRfc3986() {
        String md = "![含空格](my images/图 片.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        String expected = urlEncode("kb/kb-1/docs/my images/图 片.png");
        assertTrue(out.contains("path=" + expected),
                "中文与空格应按 RFC 3986 编码（空格为 %20 而非 +）: " + out);
        assertFalse(out.contains("path=" + expected.replace("%20", "+")),
                "不允许表单语义的 + 空格");
    }

    @Test
    void shouldDecodePercentEncodedRefAndSignDecodedPath() {
        // md 内已是百分号编码的引用：签发侧对解码后的存储路径签名（资产接口解码后校验，口径一致）
        String md = "![已编码](images/%E5%9B%BE1.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains("path=" + urlEncode("kb/kb-1/docs/images/图1.png")),
                "编码引用应解码后归一并重新编码: " + out);
    }

    @Test
    void shouldKeepTitleSyntaxStructureWhenRewriting() {
        String md = "![alt](img/a.png \"标题文字\")";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains(getSignedUrlSubstring("kb/kb-1/docs/img/a.png")),
                "带 title 的引用路径应重写: " + out);
        assertTrue(out.startsWith("![alt]("), "alt 文本应保留");
    }

    @Test
    void shouldLeaveMdWithoutImagesUnchanged() {
        String md = "# 纯文本\n\n- 列表项\n\n[链接](other.md)\n";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "无图片引用内容应原样返回");
    }

    // ======================== 跳过与安全边界 ========================

    @Test
    void shouldSkipHttpAndDataAndAnchorRefs() {
        String md = "![外链](https://example.com/a.png)\n"
                + "![数据](data:image/png;base64,iVBOR)\n"
                + "![锚点](#section)\n";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "http/data/锚点引用不应重写");
    }

    @Test
    void shouldSkipTraversalRef() {
        String md = "![越界](../../etc/secret.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "越出 md 所在目录子树的引用应保持原样（不可签发）");
    }

    @Test
    void shouldSkipNonImageExtension() {
        String md = "![文档](notes/readme.txt)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "非图片扩展名引用应保持原样");
    }

    // ======================== 降级保留：含空格引用尖括号包裹 ========================

    @Test
    void shouldWrapSpaceRefWhenRewriteFails() {
        // 改写不成功（非图片扩展名）+ 引用含空格：原样保留不符合 CommonMark（断链成纯文本），
        // 降级为尖括号包裹保持合法链接语义
        String md = "![文档](notes/my readme.txt)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals("![文档](<notes/my readme.txt>)", out,
                "含空格的失败引用应包裹为尖括号形态: " + out);
    }

    @Test
    void shouldWrapOnlyPathPartWhenDegradeWithTitle() {
        // title 语法降级：只包裹引号前的 path 段，title 原样保留
        String md = "![文档](bad dir/a.txt \"标题 文字\")";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals("![文档](<bad dir/a.txt> \"标题 文字\")", out,
                "降级应只包裹 path 段、title 原样: " + out);
    }

    @Test
    void shouldNotDoubleWrapAlreadyBracketedSpaceRef() {
        // 已尖括号包裹的失败引用原样返回（幂等，重写不成功时二次下载不叠加包裹）
        String md = "![文档](<bad dir/a.txt>)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "已包裹的失败引用应原样保留: " + out);
    }

    @Test
    void shouldKeepSpacelessFailedRefByteIdentical() {
        // 无空格的失败引用行为不变（逐字节原样，历史行为回归保护）
        String md = "![越界](../../etc/secret.png) ![非图片](notes/readme.txt) ![绝对](/kb/x/a.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "无空格失败引用应保持历史行为（原样）");
    }

    @Test
    void shouldKeepHtmlImgSpaceRefUnchangedOnDegrade() {
        // HTML img 属性值自带引号，含空格本就合法，降级不包裹
        String md = "<img src=\"my dir/a.txt\" alt=\"照片\">";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "HTML img 失败引用应原样保留: " + out);
    }

    @Test
    void wrapSpaceRefShouldRejectPathWithAngleBracket() {
        // path 段含 <（尖括号目标不允许的字符）→ 原样返回
        assertEquals("a<b c.png", MarkdownImageRewriter.wrapSpaceRef("a<b c.png"));
        // 无空格 / 纯空白 ref 原样返回
        assertEquals("a.png", MarkdownImageRewriter.wrapSpaceRef("a.png"));
        assertEquals("  ", MarkdownImageRewriter.wrapSpaceRef("  "));
    }

    @Test
    void shouldSkipSiteAbsoluteRef() {
        String md = "![绝对](/kb/other/a.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "站点绝对路径引用应保持原样");
    }

    @Test
    void shouldSkipBackslashRef() {
        String md = "![反斜杠](images\\a.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "含反斜杠的引用应保持原样");
    }

    @Test
    void shouldUseConfiguredUrlPrefix() {
        AssetUrlSigner prefixedSigner = new AssetUrlSigner(SECRET, 3600, "/hisilicon-knowledge/");
        MarkdownImageRewriter prefixed = new MarkdownImageRewriter(prefixedSigner);
        String out = prefixed.rewrite(DOC_ID, MD_PATH, "![a](a.png)".getBytes(StandardCharsets.UTF_8));

        assertTrue(out.contains("/hisilicon-knowledge/kb/documents/doc-1/assets?path="),
                "可配置前缀应出现在生成 URL 中: " + out);
    }

    // ======================== 签名一致性 ========================

    @Test
    void shouldProduceVerifiableSignature() {
        String md = "![签名](images/图1.png)";
        String out = rewriter.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        Matcher m = SIGNED_URL_PATTERN.matcher(out);
        assertTrue(m.find(), "应生成 path/expires/sig 三参数: " + out);
        String path = urlDecode(m.group(1));
        long expires = Long.parseLong(m.group(2));
        String sig = m.group(3);
        assertTrue(signer.verify(DOC_ID, path, expires, sig),
                "生成 URL 的签名应可通过 verify 校验: path=" + path);
    }

    @Test
    void shouldReturnOriginalWhenSecretUnconfigured() {
        MarkdownImageRewriter unconfigured = new MarkdownImageRewriter(new AssetUrlSigner("", 3600, ""));
        String md = "![a](a.png)";
        String out = unconfigured.rewrite(DOC_ID, MD_PATH, md.getBytes(StandardCharsets.UTF_8));

        assertEquals(md, out, "密钥未配置时降级为引用原样");
    }

    @Test
    void shouldDetectMarkdownPath() {
        assertTrue(MarkdownImageRewriter.isMarkdownPath("kb/kb-1/手册.md"));
        assertTrue(MarkdownImageRewriter.isMarkdownPath("kb/kb-1/手册.MD"), "扩展名判定不区分大小写");
        assertFalse(MarkdownImageRewriter.isMarkdownPath("kb/kb-1/doc.pdf"));
        assertFalse(MarkdownImageRewriter.isMarkdownPath("kb/kb-1/noext"));
    }

    // ======================== 辅助 ========================

    /** 期望出现在重写结果中的 URL 片段（path= 参数已编码） */
    private static String getSignedUrlSubstring(String storagePath) {
        return "/kb/documents/" + DOC_ID + "/assets?path=" + urlEncode(storagePath) + "&expires=";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
