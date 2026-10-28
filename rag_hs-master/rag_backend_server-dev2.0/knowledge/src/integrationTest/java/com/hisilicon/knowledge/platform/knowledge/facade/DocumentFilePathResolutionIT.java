package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentVersionPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：{@code findIdsByFilePaths} 两段解析（真实 PostgreSQL）。
 *
 * <p>背景（版本覆盖问题 2）：覆盖后 {@code documents.file_path} 已更新为新路径，旧版路径仅在
 * {@code document_versions.file_path}。修复前仅查 documents 表 → 问答链路按旧路径解析不到
 * documentId → download 失败。修复后：documents 优先，未命中路径回退 document_versions，
 * 且兜底段仅解析「未归档、未删除」（排除 ARCHIVED/DEPRECATED）文档。
 * 运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class DocumentFilePathResolutionIT {

    private static final String NEW_PATH = "kb-path/kb-version.pdf";
    private static final String OLD_PATH = "kb-path/kb-version_v1.pdf";

    @Inject
    DocumentRepository documentRepository;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from DocumentMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> session.persist(kb("kb-path")))
                        .chain(() -> session.persist(doc("doc-alive", "kb-path", "PUBLISHED", NEW_PATH, "2")))
                        .chain(() -> persistVersions(session,
                                version("doc-alive", "1", OLD_PATH))))
                .await().indefinitely();
    }

    /** 当前路径命中 documents（主路径，行为不变） */
    @Test
    void shouldResolveCurrentPathFromDocuments() {
        Map<String, String> result = resolve(NEW_PATH);
        assertEquals(Map.of(NEW_PATH, "doc-alive"), result);
    }

    /** 旧版本路径：documents 未命中、document_versions 命中 → 解析到同一文档（修复点） */
    @Test
    void shouldResolveHistoricalPathViaVersionsFallback() {
        Map<String, String> result = resolve(OLD_PATH);
        assertEquals(Map.of(OLD_PATH, "doc-alive"), result);
    }

    /** 当前路径 + 旧路径混合批量：两段各命中一次，documents 优先不被兜底覆盖 */
    @Test
    void shouldResolveMixedPathsWithDocumentsPriority() {
        Map<String, String> result = resolve(List.of(NEW_PATH, OLD_PATH, "kb-path/none.pdf"));
        assertEquals(2, result.size());
        assertEquals("doc-alive", result.get(NEW_PATH));
        assertEquals("doc-alive", result.get(OLD_PATH));
        assertFalse(result.containsKey("kb-path/none.pdf"), "未命中路径不出现在映射");
    }

    /** 归档（ARCHIVED）文档：旧版本路径不得解析（用户约束：仅解析到未归档、未删除文档） */
    @Test
    void shouldNotResolveHistoricalPathOfArchivedDocument() {
        sessionFactory.withTransaction(session -> session
                        .persist(doc("doc-archived", "kb-path", "ARCHIVED", "kb-path/archived-v2.pdf", "2"))
                        .chain(() -> persistVersions(session,
                                version("doc-archived", "1", "kb-path/archived-v1.pdf"))))
                .await().indefinitely();

        Map<String, String> result = resolve("kb-path/archived-v1.pdf");
        assertFalse(result.containsKey("kb-path/archived-v1.pdf"), "ARCHIVED 文档不可经旧版本路径解析");
    }

    /** 已废弃（DEPRECATED，回收站语义）文档：旧版本路径不得解析 */
    @Test
    void shouldNotResolveHistoricalPathOfDeprecatedDocument() {
        sessionFactory.withTransaction(session -> session
                        .persist(doc("doc-deprecated", "kb-path", "DEPRECATED", "kb-path/deprecated-v2.pdf", "2"))
                        .chain(() -> persistVersions(session,
                                version("doc-deprecated", "1", "kb-path/deprecated-v1.pdf"))))
                .await().indefinitely();

        Map<String, String> result = resolve("kb-path/deprecated-v1.pdf");
        assertFalse(result.containsKey("kb-path/deprecated-v1.pdf"), "DEPRECATED 文档不可经旧版本路径解析");
    }

    /** 旧路径当前被其它文档的 documents.file_path 占用（路径复用）：documents 优先 */
    @Test
    void documentsMatchWinsOverVersionsFallback() {
        sessionFactory.withTransaction(session -> session
                        .persist(doc("doc-other", "kb-path", "PUBLISHED", OLD_PATH, "1")))
                .await().indefinitely();

        Map<String, String> result = resolve(OLD_PATH);
        assertEquals("doc-other", result.get(OLD_PATH), "documents 当前路径命中优先于版本兜底");
    }

    /** 空入参 → 空映射（防御） */
    @Test
    void shouldReturnEmptyMapForEmptyInput() {
        assertTrue(resolve(List.of()).isEmpty());
    }

    /** refs 解析（携带版本号）：当前路径命中 → version=current_version */
    @Test
    void shouldResolveRefsWithCurrentVersion() {
        Map<String, DocumentRepository.PathRef> refs = resolveRefs(NEW_PATH);
        // 当前路径命中：versionStatus 镜像文档生命周期（doc-alive=PUBLISHED）
        assertEquals(new DocumentRepository.PathRef("doc-alive", "2", DocumentVersion.STATUS_PUBLISHED),
                refs.get(NEW_PATH));
    }

    /** refs 解析（携带版本号）：旧版本路径兜底命中 → version=version_number（前端按对应版本下载） */
    @Test
    void shouldResolveRefsWithHistoricalVersion() {
        Map<String, DocumentRepository.PathRef> refs = resolveRefs(OLD_PATH);
        assertEquals(new DocumentRepository.PathRef("doc-alive", "1", DocumentVersion.STATUS_PUBLISHED),
                refs.get(OLD_PATH));
    }

    // ======================== 辅助 ========================

    private Map<String, String> resolve(String... paths) {
        return resolve(List.of(paths));
    }

    private Map<String, String> resolve(List<String> paths) {
        // 经 withSession 建立 Vertx/会话上下文（reactive Panache 需要，同 KnowledgeBaseChatResolverIT）
        return sessionFactory.withSession(s -> documentRepository.findIdsByFilePaths(paths))
                .await().indefinitely();
    }

    private Map<String, DocumentRepository.PathRef> resolveRefs(String... paths) {
        return sessionFactory.withSession(s -> documentRepository.findRefsByFilePaths(List.of(paths)))
                .await().indefinitely();
    }

    private static Uni<Void> persistVersions(Mutiny.Session session, DocumentVersionPO... versions) {
        Uni<Void> uni = Uni.createFrom().voidItem();
        for (DocumentVersionPO v : versions) {
            uni = uni.chain(() -> session.persist(v));
        }
        return uni;
    }

    private static KnowledgeBasePO kb(String id) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName("路径解析测试库");
        po.setStatus((short) 1);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static DocumentPO doc(String id, String kbId, String status, String filePath, String version) {
        DocumentPO po = new DocumentPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setCategoryId(null);
        po.setTitle(filePath.substring(filePath.lastIndexOf('/') + 1));
        po.setStatus(status);
        po.setProcessStatus("COMPLETED");
        po.setCurrentVersion(version);
        po.setHasPublishedVersion(true);
        po.setFilePath(filePath);
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setDocType("DOCUMENT");
        po.setCreatedBy("admin");
        po.setInheritParent(true);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static DocumentVersionPO version(String documentId, String versionNumber, String filePath) {
        DocumentVersionPO po = new DocumentVersionPO();
        po.setDocumentId(documentId);
        po.setVersionNumber(versionNumber);
        po.setFilePath(filePath);
        po.setFileSize(1024L);
        po.setMimeType("application/pdf");
        po.setWasPublished(Boolean.TRUE);
        po.setStatus(DocumentVersion.STATUS_PUBLISHED);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        return po;
    }
}
