package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.AssetUrlSigner;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 图片资产接口测试（GET /kb/documents/{documentId}/assets，不要求登录）
 * <p>覆盖：无 Authorization 头正常取图、签名错误/过期拒绝、路径穿越（../ / 反斜杠 / 绝对路径 /
 * 双重编码绕过）、扩展名白名单、参数缺失、文档不存在。</p>
 */
@QuarkusTest
@TestProfile(AssetSignatureTestProfile.class)
class DocumentAssetResourceTest {

    private static final String KB_ID = "kb-1";
    private static final String DOC_ID = "doc-1";
    private static final String MD_PATH = "kb-1/docs/手册.md";

    /** 与主代码一致的 1x1 PNG 文件头字节（仅作响应字节断言，不校验图片合法性） */
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    private final AssetUrlSigner signer =
            new AssetUrlSigner(AssetSignatureTestProfile.TEST_SECRET, 3600, "");

    @Inject
    DocumentRepository documentRepository;

    @Inject
    FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);
        MockDocumentRepository.setEntityById(mdDocument());
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockFileStorageService.reset();
        MockFileStorageService.setReadContent(PNG_BYTES);
    }

    // ======================== 正常取图（不要求登录） ========================

    @Test
    void shouldServeImageWithoutAuthorizationHeader() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/docs/images/图1.png";

        byte[] result = given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                // 刻意不带 Authorization 头
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .header("Content-Type", containsString("image/png"))
                .header("Cache-Control", equalTo("private, max-age=3600"))
                .extract().asByteArray();

        assertEquals(new String(PNG_BYTES, java.nio.charset.StandardCharsets.ISO_8859_1),
                new String(result, java.nio.charset.StandardCharsets.ISO_8859_1),
                "应返回图片原始字节");
        assertEquals(path, MockFileStorageService.getLastReadPath(), "应按规范化后的存储路径读取");
    }

    @Test
    void shouldResolveTraversalFreePathWithinBaseDir() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/docs/./images/../images/图1.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .header("Content-Type", containsString("image/png"));

        // ./ 与同目录内 ../ 消解后仍位于子树内，按规范化路径读取
        assertEquals("kb-1/docs/images/图1.png", MockFileStorageService.getLastReadPath());
    }

    // ======================== 签名与过期 ========================

    @Test
    void shouldRejectTamperedSignature() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/docs/images/图1.png";
        // 末字符确定性篡改：固定替换为与真实末字符不同的字符。
        // 若盲改 '0'，真实签名末字符恰为 '0' 时（约 1/16 概率）签名自碰撞、请求会成功，测试偶发失败。
        String realSig = signer.sign(DOC_ID, path, expires);
        char lastChar = realSig.charAt(realSig.length() - 1);
        String badSig = realSig.substring(0, realSig.length() - 1) + (lastChar == '0' ? '1' : '0');

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", badSig)
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0301"))
                .body("message", containsString("验证签名失败"));
    }

    @Test
    void shouldRejectExpiredSignature() {
        long expires = System.currentTimeMillis() / 1000 - 10;
        String path = "kb-1/docs/images/图1.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0301"));
    }

    @Test
    void shouldRejectSignatureSignedForDifferentPath() {
        // 合法签名但绑定的路径与请求不一致（签名对路径的绑定语义）
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String signedPath = "kb-1/docs/images/图1.png";
        String requestedPath = "kb-1/docs/images/图2.png";

        given()
                .queryParam("path", requestedPath)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, signedPath, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0301"));
    }

    // ======================== 路径穿越防护（合法签名 + 非法路径 → A0400） ========================

    @Test
    void shouldRejectParentTraversal() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/../secret.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));

        assertEquals(null, MockFileStorageService.getLastReadPath(), "穿越路径不应触达存储");
    }

    @Test
    void shouldRejectEscapingBaseDir() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        // md 在 kb-1/docs/ 下：合法签名 + 同 KB 兄弟目录路径（不含 ..）也必须拒绝——
        // 可引用范围收敛在 md 所在目录子树内，与签发侧口径一致
        String path = "kb-1/other-dir/secret.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));

        assertEquals(null, MockFileStorageService.getLastReadPath(), "越界路径不应触达存储");
    }

    @Test
    void shouldRejectBackslashPath() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1\\docs\\images\\图1.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldRejectWindowsAbsolutePath() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "C:/Windows/system32/config.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldRejectStorageAbsolutePath() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "/etc/passwd.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldNotEscapeBaseDirOnDoubleEncodedTraversal() {
        // 双重编码绕过：服务端单次解码后为字面量 "%2e%2e"，签名针对解码值签发才可通过——
        // 攻击者按字面量拿到签名后，路径规范化不把 "%2e%2e" 当 ".." 消解，
        // 读取被限制在 md 所在目录子树内的字面量路径上（无任意文件读取）
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String decodedLiteral = "kb-1/docs/%2e%2e/%2e%2e/secret.png";

        given()
                .queryParam("path", decodedLiteral)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, decodedLiteral, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200);

        // 安全区断言：读取路径 == 字面量规范化路径（仍在 md 目录子树内），未越界到外部文件
        assertEquals("kb-1/docs/%2e%2e/%2e%2e/secret.png", MockFileStorageService.getLastReadPath(),
                "双重编码残余应作为字面量路径处理，不得越出基准目录子树");
    }

    // ======================== 扩展名白名单与参数 ========================

    @Test
    void shouldRejectNonImageExtension() {
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/docs/notes.txt";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0701"))
                .body("message", containsString("txt"));
    }

    @Test
    void shouldRejectMissingSignature() {
        long expires = System.currentTimeMillis() / 1000 + 3600;

        given()
                .queryParam("path", "kb-1/docs/images/图1.png")
                .queryParam("expires", expires)
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldRejectNonNumericExpires() {
        given()
                .queryParam("path", "kb-1/docs/images/图1.png")
                .queryParam("expires", "not-a-number")
                .queryParam("sig", "deadbeef")
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0400"));
    }

    @Test
    void shouldReturnNotFoundForMissingDocument() {
        MockDocumentRepository.setEntityById(null);
        long expires = System.currentTimeMillis() / 1000 + 3600;
        String path = "kb-1/docs/images/图1.png";

        given()
                .queryParam("path", path)
                .queryParam("expires", expires)
                .queryParam("sig", signer.sign(DOC_ID, path, expires))
                .when().get("/kb/documents/{documentId}/assets", DOC_ID)
                .then().statusCode(200)
                .body("code", equalTo("A0430"))
                .body("message", containsString("不存在"));
    }

    // ======================== 辅助 ========================

    /** md 文档实体（基准目录 = kb-1/docs） */
    private static DocumentEntity mdDocument() {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId(KB_ID)
                .title("手册")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath(MD_PATH)
                .fileSize((long) PNG_BYTES.length)
                .mimeType("text/markdown")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
