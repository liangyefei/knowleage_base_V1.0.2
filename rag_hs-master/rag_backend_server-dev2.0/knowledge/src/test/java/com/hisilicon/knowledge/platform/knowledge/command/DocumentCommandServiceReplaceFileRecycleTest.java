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
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.LocalFileStorageService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.UploadDocumentResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DocumentCommandService.FileInput} 简称 */
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService.FileInput;

/**
 * 替换文件孤儿回收（DRAFT 同名覆盖）真实文件系统测试。
 * <p>进程内假仓储直喂构造器（对齐 {@code DocumentStoragePathTest}）+ 真实
 * {@link LocalFileStorageService}（@TempDir 存储根）+ 真实 {@link FileRecycleService}，
 * 端到端验证两阶段回收：旧文件 rename 进 {@code .trash}（保留相对结构，非物理删除）、
 * 守卫（历史版本引用/他文档引用/同路径）不回收、回收失败仅告警不影响业务结果。</p>
 * <p>标注 {@code @QuarkusTest} 仅为取得 Qute 消息环境（upload 内 {@code LocaleUtil}），
 * 不依赖任何被测 Bean。</p>
 */
@QuarkusTest
class DocumentCommandServiceReplaceFileRecycleTest {

    private static final String KB_ID = "kb-recycle";
    private static final String KB_NAME = "回收库";
    private static final String OLD_PATH = "kb/" + KB_NAME + "/guide.pdf";

    @TempDir
    Path storageRoot;

    @Inject
    Vertx vertx;

    private FakeReplaceRecycleDocumentRepository docRepo;

    @BeforeEach
    void resetRepo() {
        docRepo = new FakeReplaceRecycleDocumentRepository();
    }

    // ======================== 回收主路径 ========================

