package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.service.FileRecycleService;
import com.hisilicon.knowledge.platform.knowledge.facade.MockFileStorageService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * tus 版本覆盖 move 前路径唯一化单测：目标路径被磁盘现存文件或 DB 归档路径
 * （documents.file_path / document_versions.file_path）占用时跳号 {@code {base}_{n}.{ext}}，
 * 保证 move(REPLACE_EXISTING) 不覆盖归档旧版物理文件（数据损坏级缺陷回归防护）。
 * 仓储用进程内假实现直喂构造器——被测方法不触真实 DB/FS。
 */
class DocumentCommandServiceUniqueMovePathTest {

    private static final String DESIRED = "kb/产品库/report.pdf";

    private FakeDocumentRepository docRepo;
    private MockFileStorageService storage;
    private DocumentStoragePathResolver svc;

    @BeforeEach
    void setUp() {
        docRepo = new FakeDocumentRepository();
        storage = new MockFileStorageService();
        svc = new DocumentStoragePathResolver(null, null, docRepo, storage);
    }

    @AfterEach
    void tearDown() {
        MockFileStorageService.reset();
    }

    /** 路径完全空闲 → 原样返回（不改名） */
    @Test
    void shouldReturnDesiredPathWhenFree() {
        String actual = svc.uniqueStoragePathForMove(publishedDoc(), DESIRED).await().indefinitely();
        assertEquals(DESIRED, actual);
    }

    /** 磁盘已存在同名文件（覆盖场景常态：旧版物理文件在场）→ 跳号 _1 */
    @Test
    void shouldSkipWhenDiskOccupied() {
        MockFileStorageService.setExistingPaths(List.of(DESIRED));
        String actual = svc.uniqueStoragePathForMove(publishedDoc(), DESIRED).await().indefinitely();
        assertEquals("kb/产品库/report_1.pdf", actual);
    }

    /** DB documents.file_path 占用（磁盘无文件）→ 同样跳号，防 DB 事实被 move 篡改 */
    @Test
    void shouldSkipWhenDocumentsPathOccupied() {
        docRepo.setPathOwners(Map.of(DESIRED, "doc-other"));
        String actual = svc.uniqueStoragePathForMove(publishedDoc(), DESIRED).await().indefinitely();
        assertEquals("kb/产品库/report_1.pdf", actual);
    }

    /** 目标与 _1 均被占用（历史 _1 与用户真实文件名冲突）→ 跳到 _2 */
    @Test
    void shouldSkipMultipleOccupiedCandidates() {
        MockFileStorageService.setExistingPaths(List.of(DESIRED));
        docRepo.setPathOwners(Map.of("kb/产品库/report_1.pdf", "doc-ver1"));
        String actual = svc.uniqueStoragePathForMove(publishedDoc(), DESIRED).await().indefinitely();
        assertEquals("kb/产品库/report_2.pdf", actual);
    }

    // ======================== 候选派生规则 ========================

    @Test
    void deriveShouldCoverBaseAndNumberedSuffixes() {
        List<String> candidates = DocumentStoragePathResolver.deriveCandidatePaths("kb/dir/file.pdf", 2);
        assertEquals(List.of("kb/dir/file.pdf", "kb/dir/file_1.pdf", "kb/dir/file_2.pdf"), candidates);
    }

    @Test
    void deriveShouldHandleNoParentAndNoExtension() {
        assertEquals(List.of("file", "file_1"),
                DocumentStoragePathResolver.deriveCandidatePaths("file", 1));
        assertEquals(List.of("kb/a", "kb/a_1"),
                DocumentStoragePathResolver.deriveCandidatePaths("kb/a", 1));
    }

    // ======================== 基建 ========================

    private static DocumentEntity publishedDoc() {
        LocalDateTime now = LocalDateTime.now();
        return DocumentEntity.builder()
                .id(DocumentId.of("doc-1"))
                .kbId("kb-1")
                .categoryId(null)
                .title("report")
                .status(DocumentEntity.STATUS_PUBLISHED)
                .processStatus(DocumentEntity.PROCESS_COMPLETED)
                .currentVersion("1")
                .publishedVersion("1")
                .filePath(DESIRED)
                .fileSize(10L)
                .mimeType("application/pdf")
                .createdBy("admin")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** 进程内假文档仓储：只实现 findIdsByFilePaths（唯一化触达点） */
    static class FakeDocumentRepository implements DocumentRepository {
        private volatile Map<String, String> pathOwners = Map.of();

        void setPathOwners(Map<String, String> pathOwners) {
            this.pathOwners = pathOwners;
        }

        @Override
        public Uni<Map<String, String>> findIdsByFilePaths(Collection<String> filePaths) {
            Map<String, String> hits = new java.util.HashMap<>();
            for (String p : filePaths) {
                String owner = pathOwners.get(p);
                if (owner != null) {
                    hits.put(p, owner);
                }
            }
            return Uni.createFrom().item(hits);
        }

        @Override
        public Uni<DocumentEntity> findById(DocumentId id) { return Uni.createFrom().nullItem(); }

        @Override
        public Uni<DocumentEntity> persist(DocumentEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<DocumentEntity> update(DocumentEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<DocumentEntity>> findReviewingByKbId(String kbId) { return Uni.createFrom().item(List.of()); }

        @Override
        public Uni<List<DocumentEntity>> findReviewingByCategoryIds(String kbId, Collection<String> categoryIds) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<DocumentEntity>> findByIds(Collection<String> documentIds) { return Uni.createFrom().item(List.of()); }

        @Override
        public Uni<List<DocumentEntity>> findDraftByKbId(String kbId) { return Uni.createFrom().item(List.of()); }

        @Override
        public Uni<List<DocumentEntity>> findDraftByCategoryIds(String kbId, Collection<String> categoryIds) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Long> countByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds,
                                                       Collection<String> statuses) {
            return Uni.createFrom().item(0L);
        }

        @Override
        public Uni<Long> countByKbIdAndStatusIn(String kbId, Collection<String> statuses) {
            return Uni.createFrom().item(0L);
        }

        @Override
        public Uni<List<DocumentEntity>> findByCategoryIdsAndStatusIn(String kbId, Collection<String> categoryIds,
                                                                      Collection<String> statuses) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<DocumentEntity>> findByKbIdAndStatusIn(String kbId, Collection<String> statuses) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<String>> findVersionFilePaths(String documentId) { return Uni.createFrom().item(List.of()); }

        @Override
        public Uni<List<String>> findVersionFilePathsByDocumentIds(Collection<String> documentIds) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Void> deleteAllByIds(Collection<String> documentIds, Collection<String> statuses) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> archiveVersion(DocumentVersion version) { return Uni.createFrom().voidItem(); }

        @Override
        public Uni<Void> delete(DocumentId id) { return Uni.createFrom().voidItem(); }

        @Override
        public Uni<DocumentVersion> findVersion(String documentId, String versionNumber) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Void> markProcessing(DocumentId id) { return Uni.createFrom().voidItem(); }
    }
}
