package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictMetaKeyCacheService;
import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tus 文档用途会话契约测试（DOC_EDIT / VERSION_OVERRIDE，编辑换文件与版本覆盖断点续传）
 * <p>profile 将 {@code kb.upload.max-file-size} 调小为 1M（业务单文件上限，与 multipart edit/versions
 * 同源）并将临时存储指向运行临时目录。覆盖：</p>
 * <ol>
 *   <li>create 校验 fail-closed：目标文档缺失键 422；文档不存在 A0430；非 DRAFT/PUBLISHED A0508；
 *       同类型强校验 A0701；zip 显式拒绝；版本号必填 A0420；超限 A0705（kb.upload.max-file-size）</li>
 *   <li>DOC_EDIT 全链路：分片 PATCH → HEAD 断点 → 末片 complete → 会话 COMPLETED + 文档换文件
 *       （processStatus→PENDING、元数据替换、outbox 旧周期作废、临时文件 move 入位）</li>
 *   <li>complete 时文档状态已变更 → 永久性拒绝：409 + A0508 + 纠偏头，会话 FAILED、临时文件清理</li>
 *   <li>VERSION_OVERRIDE 全链路：PUBLISHED → 旧版归档 document_versions → 文档 DRAFT + currentVersion 更新</li>
 *   <li>断点发现端点：本人可见活跃会话，他人不可见</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(TusDocumentUploadTest.DocUploadProfile.class)
class TusDocumentUploadTest {

    private static final String TUS = "/kb/documents/tus";
    private static final String OWNER = "admin";
    private static final String ATTACKER = "mallory";
    private static final String KB_ID = "kb-tus-doc";
    /** KB 名称（目录段即用此名，见 activeKb()；同名编辑用例按它拼目标路径） */
    private static final String KB_NAME = "tus-doc测试库";
    private static final String DOC_ID = "doc-tus-doc-1";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    DictValueValidator dictValueValidator;

    @Inject
    DictMetaKeyCacheService dictMetaKeyCacheService;

    @Inject
    UploadSessionRepository uploadSessionRepository;

