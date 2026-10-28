package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentMetadataRepositoryImpl;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 新上传端点测试：POST /kb/documents/upload
 * <p>验证元数据可空（省略/空串 → 空 Command + kbId 手工校验），以及 author/tags/document_metadata 写入。</p>
 */
@QuarkusTest
class DocumentResourceUploadTest {

    private static final String KB_ID = "kb-upload-2";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    DictValueValidator dictValueValidator;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockCategoryRepository.setEntityById(null);
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentMetadataRepository.reset();
        MockDictValueValidator.reset();
    }

    /** 仅传 version（无 author/tags）→ 上传成功，author/tags 为 null，currentVersion=version */
    @Test
    void shouldUploadWithVersionOnly() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        DocumentEntity persisted = MockDocumentRepository.getPersistedEntity();
        assertNotNull(persisted, "文档应已持久化");
        assertEquals("1", persisted.getCurrentVersion(), "currentVersion 应为元数据 version");
        assertEquals(null, persisted.getAuthor(), "未传 author 时应为 null");
        assertEquals(null, persisted.getTags(), "未传 tags 时应为 null");
    }

    /** 不传 kbId → Bean Validation 400（@NotBlank） */
    @Test
    void shouldFailWhenKbIdMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(400);
    }

    /** 空 metadata JSON（version 缺失）→ 版本号必填，拒绝 A0420 */
    @Test
    void shouldFailWhenVersionMissing() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", equalTo("版本号不能为空"));

        assertNull(MockDocumentRepository.getPersistedEntity(), "版本缺失时不应持久化文档");
    }

    /** 完整元数据 → author/tags 入文档列，product/chip_model/sensitivity 入 document_metadata */
    @Test
    void shouldPersistAuthorTagsAndMetadata() {
        // 注意：multipart 传输中文会乱码（项目已知坑），author 用 ASCII
        String metadataJson = "{\"author\":\"zhangsan\","
                + "\"tags\":[\"GPIO\",\"UART\"],\"product\":\"Mate 40\","
                + "\"chipModel\":\"CV620\",\"securityLevel\":\"INTERNAL\","
                + "\"version\":\"1.0\"}";

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "spec.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", metadataJson)
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        DocumentEntity persisted = MockDocumentRepository.getPersistedEntity();
        assertNotNull(persisted, "文档应已持久化");
        assertEquals("1.0", persisted.getCurrentVersion(), "currentVersion 应为元数据 version");
        assertEquals("zhangsan", persisted.getAuthor());
        assertEquals("GPIO,UART", persisted.getTags());

        assertEquals(persisted.getId().value(), MockDocumentMetadataRepository.getLastDocumentId(),
                "metadata 应写入对应文档");
        Map<String, String> meta = MockDocumentMetadataRepository.getLastMetadata();
        assertNotNull(meta);
        assertEquals("Mate 40", meta.get("product"));
        assertEquals("CV620", meta.get("chip_model"));
        assertEquals("INTERNAL", meta.get("sensitivity"));
    }

    /**
     * 前端以 GET /kb/metadata/dict-options 返回的 metaKey（= controlled_meta_keys /
     * 建议列表 typeCode）作为 metadata JSON 字段名提交：chip_model / sensitivity /
     * tag（单值字符串）。验证字段名映射 + tag 单值字符串→列表归一。
     * 复现线上报错：Unrecognized field "chip_model"。
     */
    @Test
    void shouldAcceptDictOptionMetaKeysAsJsonFields() {
        // 字段名与 dict-options 返回的 metaKey 完全一致（DB 层 meta_key，非 camelCase）
        String metadataJson = "{\"product\":\"1\",\"chip_model\":\"3\","
                + "\"version\":\"1\",\"sensitivity\":\"PUBLIC\","
                + "\"official\":\"OFFICIAL\",\"tag\":\"GPIO\"}";

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "spec.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", metadataJson)
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        DocumentEntity persisted = MockDocumentRepository.getPersistedEntity();
        assertNotNull(persisted, "文档应已持久化");
        // tag 单值字符串经 ACCEPT_SINGLE_VALUE_AS_ARRAY 归一为 ["GPIO"]，join 后写入 documents.tags
        assertEquals("GPIO", persisted.getTags());

        Map<String, String> meta = MockDocumentMetadataRepository.getLastMetadata();
        assertNotNull(meta);
        assertEquals("1", meta.get("product"));
        assertEquals("3", meta.get("chip_model"));
        assertEquals("PUBLIC", meta.get("sensitivity"));
        assertEquals("OFFICIAL", meta.get("official"));
        assertFalse(meta.containsKey("version"), "version 不入 document_metadata（由 documents.current_version 承载）");

        assertEquals("1", persisted.getCurrentVersion(), "currentVersion 应为元数据 version");
    }

    /** 仅传 version（无 dict 受控键）→ metaMap 为空，不触发 metadata 写入 */
    @Test
    void shouldSkipMetadataWriteWhenOnlyVersion() {
        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"1\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertEquals(null, MockDocumentMetadataRepository.getLastDocumentId(),
                "仅 version 时不应调用 replaceAll（version 不入 document_metadata）");
    }

    /**
     * 上传字典值校验（与编辑共用 validateOrThrow）：非法值在文件落盘前短路拒绝（A0420），
     * 不写文档、不写元数据。复现：上传 path 现已对齐编辑的字典值校验。
     */
    @Test
    void shouldRejectUploadWhenDictValueInvalid() {
        // MockDictValueValidator.validate 返回预设非法值 → validateOrThrow 经 errorFactory 抛 A0420
        MockDictValueValidator.setInvalidValue(
                new DictValueValidator.InvalidValue("sensitivity", "BOGUS"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "spec.pdf", "test-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"1\",\"sensitivity\":\"BOGUS\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("A0420"))
                .body("message", containsString("不在字典范围内"));

        assertNull(MockDocumentMetadataRepository.getLastDocumentId(),
                "字典值非法时不应写元数据");
    }

    // ======================== 辅助方法 ========================

    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("上传测试库2")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .build();
    }

    private static String token() {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }

    // ======================== Mock ========================

    static class MockDocumentMetadataRepository extends DocumentMetadataRepositoryImpl {
        private static volatile String lastDocumentId;
        private static volatile Map<String, String> lastMetadata;

        MockDocumentMetadataRepository() {
            super(null);
        }

        static void reset() {
            lastDocumentId = null;
            lastMetadata = null;
        }

        static String getLastDocumentId() { return lastDocumentId; }
        static Map<String, String> getLastMetadata() { return lastMetadata; }

        @Override
        public io.smallrye.mutiny.Uni<Void> replaceAll(String documentId, Map<String, String> metadata) {
            lastDocumentId = documentId;
            lastMetadata = metadata;
            return io.smallrye.mutiny.Uni.createFrom().voidItem();
        }
    }
}
