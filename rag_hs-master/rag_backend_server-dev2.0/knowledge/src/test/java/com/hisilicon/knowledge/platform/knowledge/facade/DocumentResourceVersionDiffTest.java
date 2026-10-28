package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DocumentVersionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.VersionDiffQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentVersionOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 版本对比接口测试（{@code GET /kb/documents/versions} + {@code POST /kb/documents/version-diff}）
 * <p>mock 查询服务（不触库）；diff 计算逻辑由 {@code VersionDiffQueryServiceComputeDiffTest} 单测覆盖。</p>
 */
@QuarkusTest
class DocumentResourceVersionDiffTest {

    private static final String OPERATOR = "admin";
    private static final String VALID_TOKEN = buildToken(OPERATOR, System.currentTimeMillis() / 1000 + 3600);

    @Inject
    DocumentVersionQueryService documentVersionQueryService;

    @Inject
    VersionDiffQueryService versionDiffQueryService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(
                new MockDocumentVersionQueryService(), documentVersionQueryService);
        QuarkusMock.installMockForInstance(
                new MockVersionDiffQueryService(), versionDiffQueryService);
        MockDocumentVersionQueryService.reset();
        MockVersionDiffQueryService.reset();
    }

    // ======================== GET /kb/documents/versions ========================

    @Test
    void shouldReturnVersionOptions() {
        MockDocumentVersionQueryService.setPreset(List.of(
                new DocumentVersionOption("V1.1", true, "PUBLISHED", null,
                        100L, "application/pdf", null, "admin", LocalDateTime.now()),
                new DocumentVersionOption("V1.0", false, "PUBLISHED", true,
                        90L, "application/pdf", "初版归档", "editor", LocalDateTime.now())));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("documentId", "doc-1")
                .when().get("/kb/documents/versions")
                .then().statusCode(200)
                .body("data", hasSize(2))
                .body("data[0].version", equalTo("V1.1"))
                .body("data[0].isCurrent", equalTo(true))
                .body("data[1].version", equalTo("V1.0"))
                .body("data[1].isCurrent", equalTo(false));

        assertEquals("doc-1", MockDocumentVersionQueryService.getLastDocumentId());
    }

    @Test
    void shouldFailVersionsWithoutDocumentId() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/kb/documents/versions")
                .then().statusCode(400);
    }

    @Test
    void shouldReturnNotFoundFromService() {
        MockDocumentVersionQueryService.setFailure(
                new com.hisilicon.knowledge.platform.knowledge.exception.DocumentNotFoundException("文档不存在"));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .queryParam("documentId", "doc-missing")
                .when().get("/kb/documents/versions")
                .then().statusCode(200)
                .body("code", equalTo("A0430"));
    }

    // ======================== POST /kb/documents/version-diff ========================

    @Test
    void shouldReturnDiffResult() {
        MockVersionDiffQueryService.setPreset(new VersionDiffResult("doc-1", "测试文档", true, null,
                new VersionDiffResult.DiffSideInfo("V1.0", 10, "READY"),
                new VersionDiffResult.DiffSideInfo("V1.1", 11, "READY"),
                new VersionDiffResult.DiffStats(1, 1, 1), false,
                List.of(new VersionDiffResult.DiffHunk("MODIFIED", List.of(
                        new VersionDiffResult.DiffLine("REMOVED", 1, null, "old"),
                        new VersionDiffResult.DiffLine("ADDED", null, 1, "new")))),
                List.of(new VersionDiffResult.DiffLine("REMOVED", 1, null, "old"),
                        new VersionDiffResult.DiffLine("ADDED", null, 1, "new")),
                "patch-text"));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"sourceVersion\":\"V1.0\",\"targetVersion\":\"V1.1\"}")
                .when().post("/kb/documents/version-diff")
                .then().statusCode(200)
                .body("data.diffSupported", equalTo(true))
                .body("data.stats.added", equalTo(1))
                .body("data.hunks", hasSize(1))
                .body("data.hunks[0].type", equalTo("MODIFIED"))
                .body("data.fullLines", hasSize(2))
                .body("data.fullLines[0].op", equalTo("REMOVED"))
                .body("data.fullLines[1].op", equalTo("ADDED"))
                .body("data.patch", equalTo("patch-text"));

        assertEquals("doc-1", MockVersionDiffQueryService.getLastDocumentId());
        assertEquals("V1.0", MockVersionDiffQueryService.getLastSourceVersion());
        assertEquals("V1.1", MockVersionDiffQueryService.getLastTargetVersion());
    }

    @Test
    void shouldReturnDiffResultWhenSameVersion() {
        // 单版本文档回显场景：sourceVersion == targetVersion 合法，返回该版本内容
        MockVersionDiffQueryService.setPreset(new VersionDiffResult("doc-1", "测试文档", true, null,
                new VersionDiffResult.DiffSideInfo("V1.0", 10, "READY"),
                new VersionDiffResult.DiffSideInfo("V1.0", 10, "READY"),
                new VersionDiffResult.DiffStats(0, 0, 0), false,
                List.of(), List.of(), ""));

        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"sourceVersion\":\"V1.0\",\"targetVersion\":\"V1.0\"}")
                .when().post("/kb/documents/version-diff")
                .then().statusCode(200)
                .body("data.diffSupported", equalTo(true))
                .body("data.stats.added", equalTo(0));

        assertEquals("doc-1", MockVersionDiffQueryService.getLastDocumentId());
        assertEquals("V1.0", MockVersionDiffQueryService.getLastSourceVersion());
        assertEquals("V1.0", MockVersionDiffQueryService.getLastTargetVersion());
    }

    @Test
    void shouldFailDiffWhenVersionBlank() {
        given()
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"documentId\":\"doc-1\",\"sourceVersion\":\"\",\"targetVersion\":\"V1.1\"}")
                .when().post("/kb/documents/version-diff")
                .then().statusCode(400);
    }

    // ======================== helpers ========================

    private static String buildToken(String sub, long exp) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
