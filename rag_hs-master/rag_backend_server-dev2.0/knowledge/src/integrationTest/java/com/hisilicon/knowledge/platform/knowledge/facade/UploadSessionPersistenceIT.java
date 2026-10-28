package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ImportFileMetadata;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.ImportBatchPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.UploadSessionPO;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 集成测试：import_batches / upload_sessions 的 jsonb 列（{@code default_metadata} / {@code metadata}）
 * 在真实 PostgreSQL 上的往返。验证 {@code @JdbcTypeCode(SqlTypes.JSON)} 对 {@link ImportFileMetadata}
 * record 的序列化/反序列化（含中文 UTF-8、List 字段、null 列字段）。
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --tests "*UploadSessionPersistenceIT*" --rerun-tasks}</p>
 * <p>这是设计 §5 待验证项 1（jsonb 映射）；通过即 JVM 模式 jsonb 映射正确，native 仍需单独冒烟。</p>
 */
@QuarkusTest
class UploadSessionPersistenceIT {

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void clean() {
        sessionFactory.withTransaction(s -> s
                        .createQuery("delete from UploadSessionPO").executeUpdate()
                        .chain(() -> s.createQuery("delete from ImportBatchPO").executeUpdate()))
                .await().indefinitely();
    }

    // ======================== 批次 default_metadata jsonb 往返 ========================

    @Test
    void batchDefaultMetadataRoundTrips() {
        ImportFileMetadata meta = new ImportFileMetadata(
                mapOf("chip_model", "CV620", "sensitivity", "内部"),
                "v1", "alice", List.of("tag1", "tag2"), "初始化灌库备注", null);
        ImportBatchPO po = batchPo("tus-it-batch-1", "kb-it", "测试批次", meta, "OVERRIDE");
        persist(po);

        ImportBatchPO loaded = find(ImportBatchPO.class, po.getId());

        assertNotNull(loaded);
        assertEquals("kb-it", loaded.getKbId());
        assertEquals("测试批次", loaded.getBatchName());
        assertEquals("OVERRIDE", loaded.getDraftConflictPolicy());
        assertEquals("UPLOADING", loaded.getStatus());
        assertEquals(0, loaded.getTotalFiles());
        assertEquals(0L, loaded.getTotalSize());
        ImportFileMetadata dm = loaded.getDefaultMetadata();
        assertNotNull(dm, "default_metadata jsonb 反序列化为 null");
        assertEquals("CV620", dm.documentMetadata().get("chip_model"));
        assertEquals("内部", dm.documentMetadata().get("sensitivity"));
        assertEquals("v1", dm.version());
        assertEquals("alice", dm.author());
        assertEquals(List.of("tag1", "tag2"), dm.tags());
        assertEquals("初始化灌库备注", dm.remark());
    }

    @Test
    void batchDefaultMetadataNullFieldsRoundTrip() {
        // version 必填（业务校验），但 PO 层允许 null；列字段 author/tags/remark 为 null 时 jsonb 须正确存取
        ImportFileMetadata meta = new ImportFileMetadata(
                mapOf("product", "Hi1711"), "v2", null, null, null, null);
        ImportBatchPO po = batchPo("tus-it-batch-2", "kb-it", "b2", meta, "SKIP");
        persist(po);

        ImportBatchPO loaded = find(ImportBatchPO.class, po.getId());

        ImportFileMetadata dm = loaded.getDefaultMetadata();
        assertNotNull(dm);
        assertEquals("Hi1711", dm.documentMetadata().get("product"));
        assertEquals("v2", dm.version());
        // 纯 record 无默认值；null 列字段经 jsonb 往返仍为 null（验证 Jackson null 序列化一致性）
        assertNull(dm.author());
        assertNull(dm.tags());
        assertNull(dm.remark());
    }

    // ======================== 会话 metadata jsonb 往返 ========================