    /** 测试期临时存储根（profile 构建与运行时双侧收敛到同一物理目录，见 TusZipPrecheckTest 同款注释） */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createDirectories(
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "tus-doc-storage"));
        } catch (IOException e) {
            throw new IllegalStateException("创建测试存储目录失败", e);
        }
    }

    public static class DocUploadProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "kb.upload.max-file-size", "1M",
                    "kb.storage.local-path", STORAGE_ROOT.toString()
            );
        }
    }

    @BeforeAll
    static void cleanStorageBeforeAll() {
        try (var walk = Files.walk(STORAGE_ROOT)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            Files.createDirectories(STORAGE_ROOT);
        } catch (IOException ignored) {
            // 清理失败无害（系统临时目录）
        }
    }

    @AfterAll
    static void cleanupStorage() {
        try (var walk = Files.walk(STORAGE_ROOT)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 清理失败无害
        }
    }

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        QuarkusMock.installMockForInstance(new MockDictMetaKeyCacheService(), dictMetaKeyCacheService);
        QuarkusMock.installMockForInstance(new MockUploadSessionRepository(), uploadSessionRepository);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockDocumentMetadataRepository.reset();
        MockDocumentOutboxRepository.reset();
        MockDictValueValidator.reset();
        MockDictMetaKeyCacheService.reset();
        MockUploadSessionRepository.reset();
        MockDocumentRepository.setEntityById(draftDoc());
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentRepository.setVersionById(null);
        MockDocumentRepository.resetArchivedVersion();
        // findIdsByFilePaths 占用表（唯一化触达点）：默认无占用，防用例间串扰
        MockDocumentRepository.setIdsByFilePaths(null);
        // 白名单须在 reset 之后预设（reset 置 null=空集，fail-closed）
        MockDictMetaKeyCacheService.setPresetKeys(Set.of("product", "chip_model", "sensitivity", "official"));
    }

    // ========== create 校验（fail-closed） ==========

    @Test
    void shouldRejectCreateMissingTargetDocumentId() {
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", 100)
                .header("Upload-Metadata", "filename " + b64("new.pdf") + ",purpose " + b64("docEdit"))
                .when().post(TUS)
                .then()
                .statusCode(422);
    }

    @Test
    void shouldRejectCreateUnknownPurpose() {
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", 100)
                .header("Upload-Metadata", "filename " + b64("new.pdf")
                        + ",purpose " + b64("hackMode") + ",targetDocumentId " + b64(DOC_ID))
                .when().post(TUS)
                .then()
                .statusCode(422);
    }

    @Test
    void shouldFailCreateDocEditWhenDocumentNotFound() {
        MockDocumentRepository.setEntityById(null);

        createDocSessionAndFail("docEdit", DOC_ID, "{\"title\":\"t\"}", 200, "A0430", null);
    }

    @Test
    void shouldFailCreateDocEditWhenReviewing() {
        MockDocumentRepository.setEntityById(docWithStatus(DocumentEntity.STATUS_REVIEWING));

        createDocSessionAndFail("docEdit", DOC_ID, "{\"title\":\"t\"}", 200, "A0508", null);
    }

    @Test
    void shouldFailCreateDocEditWhenPublished() {
        MockDocumentRepository.setEntityById(docWithStatus(DocumentEntity.STATUS_PUBLISHED));

        createDocSessionAndFail("docEdit", DOC_ID, "{\"title\":\"t\"}", 200, "A0508",
                containsString("版本覆盖"));
    }

    @Test
    void shouldFailCreateDocEditWhenTypeMismatch() {
        // 源文档 draft-doc.pdf，新文件 report.txt → 同类型强校验拒绝
        createDocSessionAndFail("docEdit", DOC_ID, null, 200, "A0701", containsString("类型"));
    }

    @Test
    void shouldFailCreateDocPurposeWhenZip() {
        createDocSessionAndFail("docEdit", DOC_ID, null, 200, "A0701", containsString("zip"), "archive.zip");
    }

    @Test
    void shouldFailCreateVersionOverrideWhenVersionMissing() {
        MockDocumentRepository.setEntityById(publishedDoc("2"));

        createDocSessionAndFail("versionOverride", DOC_ID, null, 200, "A0420", null);
    }

    @Test
    void shouldFailCreateVersionOverrideWhenNotPublished() {
        createDocSessionAndFail("versionOverride", DOC_ID, "{\"version\":\"3\"}", 200, "A0508", null);
    }

    @Test
    void shouldFailCreateWhenSizeOverDocUploadLimit() {
        // profile 将 kb.upload.max-file-size 调小为 1M：2M 载荷 → A0705（与 multipart edit/versions 同源上限）
        byte[] big = new byte[2 * 1024 * 1024];

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", big.length)
                .header("Upload-Metadata", "filename " + b64("new.pdf")
                        + ",purpose " + b64("docEdit") + ",targetDocumentId " + b64(DOC_ID))
                .when().post(TUS)
                .then()
                .statusCode(200)
                .body("code", equalTo("A0705"));
    }

    // ========== create 成功：会话归属与目标文档正确落库 ==========

    @Test
    void shouldCreateDocEditSessionWithTargetDocument() {
        String sessionId = createDocSession("docEdit", DOC_ID, "{\"title\":\"新标题\",\"product\":\"P1\"}",
                "new.pdf", PAYLOAD);

        UploadSession session = MockUploadSessionRepository.get(sessionId);
        assertNotNull(session);
        assertEquals(UploadSessionPurpose.DOC_EDIT, session.getPurpose());
        assertEquals(DOC_ID, session.getTargetDocumentId());
        assertNull(session.getBatchId(), "doc 用途会话不隶属批次");
        assertEquals(KB_ID, session.getKbId());
        assertEquals(UploadSessionStatus.CREATED, session.getStatus());
        assertEquals(PAYLOAD.length, session.getUploadLength());
    }

    // ========== DOC_EDIT 全链路（分片 → HEAD 断点 → complete 换文件） ==========

    @Test
    void shouldCompleteDocEditThroughFullFlow() throws Exception {
        String sessionId = createDocSession("docEdit", DOC_ID, "{\"title\":\"新标题\",\"product\":\"P1\"}",
                "new.pdf", PAYLOAD);
        int half = PAYLOAD.length / 2;

        // 前半片
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(copyOf(PAYLOAD, 0, half))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(half)));

        // 断点续传：HEAD 查 offset
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .when().head(TUS + "/" + sessionId)
                .then()
                .statusCode(200)
                .header("Upload-Offset", equalTo(String.valueOf(half)));

        // 末片 → complete 同步落库（PATCH 等到落库完）
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", String.valueOf(half))
                .contentType("application/offset+octet-stream")
                .body(copyOf(PAYLOAD, half, PAYLOAD.length))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(PAYLOAD.length)));

        // 会话终态 + 结果文档 ID
        UploadSession session = MockUploadSessionRepository.get(sessionId);
        assertEquals(UploadSessionStatus.COMPLETED, session.getStatus());
        assertEquals(DOC_ID, session.getDocumentId());

        // 文档换文件：DRAFT 保持、processStatus→PENDING、title 更新、新路径落实体
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "文档应被更新");
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus());
        assertEquals(DocumentEntity.PROCESS_PENDING, updated.getProcessStatus());
        assertEquals("新标题", updated.getTitle());
        assertNotNull(updated.getFilePath());
        assertTrue(Files.exists(STORAGE_ROOT.resolve(updated.getFilePath())),
                "临时文件应 move 入位: " + updated.getFilePath());
        assertFalse(Files.exists(STORAGE_ROOT.resolve(session.getTempPath())), "临时文件不应残留");

        // dict 元数据全量替换（product=P1；version 不在编辑链路入 map）
        Map<String, String> meta = MockDocumentMetadataRepository.getLastMetadata();
        assertNotNull(meta);
        assertEquals("P1", meta.get("product"));
        assertFalse(meta.containsKey("version"));

        // 旧内容周期 outbox 事件作废（与 multipart edit 一致）
        assertEquals(DOC_ID, MockDocumentOutboxRepository.getCancelledDocumentId());
    }

    // ========== complete 时状态已变更 → 永久性拒绝（409 协议化 + 会话 FAILED） ==========

    @Test
    void shouldFailCompleteDocEditWhenDocumentTurnedReviewing() {
        String sessionId = createDocSession("docEdit", DOC_ID, null, "new.pdf", PAYLOAD);

        // 上传期间文档被提交审核
        MockDocumentRepository.setEntityById(docWithStatus(DocumentEntity.STATUS_REVIEWING));

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(PAYLOAD)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(409)
                .header("Upload-Offset", equalTo(String.valueOf(PAYLOAD.length)))
                .body("code", equalTo("A0508"));

        assertEquals(UploadSessionStatus.FAILED, MockUploadSessionRepository.get(sessionId).getStatus());
        assertFalse(Files.exists(STORAGE_ROOT.resolve(".uploads").resolve(sessionId)),
                "永久性拒绝后临时文件应清理");
    }

    // ========== VERSION_OVERRIDE 全链路 ==========

    @Test
    void shouldCompleteVersionOverrideThroughFullFlow() throws Exception {
        MockDocumentRepository.setEntityById(publishedDoc("2"));
        String sessionId = createDocSession("versionOverride", DOC_ID, "{\"version\":\"3\",\"author\":\"新作者\"}",
                "new.pdf", PAYLOAD);

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(PAYLOAD)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204);

        UploadSession session = MockUploadSessionRepository.get(sessionId);
        assertEquals(UploadSessionStatus.COMPLETED, session.getStatus());
        assertEquals(DOC_ID, session.getDocumentId());

        // 旧版归档 document_versions（was_published=TRUE，版本号=旧 currentVersion）
        var archived = MockDocumentRepository.getArchivedVersion();
        assertNotNull(archived, "旧版应归档");
        assertEquals("2", archived.versionNumber());
        assertTrue(archived.wasPublished());

        // 文档：status→DRAFT、currentVersion=3、processStatus→PENDING、author 入列
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(DocumentEntity.STATUS_DRAFT, updated.getStatus());
        assertEquals("3", updated.getCurrentVersion());
        assertEquals(DocumentEntity.PROCESS_PENDING, updated.getProcessStatus());
        assertEquals("新作者", updated.getAuthor());
        assertTrue(Files.exists(STORAGE_ROOT.resolve(updated.getFilePath())));

        // outbox 旧周期作废（与 multipart overrideVersion 一致）
        assertEquals(DOC_ID, MockDocumentOutboxRepository.getCancelledDocumentId());
    }

    // ========== DOC_EDIT 换同名文件：move 前路径唯一化（不物理覆盖在用文件） ==========

    @Test
    void shouldSkipNumberingWhenDocEditReplacesSameNameFile() throws Exception {
        // 目标路径（kb/{KB名}/draft-doc.pdf）已被磁盘在用文件 + DB 其他行占用：
        // 直接 move(REPLACE_EXISTING) 会物理覆盖在用文件（与版本覆盖覆盖缺陷同根），必须跳号 _1
        String sameNamePath = "kb/" + KB_NAME + "/draft-doc.pdf";
        Path occupied = STORAGE_ROOT.resolve(sameNamePath);
        Files.createDirectories(occupied.getParent());
        Files.write(occupied, "old-content".getBytes(StandardCharsets.UTF_8));
        MockDocumentRepository.setEntityById(sameNameDoc());
        MockDocumentRepository.setIdsByFilePaths(Map.of(sameNamePath, "doc-other"));

        String sessionId = createDocSession("docEdit", DOC_ID, null, "draft-doc.pdf", PAYLOAD);
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(PAYLOAD)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204);

        assertEquals(UploadSessionStatus.COMPLETED, MockUploadSessionRepository.get(sessionId).getStatus());
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated);
        // 跳号唯一化：DB 落 _1 路径
        assertEquals("kb/" + KB_NAME + "/draft-doc_1.pdf", updated.getFilePath());
        // 新文件物理落位 _1 路径，内容为新载荷
        Path newFile = STORAGE_ROOT.resolve(updated.getFilePath());
        assertTrue(Files.exists(newFile), "新文件应 move 到跳号路径");
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                Files.readString(newFile), "跳号路径内容应为新载荷");
        // 在用旧文件未被覆盖
        assertEquals("old-content", Files.readString(occupied), "在用文件内容不得被 move 覆盖");
    }

    // ========== 断点发现端点 ==========

    @Test
    void shouldFindActiveSessionForOwnerOnly() {
        String sessionId = createDocSession("docEdit", DOC_ID, null, "new.pdf", PAYLOAD);

        // 本人：返回活跃会话（sessionId/purpose/offset）
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .when().get("/kb/documents/" + DOC_ID + "/upload-session")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.sessionId", equalTo(sessionId))
                .body("data.purpose", equalTo("DOC_EDIT"))
                .body("data.uploadOffset", equalTo(0))
                .body("data.uploadLength", equalTo(PAYLOAD.length));

        // 他人：不可见（data=null）
        given()
                .header("Authorization", "Bearer " + token(ATTACKER))
                .when().get("/kb/documents/" + DOC_ID + "/upload-session")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data", org.hamcrest.Matchers.nullValue());
    }

    // ========== 辅助 ==========

    private static final byte[] PAYLOAD = "0123456789".repeat(50).getBytes(StandardCharsets.UTF_8); // 500B

    /** 以 OWNER 身份创建 doc 用途会话，返回 sessionId（期望成功 201） */
    private String createDocSession(String purpose, String targetDocumentId,
                                    String metadataJson, String fileName, byte[] content) {
        String metadataHeader = "filename " + b64(fileName)
                + ",purpose " + b64(purpose)
                + ",targetDocumentId " + b64(targetDocumentId);
        if (metadataJson != null) {
            metadataHeader += ",metadata " + b64(metadataJson);
        }
        String location = given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", content.length)
                .header("Upload-Metadata", metadataHeader)
                .when().post(TUS)
                .then()
                .statusCode(201)
                .extract().header("Location");
        assertNotNull(location, "create 应返回 Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** 创建 doc 用途会话并断言业务失败（HTTP 200 envelope + 业务码 + 可选文案片段） */
    private void createDocSessionAndFail(String purpose, String targetDocumentId, String metadataJson,
                                         int statusCode, String code, org.hamcrest.Matcher<String> messageMatcher) {
        createDocSessionAndFail(purpose, targetDocumentId, metadataJson, statusCode, code, messageMatcher, "report.txt");
    }

    private void createDocSessionAndFail(String purpose, String targetDocumentId, String metadataJson,
                                         int statusCode, String code, org.hamcrest.Matcher<String> messageMatcher,
                                         String fileName) {
        String metadataHeader = "filename " + b64(fileName)
                + ",purpose " + b64(purpose)
                + ",targetDocumentId " + b64(targetDocumentId);
        if (metadataJson != null) {
            metadataHeader += ",metadata " + b64(metadataJson);
        }
        var assertion = given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", 100)
                .header("Upload-Metadata", metadataHeader)
                .when().post(TUS)
                .then()
                .statusCode(statusCode)
                .body("code", equalTo(code));
        if (messageMatcher != null) {
            assertion.body("message", messageMatcher);
        }
    }

    private static DocumentEntity draftDoc() {
        return docBase()
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .filePath(KB_ID + "/draft-doc.pdf")
                .currentVersion("1")
                .build();
    }

    /** 同名编辑用例：filePath == 目标解析路径（kb/{KB名}/draft-doc.pdf），模拟同名换文件常态 */
    private static DocumentEntity sameNameDoc() {
        return docBase()
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .filePath("kb/" + KB_NAME + "/draft-doc.pdf")
                .currentVersion("1")
                .build();
    }

    private static DocumentEntity publishedDoc(String version) {
        return docBase()
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion(version)
                .publishedVersion(version)
                .hasPublishedVersion(false)
                .filePath(KB_ID + "/old-version.pdf")
                .build();
    }

    private static DocumentEntity docWithStatus(String status) {
        return docBase()
                .status(status)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .filePath(KB_ID + "/draft-doc.pdf")
                .currentVersion("1")
                .build();
    }

    private static DocumentEntity.Builder docBase() {
        return DocumentEntity.builder()
                .id(DocumentId.of(DOC_ID))
                .kbId(KB_ID)
                .categoryId(null)
                .title("draft-doc")
                .fileSize(500L)
                .mimeType("application/pdf")
                .createdBy(OWNER)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name(KB_NAME)
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .build();
    }

    private static byte[] copyOf(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 与既有 facade 测试同构的 JWT 构造（生产验签链对 exp/sub 校验） */
    private static String token(String user) {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + user + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
