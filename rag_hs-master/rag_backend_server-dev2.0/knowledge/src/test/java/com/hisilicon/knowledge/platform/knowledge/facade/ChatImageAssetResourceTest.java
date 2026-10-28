package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import com.hisilicon.knowledge.platform.knowledge.query.ChatImageAssetPolicy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 对话兜底图片资产接口测试（GET /kb/chat/assets，不要求登录）
 *
 * <p>覆盖：模型端解析产物图片无 Authorization 头正常取图、签名校验（篡改/过期/跨签名域重放/
 * 缺失参数）、放行目录白名单（兄弟目录 / 平台文档树 / 回收站 / 上传临时区 / 绝对路径 / 反斜杠 /
 * 越界）、扩展名白名单、文件不存在（含软链逃逸语义由存储层 mock 表达）。</p>
 */
@QuarkusTest
@TestProfile(AssetSignatureTestProfile.class)
class ChatImageAssetResourceTest {

    private static final String FALLBACK_PATH = "mineru_output/1c25e985f396984e/images/page_0_image_31.jpg";

    /** 与主代码一致的 1x1 PNG 文件头字节（仅作响应字节断言，不校验图片合法性） */
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    private final AssetUrlSigner signer =
            new AssetUrlSigner(AssetSignatureTestProfile.TEST_SECRET, 3600, "");

    @Inject
    ChatImageAssetPolicy chatImageAssetPolicy;

    @Inject
    com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);
        MockFileStorageService.reset();
        MockFileStorageService.setReadContent(PNG_BYTES);
        MockFileStorageService.setExistingPaths(Set.of(FALLBACK_PATH));
    }

    // ======================== 正常取图（不要求登录） ========================

    @Test
    void shouldServeParserOutputImageWithoutAuthorizationHeader() {
        long expires = System.currentTimeMillis() / 1000 + 3600;

        byte[] result = given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(FALLBACK_PATH, expires))
                // 刻意不带 Authorization 头
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .header("Content-Type", containsString("image/jpeg"))
                .header("Cache-Control", equalTo("private, max-age=3600"))
                .extract().asByteArray();

        assertEquals(new String(PNG_BYTES, StandardCharsets.ISO_8859_1),
                new String(result, StandardCharsets.ISO_8859_1), "应返回图片原始字节");
        assertEquals(FALLBACK_PATH, MockFileStorageService.getLastReadPath(), "应按存储相对路径读取");
    }

    @Test
    void shouldResolveTraversalFreePathWithinAllowedDir() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/1c25e985f396984e/./images/../images/page_0_image_31.jpg";
        MockFileStorageService.setExistingPaths(Set.of(FALLBACK_PATH));

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(path, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .header("Content-Type", containsString("image/jpeg"));

        // 目录内 ./ 与 ../ 消解后仍位于放行目录内，按规范化路径读取
        assertEquals(FALLBACK_PATH, MockFileStorageService.getLastReadPath());
    }

    // ======================== 签名校验 ========================

    @Test
    void shouldRejectTamperedSignature() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String realSig = signer.signChatAsset(FALLBACK_PATH, expires);
        char lastChar = realSig.charAt(realSig.length() - 1);
        String badSig = realSig.substring(0, realSig.length() - 1) + (lastChar == '0' ? '1' : '0');

        given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", expires)
                .queryParam("sig", badSig)
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0301"));

        assertNull(MockFileStorageService.getLastReadPath(), "签名不过不应触达存储");
    }

    @Test
    void shouldRejectExpiredSignature() {
        long expires = System.currentTimeMillis() / 1000 - 10;

        given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(FALLBACK_PATH, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0301"));
    }

    @Test
    void shouldRejectSignatureSignedForDifferentPath() {
        long expires = System.currentTimeMillis() / 1000 + 3600;

        given()
                .queryParam("path", "mineru_output/other/page_9.png")
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(FALLBACK_PATH, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0301"));
    }

    /**
     * 跨签名域重放（安全关键）：文档图片域签发的签名不得在兜底端点上通过。
     * <p>否则 markdown 下载页的签名 URL 参数可直接搬到 {@code /kb/chat/assets} 复用，
     * 绕过本端点的白名单收敛。</p>
     */
    @Test
    void shouldRejectDocumentDomainSignatureReplay() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        // 即便把 documentId 伪造成兜底域的标签字符串，载荷形态仍不同 → 必须拒绝
        String docDomainSig = signer.sign("chat-asset", FALLBACK_PATH, expires);

        given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", expires)
                .queryParam("sig", docDomainSig)
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0301"));

        assertNull(MockFileStorageService.getLastReadPath(), "跨域签名不应触达存储");
    }

    @Test
    void shouldRejectMissingSignatureOrNonNumericExpires() {
        long expires = System.currentTimeMillis() / 1000 + 3600;

        given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", expires)
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0400"));

        given()
                .queryParam("path", FALLBACK_PATH)
                .queryParam("expires", "not-a-number")
                .queryParam("sig", "deadbeef")
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    // ======================== 放行目录白名单（合法签名 + 非法路径 → A0400） ========================

    @Test
    void shouldRejectSiblingDirSharingPrefix() {
        assertRejectedAsInvalidParameter("mineru_output_old/x.png");
    }

    @Test
    void shouldRejectPlatformDocumentTreePath() {
        // 平台文档树路径必须走文档域端点（有 documents 行、有子树校验），不得从兜底端点读出
        assertRejectedAsInvalidParameter("kb/CV610pro/docs/图1.png");
    }

    @Test
    void shouldRejectTrashAndUploadTempPaths() {
        assertRejectedAsInvalidParameter(".trash/kb/CV610pro/docs/图1.png");
        assertRejectedAsInvalidParameter(".uploads/session/1.png");
    }

    @Test
    void shouldRejectAbsoluteBackslashAndTraversalPaths() {
        assertRejectedAsInvalidParameter("/mineru_output/a.png");
        assertRejectedAsInvalidParameter("C:/mineru_output/a.png");
        assertRejectedAsInvalidParameter("mineru_output\\a.png");
        assertRejectedAsInvalidParameter("../mineru_output/a.png");
    }

    // ======================== 扩展名与文件存在性 ========================

    @Test
    void shouldRejectNonImageExtension() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/1c25e985f396984e/images/page_0_image_31.txt";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(path, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", containsString("txt"));
    }

    @Test
    void shouldRejectMissingFile() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "mineru_output/gone/page_0_image_31.jpg";
        // 文件已被模型端清理（不在预置现存路径集合内）

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(path, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0706"));

        assertNull(MockFileStorageService.getLastReadPath(), "判存不过不应触达读取");
    }

    @Test
    void shouldNotExposeSignedPathOutsideAllowedDirOnSignatureBypass() {
        // 兜底目录配置生效性前置断言：策略确实只放行 mineru_output（配置未生效时本测试无意义）
        assertEquals("mineru_output", chatImageAssetPolicy.matchAllowedDir(FALLBACK_PATH));
        assertNull(chatImageAssetPolicy.matchAllowedDir(".trash/kb/x.png"));
    }

    // ======================== 辅助 ========================

    private void assertRejectedAsInvalidParameter(String path) {
        long expires = System.currentTimeMillis() / 1000 + 3600;

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.signChatAsset(path, expires))
                .when().get("/kb/chat/assets")
                .then().statusCode(200)
                .body("code", equalTo("A0400"));

        assertNull(MockFileStorageService.getLastReadPath(), "非法路径不应触达存储: " + path);
    }
}