    @Test
    void sessionMetadataRoundTrips() {
        // 先建批次（会话外键仅 batch_id 字符串，无 DB 外键约束，但语义上挂批次）
        ImportBatchPO batch = batchPo("tus-it-batch-3", "kb-it", "b3",
                new ImportFileMetadata(mapOf(), "v1", null, null, null, null), "OVERRIDE");
        persist(batch);

        ImportFileMetadata meta = new ImportFileMetadata(
                mapOf("chip_model", "CV720", "sensitivity", "公开"),
                "v1", "bob", List.of("寄存器"), "续传测试", null);
        UploadSessionPO po = sessionPo(new SessionPoSeed("tus-it-sess-1", batch.getId(), "kb-it", "doc.pdf", "application/pdf", 2048L, meta, "OVERRIDE", "CREATED"));
        persist(po);

        UploadSessionPO loaded = find(UploadSessionPO.class, po.getId());

        assertNotNull(loaded);
        assertEquals(batch.getId(), loaded.getBatchId());
        assertEquals("doc.pdf", loaded.getFileName());
        assertEquals("application/pdf", loaded.getMimeType());
        assertEquals(2048L, loaded.getUploadLength());
        assertEquals(0L, loaded.getUploadOffset());
        assertEquals("OVERRIDE", loaded.getDraftPolicy());
        assertEquals("CREATED", loaded.getStatus());
        assertEquals(".uploads/tus-it-sess-1", loaded.getTempPath());
        assertNotNull(loaded.getExpiresAt());
        ImportFileMetadata m = loaded.getMetadata();
        assertNotNull(m, "metadata jsonb 反序列化为 null");
        assertEquals("CV720", m.documentMetadata().get("chip_model"));
        assertEquals("公开", m.documentMetadata().get("sensitivity"));
        assertEquals("v1", m.version());
        assertEquals("bob", m.author());
        assertEquals(List.of("寄存器"), m.tags());
        assertEquals("续传测试", m.remark());
    }

    // ======================== PROCESSING 状态 + entriesDone 进度往返 ========================

    @Test
    void sessionProcessingEntriesDoneRoundTrips() {
        // zip 异步解压中：status=PROCESSING，entriesDone=42（逐条目累加，见 §4.6.1）
        ImportBatchPO batch = batchPo("tus-it-batch-4", "kb-it", "b4",
                new ImportFileMetadata(mapOf(), "v1", null, null, null, null), "OVERRIDE");
        persist(batch);

        UploadSessionPO po = sessionPo(new SessionPoSeed("tus-it-sess-2", batch.getId(), "kb-it", "pkg.zip", "application/zip", 5L * 1024 * 1024 * 1024, null, "OVERRIDE", "PROCESSING"));
        po.setEntriesDone(42);
        persist(po);

        UploadSessionPO loaded = find(UploadSessionPO.class, po.getId());

        assertNotNull(loaded);
        assertEquals("PROCESSING", loaded.getStatus(), "PROCESSING 须可往返（isTerminal 排除它，complete 不复入）");
        assertEquals(42, loaded.getEntriesDone(), "entries_done int4 列须往返");
        // 超大包 uploadLength（5GB）须在 bigint 列正确存取（max-file-size=10G 放行）
        assertEquals(5L * 1024 * 1024 * 1024, loaded.getUploadLength());
    }

    // ======================== doc 用途会话（purpose / target_document_id / nullable batch_id） ========================

