package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentMetadataRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 替换文件孤儿回收端点测试（DRAFT 同名覆盖 / 编辑换文件的旧文件守卫回收）
 * <p>旧文件在 DB 更新成功后经 {@code FileRecycleService.recycleGuarded} 移入回收站（两阶段），
 * 守卫：旧路径==新路径不回收、仍被 document_versions 引用不回收、仍被其他 documents 行引用不回收。
 * <p>本类经 {@link MockFileStorageService} 断言回收交接（moveToTrash 调用）；物理落盘行为由
 * {@code DocumentCommandServiceReplaceFileRecycleTest}（真实 LocalFileStorageService + 临时目录）覆盖。</p>
 */
@QuarkusTest
class DocumentResourceReplaceFileRecycleTest {

    private static final String KB_ID = "kb-recycle-1";
    private static final String KB_NAME = "回收测试库";
    /** 同名覆盖的解析落盘路径（kb/{KB名}/{清洗后文件名}，根目录 categoryId=null） */
    private static final String NEW_PATH = "kb/" + KB_NAME + "/guide.pdf";
    private static final String OLD_PATH = "kb/" + KB_NAME + "/guide-v0.9.pdf";

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    DocumentRepository documentRepository;

    @Inject
    DocumentMetadataRepository documentMetadataRepository;

    @Inject
    DocumentOutboxRepository outboxRepository;

    @Inject
    DictValueValidator dictValueValidator;

    @Inject
    com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService fileStorageService;

    @BeforeEach
    void installMocks() {
        QuarkusMock.installMockForInstance(new MockKnowledgeBaseRepository(), knowledgeBaseRepository);
        QuarkusMock.installMockForInstance(new MockCategoryRepository(), categoryRepository);
        QuarkusMock.installMockForInstance(new MockDocumentRepository(), documentRepository);
        QuarkusMock.installMockForInstance(new MockDocumentMetadataRepository(), documentMetadataRepository);
        QuarkusMock.installMockForInstance(new MockDocumentOutboxRepository(), outboxRepository);
        QuarkusMock.installMockForInstance(new MockDictValueValidator(), dictValueValidator);
        QuarkusMock.installMockForInstance(new MockFileStorageService(), fileStorageService);

        MockKnowledgeBaseRepository.setEntityById(activeKb());
        MockCategoryRepository.setEntityById(null);
        MockDocumentRepository.setDuplicateDocs(null);
        MockDocumentRepository.setEntityById(null);
        MockDocumentRepository.setVersionFilePaths(null);
        MockDocumentRepository.setIdsByFilePaths(null);
        MockDocumentRepository.resetUpdatedEntity();
        MockDocumentMetadataRepository.reset();
        MockFileStorageService.reset();
    }

    // ======================== DRAFT 同名覆盖 ========================

    /** DRAFT 同名覆盖：旧物理文件在 DB 更新成功后移入回收站（两阶段语义，非物理删除） */
    @Test
    void shouldTrashOldFileOnDraftOverride() {
        MockDocumentRepository.setDuplicateDocs(java.util.List.of(draftDoc(OLD_PATH)));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        assertEquals(OLD_PATH, MockFileStorageService.getLastTrashPath(), "旧物理文件应移入回收站");
        assertEquals(NEW_PATH, MockFileStorageService.getLastStorePath(), "新文件应落盘到解析路径");
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated, "文档应已更新");
        assertEquals(NEW_PATH, updated.getFilePath(), "filePath 应指向新文件");
    }

    // ======================== 守卫一：旧路径 == 新路径 ========================

    /** filePath 未变化（同路径同名覆盖）→ 无孤儿，不触发回收 */
    @Test
    void shouldNotTrashWhenOldPathEqualsNewPath() {
        MockDocumentRepository.setDuplicateDocs(java.util.List.of(draftDoc(NEW_PATH)));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        assertNull(MockFileStorageService.getLastTrashPath(), "旧路径==新路径时不应触发回收");
    }

    // ======================== 守卫二：旧路径仍被 document_versions 引用 ========================

    /** 旧路径仍在历史版本表（版本覆盖/发布后打回草稿）→ 不回收，保证归档版本可下载 */
    @Test
    void shouldNotTrashWhenOldPathReferencedByVersion() {
        MockDocumentRepository.setDuplicateDocs(java.util.List.of(draftDoc(OLD_PATH)));
        MockDocumentRepository.setVersionFilePaths(java.util.List.of(OLD_PATH));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        assertNull(MockFileStorageService.getLastTrashPath(), "旧路径被历史版本引用时不应回收");
        DocumentEntity updated = MockDocumentRepository.getUpdatedEntity();
        assertNotNull(updated);
        assertEquals(NEW_PATH, updated.getFilePath(), "业务结果不受守卫影响");
    }

    // ======================== 守卫三：旧路径仍被其他 documents 行引用 ========================

    /** 旧路径仍被其他文档行引用（同名清洗碰撞等极端场景）→ 不回收，防误删他文档文件 */
    @Test
    void shouldNotTrashWhenOldPathReferencedByOtherDocument() {
        MockDocumentRepository.setDuplicateDocs(java.util.List.of(draftDoc(OLD_PATH)));
        MockDocumentRepository.setIdsByFilePaths(Map.of(OLD_PATH, "doc-other"));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8))
                .multiPart("kbId", KB_ID)
                .multiPart("metadata", "{\"version\":\"2\"}")
                .when().post("/kb/documents/upload")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"))
                .body("data.results[0].status", equalTo("SUCCESS"));

        assertNull(MockFileStorageService.getLastTrashPath(), "旧路径被其他文档引用时不应回收");
    }

    // ======================== 守卫：编辑不带文件不触发回收 ========================

    /** 编辑不带文件（仅属性/元数据）→ 不进入替换路径，不触发任何回收 */
    @Test
    void shouldNotTrashOnEditWithoutFile() {
        MockDocumentRepository.setEntityById(draftDoc(OLD_PATH));

        given()
                .header("Authorization", "Bearer " + token())
                .contentType(ContentType.MULTIPART)
                .multiPart("documentId", "doc-dup")
                .multiPart("metadata", "{\"author\":\"reviewer2\"}")
                .when().post("/kb/documents/edit")
                .then()
                .statusCode(200)
                .body("code", equalTo("0"));

        assertNull(MockFileStorageService.getLastTrashPath(), "编辑不带文件不应触发回收");
        assertNotNull(MockDocumentRepository.getUpdatedEntity(), "属性编辑应正常落库（与回收无关）");
    }

    // ======================== 辅助方法 ========================

    /** DRAFT 状态重复文档（同名检测命中 → 走 overrideDraftFile 覆盖路径） */
    private static DocumentEntity draftDoc(String filePath) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-dup"))
                .kbId(KB_ID)
                .categoryId(null)
                .title("guide")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .hasPublishedVersion(false)
                .filePath(filePath)
                .fileSize(1024L)
                .mimeType("application/pdf")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 构建最小知识库实体（活跃） */
    private static KnowledgeBaseEntity activeKb() {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name(KB_NAME)
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .createdBy("admin")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static String token() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".dummy-signature";
    }
}
