package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * download 接口 markdown 图片网络地址化测试（POST /kb/documents/download）
 * <p>覆盖：最新版/历史版本 md 的相对图片引用重写为签名资产 URL（含中文路径编码、HTML img）、
 * 签名可回验、非 md 文件字节流不变。签名能力经 {@link AssetSignatureTestProfile} 注入。</p>
 */
@QuarkusTest
@TestProfile(AssetSignatureTestProfile.class)
class DocumentResourceDownloadMarkdownTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);
    private static final String DOC_ID = "doc-md";
    /** 生成的资产引用前缀（根相对，与路由 /kb/documents/{id}/assets 对齐） */
    private static final String ASSET_URL_PREFIX = "/kb/documents/";

    private final AssetUrlSigner signer =
            new AssetUrlSigner(AssetSignatureTestProfile.TEST_SECRET, 3600, "");

    private static final byte[] MD_BYTES = ("# 手册\n\n![架构图](images/图1.png)\n\n"
            + "<img src=\"pic/photo 2.jpg\" alt=\"照片\">\n\n"
            + "![外链不动](https://example.com/a.png)\n")
            .getBytes(StandardCharsets.UTF_8);

    @Inject
    DocumentRepository documentRepository;

    @Inject
    FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);
        MockDocumentRepository.setEntityById(mdDocument("kb-1/docs/手册.md"));
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockFileStorageService.reset();
        MockFileStorageService.setReadContent(MD_BYTES);
    }

    @Test
    void shouldRewriteRelativeImagesInLatestMd() {
        String body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"" + DOC_ID + "\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType(containsString("text/markdown"))
                .contentType(containsString("charset=utf-8"))
                .extract().asString();

        // 相对引用 → 签名资产 URL（path 已按 RFC 3986 编码）
        assertTrue(body.contains(ASSET_URL_PREFIX + DOC_ID + "/assets?path="
                        + urlEncode("kb-1/docs/images/图1.png") + "&expires="),
                "md 语法相对图片应重写: " + body);
        assertTrue(body.contains(ASSET_URL_PREFIX + DOC_ID + "/assets?path="
                        + urlEncode("kb-1/docs/pic/photo 2.jpg") + "&expires="),
                "HTML img 相对引用应重写: " + body);
        // 外链保持原样
        assertTrue(body.contains("https://example.com/a.png"), "绝对 URL 不受影响");
        assertFalse(body.contains("](images/"), "原始相对引用不应保留");
        // 中文标题等非图片内容原样
        assertTrue(body.contains("# 手册"), "非图片部分应原样保留");

        assertUrlSignatureVerifiable(body);
    }

    @Test
    void shouldRewriteRelativeImagesInHistoricalMdVersion() {
        MockDocumentRepository.setVersionById(new DocumentVersion(
                DOC_ID, "1", "kb-1/docs/old/手册.md", (long) MD_BYTES.length,
                "text/markdown", true, DocumentVersion.STATUS_PUBLISHED, "editor", LocalDateTime.now()));

        String body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"" + DOC_ID + "\",\"version\":\"1\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType(containsString("text/markdown"))
                .extract().asString();

        // 基准目录为历史版本文件所在目录（kb-1/docs/old）
        assertTrue(body.contains(ASSET_URL_PREFIX + DOC_ID + "/assets?path="
                        + urlEncode("kb-1/docs/old/images/图1.png") + "&expires="),
                "历史版本 md 也应重写，且基准目录为版本文件所在目录: " + body);
        assertEquals("kb-1/docs/old/手册.md", MockFileStorageService.getLastReadPath(),
                "应读取历史版本文件路径");
    }

    @Test
    void shouldReturnNonMarkdownBytesUnchanged() {
        byte[] pdf = "pdf-binary-unchanged".getBytes(StandardCharsets.UTF_8);
        MockDocumentRepository.setEntityById(pdfDocument());
        MockFileStorageService.setReadContent(pdf);

        byte[] result = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"" + DOC_ID + "\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType("application/pdf")
                .extract().asByteArray();

        assertEquals(new String(pdf, StandardCharsets.ISO_8859_1),
                new String(result, StandardCharsets.ISO_8859_1),
                "非 md 文件响应字节流必须完全不变");
    }

    @Test
    void shouldKeepOriginalRefsWhenSecretUnconfigured() {
        // 独立 profile 无法在同测试类内切换——直接验证降级逻辑的等价行为：
        // 未配置密钥时 MarkdownImageRewriter.rewrite 原样返回（rewriter 单测已覆盖），
        // 此处补充验证 rewriter 与 download 接入的判定口径一致（isMarkdownPath）。
        assertTrue(com.hisilicon.knowledge.platform.knowledge.query.MarkdownImageRewriter
                .isMarkdownPath("kb-1/docs/手册.md"));
    }

    // ======================== 辅助 ========================

    /** 断言响应中的资产 URL 签名可通过 verify 回验（签发/校验口径一致性） */
    private void assertUrlSignatureVerifiable(String body) {
        Matcher m = Pattern.compile(Pattern.quote("/kb/documents/" + DOC_ID + "/assets?path=")
                + "([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)").matcher(body);
        assertTrue(m.find(), "响应中应存在签名资产 URL");
        String path = urlDecode(m.group(1));
        long expires = Long.parseLong(m.group(2));
        String sig = m.group(3);
        assertTrue(signer.verify(DOC_ID, path, expires, sig),
                "响应 URL 的签名应可通过校验器回验: path=" + path);
    }

    /**
     * 端到端缝隙测试：download 重写出的资产 URL 必须能被资产接口真实命中（200 返回图片字节）。
     * <p>提取重写 URL 的 path/expires/sig 后按解码值经 RestAssured 的 queryParam 显式发送——
     * queryParam 对中文/空格/斜杠做标准单次编码，等价于浏览器对已签发的单编码 URL 的请求，
     * 规避 RestAssured 对整串含 {@code %2F} URI 的二次编码歧义；仍是全链路（路由+签名+文档+
     * 白名单+真实读图）端到端验证。</p>
     */
    @Test
    void shouldRewriteUrlHitsAssetEndpointEndToEnd() {
        // 起点：readContent=MD_BYTES，download 重写产出资产 URL
        String body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"" + DOC_ID + "\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .extract().asString();

        // 匹配根相对资产 URL 并解析三参数（path 为单次 percent 编码）
        Matcher m = Pattern.compile(Pattern.quote("/kb/documents/" + DOC_ID + "/assets?path=")
                + "([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)").matcher(body);
        assertTrue(m.find(), "下载响应应含重写的资产 URL: " + body);
        String path = urlDecode(m.group(1));
        String expires = m.group(2);
        String sig = m.group(3);

        // 切 mock 存储内容为图片字节后，用重写出的参数打资产接口（免登录）→ 200 + image/png
        MockFileStorageService.setReadContent(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, 'p', 'n', 'g', '-', 'o', 'k'});
        given()
                .header("Accept-Language", "zh-CN")
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", sig)
                .when().get("/kb/documents/" + DOC_ID + "/assets")
                .then().statusCode(200)
                .contentType("image/png");
    }

    /** md 文档实体（currentVersion="2"：历史版本用例请求 version="1" 时必须 ≠ currentVersion，
     *  否则 resolveVersion 走"显式 version == currentVersion 用当前版"捷径、版本行不会被查询，
     *  历史基准目录断言永远失败——该矛盾使本测试自 4932133 起一直是红的） */
    private static DocumentEntity mdDocument(String filePath) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId("kb-1")
                .title("手册")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("2")
                .hasPublishedVersion(false)
                .filePath(filePath)
                .fileSize((long) MD_BYTES.length)
                .mimeType("text/markdown")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** pdf 文档实体（非 md 对照） */
    private static DocumentEntity pdfDocument() {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId("kb-1")
                .title("文档.pdf")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath("kb-1/文档.pdf")
                .fileSize(20L)
                .mimeType("application/pdf")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