    @Test
    void sessionDocPurposeRoundTrips() {
        // DOC_EDIT 会话：batch_id=NULL（migration_013 置列可空）、purpose/target_document_id 往返
        UploadSessionPO po = sessionPo(new SessionPoSeed("tus-it-sess-doc-1", null, "kb-it", "new.pdf", "application/pdf", 2048L, null, "OVERRIDE", "CREATED"));
        po.setPurpose("DOC_EDIT");
        po.setTargetDocumentId("doc-it-1");
        po.setMetadata(new ImportFileMetadata(mapOf("product", "Hi1711"), null, "编辑作者", null, null, "新标题"));
        persist(po);

        UploadSessionPO loaded = find(UploadSessionPO.class, po.getId());

        assertNotNull(loaded, "batch_id 为 NULL 的 doc 会话应可持久化（列可空）");
        assertNull(loaded.getBatchId());
        assertEquals("DOC_EDIT", loaded.getPurpose());
        assertEquals("doc-it-1", loaded.getTargetDocumentId());
        // title 组件（仅 doc 用途消费）经 jsonb 往返保留
        assertEquals("新标题", loaded.getMetadata().title());
        assertEquals("编辑作者", loaded.getMetadata().author());
    }

    @Test
    void sessionBatchPurposeDefaultsRoundTrip() {
        // batch 会话：purpose 显式 BATCH_IMPORT（应用层 mapper 恒显式写值——Hibernate 不走 DB 列默认）、
        // target_document_id 为 NULL
        ImportBatchPO batch = batchPo("tus-it-batch-6", "kb-it", "b6",
                new ImportFileMetadata(mapOf(), "v1", null, null, null, null), "OVERRIDE");
        persist(batch);

        UploadSessionPO po = sessionPo(new SessionPoSeed("tus-it-sess-6", batch.getId(), "kb-it", "doc.pdf", "application/pdf", 100L, null, "OVERRIDE", "UPLOADING"));
        persist(po);

        UploadSessionPO loaded = find(UploadSessionPO.class, po.getId());

        assertEquals("BATCH_IMPORT", loaded.getPurpose());
        assertNull(loaded.getTargetDocumentId());
        assertEquals(batch.getId(), loaded.getBatchId());
    }

    // ======================== 辅助 ========================

    private void persist(Object... pos) {
        sessionFactory.withTransaction(s -> {
            Uni<Void> uni = Uni.createFrom().voidItem();
            for (Object po : pos) {
                uni = uni.chain(() -> s.persist(po));
            }
            return uni.chain(s::flush);
        }).await().indefinitely();
    }

    private <T> T find(Class<T> clazz, String id) {
        // 独立会话读取，强制走 DB（绕过同一持久化上下文一级缓存）
        return sessionFactory.withTransaction(s -> s.find(clazz, id)).await().indefinitely();
    }

    private static ImportBatchPO batchPo(String id, String kbId, String name,
                                         ImportFileMetadata defaultMetadata, String policy) {
        ImportBatchPO po = new ImportBatchPO();
        po.setId(id);
        po.setKbId(kbId);
        po.setBatchName(name);
        po.setTotalFiles(0);
        po.setTotalSize(0L);
        po.setStatus("UPLOADING");
        po.setDefaultMetadata(defaultMetadata);
        po.setDraftConflictPolicy(policy);
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 会话种子（参数对象：>5 参工厂收口，G.MET.01） */
    private record SessionPoSeed(String id, String batchId, String kbId, String fileName, String mimeType,
                                 long length, ImportFileMetadata metadata, String policy, String status) {
    }

    private static UploadSessionPO sessionPo(SessionPoSeed s) {
        UploadSessionPO po = new UploadSessionPO();
        po.setId(s.id());
        po.setBatchId(s.batchId());
        // 应用层（UploadSessionMapper）恒显式写 purpose：batch=BATCH_IMPORT / doc=DOC_EDIT|VERSION_OVERRIDE
        po.setPurpose("BATCH_IMPORT");
        po.setKbId(s.kbId());
        po.setFileName(s.fileName());
        po.setFileTitle("doc");
        po.setMimeType(s.mimeType());
        po.setUploadLength(s.length());
        po.setUploadOffset(0L);
        po.setDraftPolicy(s.policy());
        po.setMetadata(s.metadata());
        po.setStatus(s.status());
        po.setTempPath(".uploads/" + s.id());
        po.setExpiresAt(LocalDateTime.now().plusHours(168));
        po.setCreatedBy("admin");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
