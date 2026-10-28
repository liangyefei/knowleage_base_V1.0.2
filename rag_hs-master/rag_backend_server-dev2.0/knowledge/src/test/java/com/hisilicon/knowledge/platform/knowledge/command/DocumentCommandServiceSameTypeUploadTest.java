package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.service.DictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService;
import com.hisilicon.knowledge.platform.knowledge.facade.MockDictValueValidator;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.LocalFileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDocumentResponse;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DocumentCommandService.FileInput} 简称 */
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService.FileInput;

/**
 * 同名检测「同名 + 同扩展名」语义测试。
 * <p>同名不同扩展名（如 {@code guide.pdf} 已存在时上传 {@code guide.docx}）视为不同文档，
 * 走新建，不再误报"文档已存在"；同名同类型原冲突语义（PUBLISHED 跳过 /
 * REVIEWING 跳过 / DRAFT 覆盖）保持不变。</p>
 * <p>进程内假仓储直喂构造器（对齐 {@code DocumentCommandServiceReplaceFileRecycleTest}），
 * 标注 {@code @QuarkusTest} 仅为取得 Qute 消息环境。</p>
 */
@QuarkusTest
class DocumentCommandServiceSameTypeUploadTest {

    private static final String KB_ID = "kb-sametype";
    private static final String KB_NAME = "同名库";

    @TempDir
    Path storageRoot;

    @Inject
    Vertx vertx;

    private FakeSameTypeDocumentRepository docRepo;

    @BeforeEach
    void resetRepo() {
        docRepo = new FakeSameTypeDocumentRepository();
    }

    // ======================== 同名不同类型 → 新建 ========================

    /** PUBLISHED guide.pdf 已存在，上传 guide.docx → SUCCESS 新建，不再报"文档已存在" */
    @Test
    void shouldCreateNewWhenSameTitleDifferentExtensionPublished() {
        docRepo.duplicate = List.of(doc("guide", "kb/同名库/guide.pdf",
                DocumentEntity.STATUS_PUBLISHED));

        UploadDocumentResponse response = upload("guide.docx");

        assertSuccessNewDoc(response, "guide.docx");
    }

    /** DRAFT guide.pdf 已存在，上传 guide.docx → 新建 docx，且 pdf 草稿不被覆盖 */
    @Test
    void shouldCreateNewWhenSameTitleDifferentExtensionDraft() {
        docRepo.duplicate = List.of(doc("guide", "kb/同名库/guide.pdf",
                DocumentEntity.STATUS_DRAFT));

        UploadDocumentResponse response = upload("guide.docx");

        assertSuccessNewDoc(response, "guide.docx");
        assertNull(docRepo.updated, "不同类型不应覆盖既有 DRAFT 草稿");
    }

    // ======================== 同名同类型 → 原语义回归 ========================

    /** PUBLISHED guide.pdf 已存在，上传 guide.pdf → 照旧跳过"文档已存在" */
    @Test
    void shouldSkipWhenSameTitleSameExtensionPublished() {
        docRepo.duplicate = List.of(doc("guide", "kb/同名库/guide.pdf",
                DocumentEntity.STATUS_PUBLISHED));

        UploadDocumentResponse response = upload("guide.pdf");

        assertEquals("SKIPPED", response.getResults().get(0).getStatus());
        assertNull(docRepo.persisted, "同名同类型 PUBLISHED 命中不应新建");
    }

    /** REVIEWING guide.pdf 已存在，上传 guide.pdf → 照旧跳过（审核中） */
    @Test
    void shouldSkipWhenSameTitleSameExtensionReviewing() {
        docRepo.duplicate = List.of(doc("guide", "kb/同名库/guide.pdf",
                DocumentEntity.STATUS_REVIEWING));

        UploadDocumentResponse response = upload("guide.pdf");

        assertEquals("SKIPPED", response.getResults().get(0).getStatus());
        assertNull(docRepo.persisted);
    }

    /** DRAFT guide.pdf 已存在，上传 guide.pdf → 照旧覆盖草稿 */
    @Test
    void shouldOverrideDraftWhenSameTitleSameExtensionDraft() {
        docRepo.duplicate = List.of(doc("guide", "kb/同名库/guide.pdf",
                DocumentEntity.STATUS_DRAFT));

        UploadDocumentResponse response = upload("guide.pdf");

        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        assertNotNull(docRepo.updated, "同名同类型 DRAFT 命中应覆盖草稿");
        assertNull(docRepo.persisted, "覆盖路径不应新建文档");
    }

    /** DRAFT 同名覆盖后上传时间（createdAt）应更新为本次上传时间，而非保留首次创建时间 */
    @Test
    void shouldRefreshCreatedAtWhenOverridingDraft() {
        LocalDateTime oldCreatedAt = LocalDateTime.now().minusDays(1);
        DocumentEntity existing = DocumentEntity.builder()
                .id(DocumentId.of("doc-existing"))
                .kbId(KB_ID)
                .title("guide")
                .status(DocumentEntity.STATUS_DRAFT)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .filePath("kb/同名库/guide.pdf")
                .fileSize(1024L)
                .mimeType("application/pdf")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(oldCreatedAt)
                .updatedAt(oldCreatedAt)
                .build();
        docRepo.duplicate = List.of(existing);

        UploadDocumentResponse response = upload("guide.pdf");

        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        DocumentEntity updated = docRepo.updated;
        assertNotNull(updated, "同名同类型 DRAFT 命中应覆盖草稿");
        assertTrue(updated.getCreatedAt().isAfter(oldCreatedAt),
                "覆盖后上传时间应更新为本次上传时间: " + updated.getCreatedAt());
        assertEquals("editor", updated.getCreatedBy(), "覆盖不改变创建人归属");
    }

