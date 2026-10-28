package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：生产 root-path 下重写的资产 URL 必须携带 {@code quarkus.http.root-path} 前缀，
 * 且带前缀的完整 URL 能被资产接口真实命中（200 + image/png）。
 * <p>背景：单测默认无 root-path，生成 {@code /kb/...} 可命中；生产
 * {@code quarkus.http.root-path=/hisilicon-knowledge}，若 URL 缺前缀则浏览器请求漏掉根路径一级 → 404。
 * 本测试显式注入 root-path 复现生产形态，防该缺陷回归。</p>
 */
@QuarkusTest
@TestProfile(AssetRootPathTestProfile.class)
class DocumentAssetRootPathTest {

    private static final String ROOT_PATH = "/hisilicon-knowledge";
    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);
    private static final String DOC_ID = "doc-root-path";

    private static final byte[] MD_BYTES = ("# 手册\n\n![架构图](images/图1.png)\n")
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
    void rewrittenUrlCarriesRootPathAndHitsAssetEndpoint() {
        // 1. download 重写：引用必须带 /hisilicon-knowledge 前缀。
        // 注：@QuarkusTest 在 root-path 已配置时会自动为该测试服务器请求 base 加根路径前缀，
        // 因此请求路径写 /kb/... 即可（框架拼成 /hisilicon-knowledge/kb/...），切勿再手拼 root-path。
        String body = given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .header("Accept-Language", "zh-CN")
                .body("{\"documentId\":\"" + DOC_ID + "\"}")
                .when().post("/kb/documents/download")
                .then().statusCode(200)
                .contentType(containsString("text/markdown"))
                .extract().asString();

        String rewritten = ROOT_PATH + "/kb/documents/" + DOC_ID + "/assets?path=";
        assertTrue(body.contains(rewritten),
                "重写后的 URL 必须携带 root-path(" + ROOT_PATH + ") 前缀: " + body);
        assertTrue(body.contains(urlEncode("kb-1/docs/images/图1.png")), "path 参数应为存储相对路径: " + body);
        assertTrue(body.contains("&expires=") && body.contains("&sig="), "应含过期与签名: " + body);

        // 2. 从响应解析 path/expires/sig，用带根路径的完整 URL 打资产接口 → 200 + image/png
        Matcher m = Pattern.compile(Pattern.quote(rewritten)
                + "([^&]*)&expires=(\\d+)&sig=([0-9a-f]+)").matcher(body);
        assertTrue(m.find(), "响应应含可解析的资产 URL: " + body);
        String path = urlDecode(m.group(1));
        String expires = m.group(2);
        String sig = m.group(3);

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

    private static DocumentEntity mdDocument(String filePath) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId("kb-1")
                .title("手册")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
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