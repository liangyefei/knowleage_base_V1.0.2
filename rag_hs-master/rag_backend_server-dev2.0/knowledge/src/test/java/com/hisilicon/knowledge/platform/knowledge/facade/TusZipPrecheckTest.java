package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DraftConflictPolicy;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatch;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportBatchStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSession;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionPurpose;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.UploadSessionStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ImportBatchRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.UploadSessionRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * tus 端点契约测试（zip 前置预检 + 会话归属校验）
 * <p>profile 将 {@code kb.import.tus.zip-max-depth} 调小为 2、临时存储指向运行临时目录。
 * 覆盖：</p>
 * <ol>
 *   <li>深层级 zip 在最后一个分片同步报错（409+A0705 文案+Upload-Offset 纠偏头），会话置 FAILED；
 *       重复 PATCH → 409（终态透传）</li>
 *   <li>非创建人 PATCH/HEAD/DELETE 越权 → A0301；创建人正常续传</li>
 *   <li>合规 zip 全链路 204 完成（预检不误伤）</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(TusZipPrecheckTest.PrecheckProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TusZipPrecheckTest {

    private static final String TUS = "/kb/documents/tus";
    private static final String OWNER = "admin";
    private static final String ATTACKER = "mallory";
    private static final String KB_ID = "kb-tus-precheck";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    ImportBatchRepository importBatchRepository;

    @Inject
    UploadSessionRepository uploadSessionRepository;

    /** 测试期临时存储根（LocalFileStorageService 的 local-path，配置覆盖注入）。
     * 注意：profile 构建 getConfigOverrides 时（app ClassLoader）与测试运行时（另一加载器）
     * 会各自执行本类静态初始化——目录名必须确定，双侧才能收敛到同一物理目录。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return java.nio.file.Files.createDirectories(
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "tus-precheck-storage"));
        } catch (IOException e) {
            throw new IllegalStateException("创建测试存储目录失败", e);
        }
    }

    public static class PrecheckProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "kb.import.tus.zip-max-depth", "2",
                    "kb.storage.local-path", STORAGE_ROOT.toString()
            );
        }
    }

    @org.junit.jupiter.api.BeforeAll
    static void cleanStorageBeforeAll() {
        // 确定目录会跨次运行残留：类开始前整树清空，保证用例间隔离
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
            // 清理失败无害（系统临时目录）
        }
    }

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockImportBatchRepository(), importBatchRepository);
        QuarkusMock.installMockForInstance(new MockUploadSessionRepository(), uploadSessionRepository);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentMetadataRepository.reset();
        MockUploadSessionRepository.reset();
        MockImportBatchRepository.put(presetBatch());
    }

    // ========== 场景 1：深层级 zip 最后一片同步拦截 ==========

    @Test
    @Order(1)
    void shouldRejectDeepNestingZipAtLastPatchAndMarkFailed() throws Exception {
        // a/b/c/file.txt 层级 3 > 上限 2
        byte[] deepZip = zipOf(Map.of("a/b/c/report.txt", "v1 content"));

        String sessionId = createZipSession("deep.zip", deepZip);

        // 整包一片上传到最后 → 同步拦截：协议化 409（非 204 让 tus 客户端终止重试）+ 业务码 A0705
        // + 具体超限文案 + Upload-Offset=全长纠偏头
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(deepZip)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(409)
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", equalTo(String.valueOf(deepZip.length)))
                .body("code", equalTo("A0705"))
                .body("message", containsString("层级"));

        // 会话已终态 FAILED（批次进度可见），无后台解压
        assertEquals(UploadSessionStatus.FAILED, MockUploadSessionRepository.get(sessionId).getStatus());

        // 重发任何分片 → 409 + A0706（会话终态失败专用码）+ 失败原因原样透出
        // （不再是笼统的"参数不正确"），同时不可再写、不向终态会话追加字节
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(deepZip)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(409)
                .body("code", equalTo("A0706"))
                .body("message", containsString("层级"));
    }

    // ========== 场景 2：会话归属校验 ==========

    @Test
    @Order(2)
    void shouldRejectForeignOperatorButAllowOwner() throws Exception {
        byte[] payload = zipOf(Map.of("readme.txt", "shallow ok"));
        String sessionId = createZipSession("ok.zip", payload);
        int half = payload.length / 2;

        // 创建人传前半片 → 正常推进 offset
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, 0, half))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(half)));

        // 非创建人续传后半片 → A0301
        given()
                .header("Authorization", "Bearer " + token(ATTACKER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", String.valueOf(half))
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, half, payload.length))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"));

        // 非创建人 HEAD 不泄露断点信息（HTTP 规范下 HEAD 无实体，越权只能靠 offset 头缺席判定）
        given()
                .header("Authorization", "Bearer " + token(ATTACKER))
                .header("Tus-Resumable", "1.0.0")
                .when().head(TUS + "/" + sessionId)
                .then()
                .statusCode(200)
                .header("Upload-Offset", org.hamcrest.Matchers.nullValue());
        given()
                .header("Authorization", "Bearer " + token(ATTACKER))
                .header("Tus-Resumable", "1.0.0")
                .when().delete(TUS + "/" + sessionId)
                .then()
                .statusCode(200)
                .body("code", equalTo("A0301"));

        // 创建人 HEAD 正常返回断点信息
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .when().head(TUS + "/" + sessionId)
                .then()
                .statusCode(200)
                .header("Tus-Resumable", equalTo("1.0.0"))
                .header("Upload-Offset", equalTo(String.valueOf(half)))
                .header("Upload-Length", equalTo(String.valueOf(payload.length)));
    }

    // ========== 场景 3：合规 zip 预检不误伤（放最后，complete 触发 fire-and-forget 后台解压） ==========    @Test
    @Order(3)
    void shouldAcceptCompliantZipThroughFullFlow() throws Exception {
        byte[] payload = zipOf(Map.of(
                "docs/readme.md", "# shallow",
                "license.txt", "MIT"));
        String sessionId = createZipSession("fine.zip", payload);

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(payload)
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(payload.length)));

        // zip 会话进入 PROCESSING（预检通过转后台解压）
        assertEquals(UploadSessionStatus.PROCESSING,
                MockUploadSessionRepository.get(sessionId).getStatus());
    }

    // ========== 场景 4：PROCESSING 会话重发分片 → 明确提示（直注会话，避免后台解压竞态） ==========

    @Test
    @Order(4)
    void shouldRejectRepPatchWhileProcessingWithExplicitReason() {
        UploadSession processing = UploadSession.create(new UploadSession.Seed(
                UploadSessionPurpose.BATCH_IMPORT, "batch-precheck", null, KB_ID, null, "bg.zip", "bg", "application/zip",
                100L, DraftConflictPolicy.OVERRIDE, null, OWNER,
                java.time.LocalDateTime.now().plusHours(1))).markProcessing();
        MockImportBatchRepository.put(presetBatch());
        new MockUploadSessionRepository().persist(processing);
        String sessionId = processing.getId().value();

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(new byte[]{1})
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(409)
                .body("code", equalTo("A0435"))
                .body("message", containsString("后台解压入库"));
    }

    // ========== 场景 5：共享存储属性缓存滞后 → 按已提交断点自愈（非 zip，单文件链路） ==========

    @Test
    @Order(5)
    void shouldHealResumeAtCommittedOffsetWhenAttrCacheStale() throws Exception {
        byte[] payload = "0123456789".repeat(64).getBytes(StandardCharsets.UTF_8); // 640B
        String sessionId = createZipSession("notes.txt", payload);
        int half = payload.length / 2;
        int staleGap = 100;

        // 正常推进半片：fs 长度 == session.uploadOffset == half（两者已一致提交）
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, 0, half))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(half)));

        // 模拟 NFS 属性缓存陈旧视图：本 pod stat 到的文件比真实已提交内容短 staleGap
        // （真实场景短的是另一副本刚 append 的数据；此处物理截短以让 Files.size 返回旧值）
        Path temp = STORAGE_ROOT.resolve(".uploads").resolve(sessionId);
        Files.write(temp, copyOf(payload, 0, half - staleGap));

        // 客户端按自己收到的 ACK 断点（= 已提交 dbOffset）续传余下全部
        // → 命中双源仲裁自愈分支，不再 409
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", String.valueOf(half))
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, half, payload.length))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204)
                .header("Upload-Offset", equalTo(String.valueOf(payload.length)));

        assertEquals(UploadSessionStatus.COMPLETED,
                MockUploadSessionRepository.get(sessionId).getStatus());
    }

    // ========== 场景 6：真冲突不变式 —— 声明偏移既非 fs.size 也非已提交断点 → 409+纠偏偏移头 ==========

    @Test
    @Order(6)
    void shouldRejectRealConflictWithCorrectionHeader() throws Exception {
        byte[] payload = "abcdef".repeat(128).getBytes(StandardCharsets.UTF_8);
        String sessionId = createZipSession("plain.txt", payload);
        int half = payload.length / 2;

        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, 0, half))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(204);

        // 声明 half-123：既不等于 fs.size（half）也不等于 dbOffset（half）→ 真冲突
        given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", String.valueOf(half - 123))
                .contentType("application/offset+octet-stream")
                .body(copyOf(payload, half - 123, payload.length))
                .when().patch(TUS + "/" + sessionId)
                .then()
                .statusCode(409)
                .header("Upload-Offset", equalTo(String.valueOf(half)))   // 服务端纠偏偏移
                .body("code", equalTo("A0435"))
                .body("message", containsString("断点不一致"));
    }

    // ========== 辅助 ==========

    /** 以 OWNER 身份创建 zip 上传会话，返回 sessionId */
    private String createZipSession(String fileName, byte[] content) {
        String location = given()
                .header("Authorization", "Bearer " + token(OWNER))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", content.length)
                .header("Upload-Metadata",
                        "filename " + b64(fileName)
                                + ",batchId " + b64("batch-precheck")
                                + ",metadata " + b64("{\"version\":\"1\"}"))
                .when().post(TUS)
                .then()
                .statusCode(201)
                .extract().header("Location");
        assertNotNull(location, "create 应返回 Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static byte[] copyOf(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zipOf(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static ImportBatch presetBatch() {
        return ImportBatch.builder()
                .id(ImportBatchId.of("batch-precheck"))
                .kbId(KB_ID)
                .categoryId(null)
                .batchName("tus 预检契约测试批次")
                .totalFiles(0)
                .totalSize(0)
                .status(ImportBatchStatus.UPLOADING)
                .defaultMetadata(null)
                .draftConflictPolicy(DraftConflictPolicy.OVERRIDE)
                .createdBy(OWNER)
                .build();
    }

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("tus预检测试库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .build();
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