    /** DRAFT 同名覆盖：旧物理文件移入 .trash（保留相对结构），新文件落盘新路径，DB 指向新路径 */
    @Test
    void shouldMoveOldFileToTrashOnDraftOverride() throws IOException {
        // 旧文件已在盘（同名：解析路径与旧路径相同 → 真实 store 唯一化出新路径，旧路径成为孤儿）
        writeOldFile("old-content");
        docRepo.duplicate = List.of(draftDoc(OLD_PATH));

        UploadDocumentResponse response = newService(fileStorage())
                .upload(new DocumentImportService.UploadRequest(
                                "admin", KB_ID, null,
                                List.of(new FileInput("guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8), "application/pdf")),
                                versionOnly(), "zh-CN"))
                .await().indefinitely();

        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        DocumentEntity updated = docRepo.updated;
        assertNotNull(updated, "文档应已更新");
        assertNotEquals(OLD_PATH, updated.getFilePath(), "filePath 应指向唯一化后的新路径");
        assertTrue(Files.exists(storageRoot.resolve(updated.getFilePath())), "新文件应真实落盘");
        assertFalse(Files.exists(storageRoot.resolve(OLD_PATH)), "旧文件应离开原位置");
        assertTrue(Files.exists(storageRoot.resolve(".trash").resolve(OLD_PATH)),
                "旧文件应移入 .trash（保留相对结构，非物理删除）");
    }

    // ======================== 守卫：历史版本引用 ========================

    /** 旧路径仍在 document_versions（版本覆盖归档）→ 不回收，保证归档版本可下载 */
    @Test
    void shouldKeepOldFileWhenReferencedByVersion() throws IOException {
        writeOldFile("old-content");
        docRepo.duplicate = List.of(draftDoc(OLD_PATH));
        docRepo.versionPaths = List.of(OLD_PATH);

        UploadDocumentResponse response = newService(fileStorage())
                .upload(new DocumentImportService.UploadRequest(
                                "admin", KB_ID, null,
                                List.of(new FileInput("guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8), "application/pdf")),
                                versionOnly(), "zh-CN"))
                .await().indefinitely();

        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        assertTrue(Files.exists(storageRoot.resolve(OLD_PATH)), "被版本引用的旧文件应留在原地");
        assertFalse(Files.exists(storageRoot.resolve(".trash").resolve(OLD_PATH)), "不应移入回收站");
    }

    // ======================== 守卫：他文档引用 ========================

    /** 旧路径仍被其他 documents 行引用（同名清洗碰撞等极端场景）→ 不回收，防误删 */
    @Test
    void shouldKeepOldFileWhenReferencedByOtherDocument() throws IOException {
        writeOldFile("old-content");
        docRepo.duplicate = List.of(draftDoc(OLD_PATH));
        docRepo.pathOwners = Map.of(OLD_PATH, "doc-other");

        UploadDocumentResponse response = newService(fileStorage())
                .upload(new DocumentImportService.UploadRequest(
                                "admin", KB_ID, null,
                                List.of(new FileInput("guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8), "application/pdf")),
                                versionOnly(), "zh-CN"))
                .await().indefinitely();

        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        assertTrue(Files.exists(storageRoot.resolve(OLD_PATH)), "被他文档引用的旧文件应留在原地");
        assertFalse(Files.exists(storageRoot.resolve(".trash").resolve(OLD_PATH)), "不应移入回收站");
    }

    // ======================== 回收失败不阻断业务 ========================

    /** moveToTrash 失败 → 仅告警（文件留原地由清理任务兜底），上传业务结果不受影响 */
    @Test
    void shouldKeepBusinessResultWhenRecycleFails() throws IOException {
        writeOldFile("old-content");
        docRepo.duplicate = List.of(draftDoc(OLD_PATH));
        LocalFileStorageService failingTrash = new LocalFileStorageService(storageRoot.toString(), vertx) {
            @Override
            public Uni<Void> moveToTrash(String path) {
                return Uni.createFrom().failure(new RuntimeException("模拟回收失败"));
            }
        };

        UploadDocumentResponse response = newService(failingTrash)
                .upload(new DocumentImportService.UploadRequest(
                                "admin", KB_ID, null,
                                List.of(new FileInput("guide.pdf", "new-content".getBytes(StandardCharsets.UTF_8), "application/pdf")),
                                versionOnly(), "zh-CN"))
                .await().indefinitely();

        assertEquals("SUCCESS", response.getResults().get(0).getStatus(), "回收失败不应影响上传业务结果");
        DocumentEntity updated = docRepo.updated;
        assertNotNull(updated);
        assertNotEquals(OLD_PATH, updated.getFilePath());
        assertTrue(Files.exists(storageRoot.resolve(OLD_PATH)), "回收失败的旧文件留在原地（可人工清理）");
    }

    // ======================== 基建 ========================

    /** 写入旧物理文件（含父目录） */
    private void writeOldFile(String content) throws IOException {
        Path old = storageRoot.resolve(OLD_PATH);
        Files.createDirectories(old.getParent());
        Files.writeString(old, content);
    }

    private static UploadDocumentCommand versionOnly() {
        return new UploadDocumentCommand(null, null, "1", null, null, null, null, null, null, null);
    }

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

    /** 手工装配：假仓储 + 真实存储/回收服务（被测方法不触 DB/Redis） */
    private DocumentImportService newService(LocalFileStorageService storage) {
        FakeReplaceRecycleKbRepository kbRepo = new FakeReplaceRecycleKbRepository();
        return new DocumentImportService(
                kbRepo, null, docRepo,
                null,
                storage, new FileRecycleService(storage), mockDict(),
                // 内容索引服务：测试流程不触 DB，仓储传 null（indexAsync 若触发，异常仅记日志不影响结果）
                new DocumentContentIndexService(storage, null, vertx, 100, 5),
                vertx, new DocumentStoragePathResolver(kbRepo, null, docRepo, storage),
                2000,
                MemorySize.of(BigInteger.valueOf(1024)), MemorySize.of(BigInteger.valueOf(1024)));
    }

    private static DictValueValidator mockDict() {
        return new MockDictValueValidator();
    }

    /** 真实本地存储（@TempDir 根） */
    private LocalFileStorageService fileStorage() {
        return new LocalFileStorageService(storageRoot.toString(), vertx);
    }

    /** 进程内假 KB 仓储：只实现上传流程会触达的方法 */
    static class FakeReplaceRecycleKbRepository implements KnowledgeBaseRepository {
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

    /** 进程内假文档仓储：同名检测/更新/回收守卫三查询可编程，其余安全默认 */
    static class FakeReplaceRecycleDocumentRepository implements DocumentRepository {
        List<DocumentEntity> duplicate = List.of();
        List<String> versionPaths = List.of();
        Map<String, String> pathOwners = Map.of();
        volatile DocumentEntity updated;

        @Override
        public Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) {
            return Uni.createFrom().item(duplicate);
        }

        @Override
        public Uni<DocumentEntity> update(DocumentEntity entity) {
            updated = entity;
            return Uni.createFrom().item(entity);
        }

        @Override
        public Uni<List<String>> findVersionFilePaths(String documentId) {
            return Uni.createFrom().item(versionPaths);
        }

        @Override
        public Uni<Map<String, String>> findIdsByFilePaths(java.util.Collection<String> filePaths) {
            return Uni.createFrom().item(pathOwners);
        }

        // ===== 以下不被上传/覆盖流程触达，安全默认 =====

        @Override public Uni<DocumentEntity> findById(DocumentId id) { return Uni.createFrom().nullItem(); }
        @Override public Uni<DocumentEntity> persist(DocumentEntity entity) { return Uni.createFrom().item(entity); }
        @Override public Uni<List<DocumentEntity>> findReviewingByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, java.util.Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByIds(java.util.Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, java.util.Collection<String> categoryIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Long> countByCategoryIdsAndStatusIn(String kbId, java.util.Collection<String> categoryIds, java.util.Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<Long> countByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses) { return Uni.createFrom().item(0L); }
        @Override public Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId, java.util.Collection<String> categoryIds, java.util.Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<List<String>> findVersionFilePathsByDocumentIds(java.util.Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Void> deleteAllByIds(java.util.Collection<String> documentIds, java.util.Collection<String> statuses) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> archiveVersion(com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion version) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> delete(DocumentId id) { return Uni.createFrom().voidItem(); }
        @Override public Uni<com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion> findVersion(String documentId, String versionNumber) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> markProcessing(DocumentId id) { return Uni.createFrom().voidItem(); }
    }
}