    // ======================== 基建 ========================

    private UploadDocumentResponse upload(String fileName) {
        return newService()
                .upload(new DocumentImportService.UploadRequest(
                                "admin", KB_ID, null,
                                List.of(new FileInput(fileName, "content".getBytes(StandardCharsets.UTF_8), "application/octet-stream")),
                                versionOnly(), "zh-CN"))
                .await().indefinitely();
    }

    private void assertSuccessNewDoc(UploadDocumentResponse response, String fileName) {
        assertEquals("SUCCESS", response.getResults().get(0).getStatus(),
                "同名不同类型应新建成功，而非跳过: " + response.getResults().get(0).getReason());
        DocumentEntity persisted = docRepo.persisted;
        assertNotNull(persisted, "应新建文档落库");
        assertTrue(persisted.getFilePath().endsWith(fileName),
                "新文档 filePath 应为新扩展名: " + persisted.getFilePath());
    }

    private static UploadDocumentCommand versionOnly() {
        return new UploadDocumentCommand(null, null, "1", null, null, null, null, null, null, null);
    }

    private static DocumentEntity doc(String title, String filePath, String status) {
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity.Builder builder = DocumentEntity.builder()
                .id(DocumentId.of("doc-existing"))
                .kbId(KB_ID)
                .categoryId(null)
                .title(title)
                .status(status)
                .processStatus(DocumentEntity.PROCESS_PENDING)
                .currentVersion("1")
                .filePath(filePath)
                .fileSize(1024L)
                .mimeType("application/pdf")
                .createdBy("editor")
                .inheritParent(true)
                .createdAt(now)
                .updatedAt(now);
        if (DocumentEntity.STATUS_PUBLISHED.equals(status)) {
            builder.publishedVersion("1").hasPublishedVersion(true);
        } else {
            builder.hasPublishedVersion(false);
        }
        return builder.build();
    }

    /** 手工装配：假仓储 + 真实存储服务（被测方法不触 DB/Redis） */
    private DocumentImportService newService() {
        LocalFileStorageService storage = new LocalFileStorageService(storageRoot.toString(), vertx);
        FakeSameTypeKbRepository kbRepo = new FakeSameTypeKbRepository();
        return new DocumentImportService(
                kbRepo, null, docRepo,
                null,
                storage, new FileRecycleService(storage), mockDict(),
                null, vertx, new DocumentStoragePathResolver(kbRepo, null, docRepo, storage),
                2000,
                MemorySize.of(BigInteger.valueOf(1024)), MemorySize.of(BigInteger.valueOf(1024)));
    }

    private static DictValueValidator mockDict() {
        return new MockDictValueValidator();
    }

    /** 进程内假 KB 仓储：只实现上传流程会触达的方法 */
    static class FakeSameTypeKbRepository implements KnowledgeBaseRepository {
        @Override
        public Uni<KnowledgeBaseEntity> findById(KnowledgeBaseId id) {
            return Uni.createFrom().item(KnowledgeBaseEntity.builder()
                    .id(id).name(KB_NAME).status(KnowledgeBaseEntity.STATUS_ACTIVE)
                    .createdBy("admin").createdAt(LocalDateTime.now()).build());
        }

        @Override
        public Uni<KnowledgeBaseEntity> findByIdForUpdate(KnowledgeBaseId id) { return findById(id); }

        @Override
        public Uni<KnowledgeBaseEntity> findByName(String name) { return Uni.createFrom().nullItem(); }

        @Override
        public Uni<KnowledgeBaseEntity> persist(KnowledgeBaseEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<KnowledgeBaseEntity> update(KnowledgeBaseEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<Void> markProcessing(KnowledgeBaseId id) { return Uni.createFrom().voidItem(); }
    }

    /** 进程内假文档仓储：同名检测/新建/更新可编程，其余安全默认 */
    static class FakeSameTypeDocumentRepository implements DocumentRepository {
        List<DocumentEntity> duplicate = List.of();
        volatile DocumentEntity persisted;
        volatile DocumentEntity updated;

        @Override
        public Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) {
            return Uni.createFrom().item(duplicate);
        }

        @Override
        public Uni<DocumentEntity> persist(DocumentEntity entity) {
            persisted = entity;
            return Uni.createFrom().item(entity);
        }

        @Override
        public Uni<DocumentEntity> update(DocumentEntity entity) {
            updated = entity;
            return Uni.createFrom().item(entity);
        }

        // ===== 以下不被上传流程触达，安全默认 =====

        @Override public Uni<DocumentEntity> findById(DocumentId id) { return Uni.createFrom().nullItem(); }
        @Override public Uni<List<DocumentEntity>> findReviewingByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByIds(Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Long> countByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds, Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<Long> countByKbIdAndStatusIn(String kbId, Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds, Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<String>> findVersionFilePaths(String documentId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<String>> findVersionFilePathsByDocumentIds(Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Map<String, String>> findIdsByFilePaths(Collection<String> filePaths) { return Uni.createFrom().item(Map.of()); }
        @Override public Uni<Void> deleteAllByIds(Collection<String> documentIds, Collection<String> statuses) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> archiveVersion(com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion version) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> delete(DocumentId id) { return Uni.createFrom().voidItem(); }
        @Override public Uni<com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion> findVersion(String documentId, String versionNumber) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> markProcessing(DocumentId id) { return Uni.createFrom().voidItem(); }
    }
}
