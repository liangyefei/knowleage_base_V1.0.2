package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentRepositoryImpl;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link DocumentRepositoryImpl}
 * <p>通过静态字段控制查询/持久化行为。</p>
 */
public class MockDocumentRepository extends DocumentRepositoryImpl {

    /** null = 文档不存在，非 null = 文档已存在（返回该实体） */
    private static volatile DocumentEntity entityById;

    /** 记录最近一次 update 的实体，用于断言更新字段 */
    private static volatile DocumentEntity updatedEntity;

    /** findByKbIdAndTitle 返回的列表 */
    private static volatile java.util.List<DocumentEntity> duplicateDocs;

    /** 最近一次归档的版本 */
    private static volatile com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion archivedVersion;

    /** 记录最近一次 persist 的实体，用于断言入库字段 */
    private static volatile DocumentEntity persistedEntity;

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockDocumentRepository() {
        super(null, null, null);
    }

    public static void setEntityById(DocumentEntity entity) {
        entityById = entity;
    }

    public static void setDuplicateDocs(java.util.List<DocumentEntity> docs) {
        duplicateDocs = docs;
    }

    public static void resetArchivedVersion() {
        archivedVersion = null;
    }

    public static com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion getArchivedVersion() {
        return archivedVersion;
    }

    public static DocumentEntity getUpdatedEntity() {
        return updatedEntity;
    }

    public static DocumentEntity getPersistedEntity() {
        return persistedEntity;
    }

    public static void resetUpdatedEntity() {
        updatedEntity = null;
    }

    /**
     * 故障注入：接下来 N 次 findById 抛瞬时异常（模拟 DB 抖动/短暂不可用），每调用一次自减。
     * 用于验证消费端的<b>有界重试</b>；用完即自清零，避免静态状态泄漏到其他测试类。
     */
    private static volatile int findByIdFailures;
    private static volatile int findByIdCallCount;

    public static void failNextFindByIdCalls(int failures) {
        findByIdFailures = Math.max(0, failures);
        findByIdCallCount = 0;
    }

    public static void resetFailureInjection() {
        findByIdFailures = 0;
        findByIdCallCount = 0;
    }

    /** findById 被调用次数（断言重试次数用） */
    public static int getFindByIdCallCount() {
        return findByIdCallCount;
    }

    @Override
    public Uni<DocumentEntity> findById(DocumentId id) {
        findByIdCallCount++;
        if (findByIdFailures > 0) {
            findByIdFailures--;
            // IllegalStateException 不属于"不可恢复"（非 BusinessException/IllegalArgumentException），
            // 消费端会按瞬时故障重试
            return Uni.createFrom().failure(new IllegalStateException("模拟 DB 瞬时故障"));
        }
        DocumentEntity entity = entityById;
        if (entity == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<DocumentEntity> update(DocumentEntity entity) {
        updatedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findByKbIdAndTitle(String kbId, String categoryId, String title) {
        java.util.List<DocumentEntity> docs = duplicateDocs;
        if (docs == null) {
            return Uni.createFrom().item(java.util.List.of());
        }
        return Uni.createFrom().item(docs);
    }

    @Override
    public Uni<Void> archiveVersion(com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion version) {
        archivedVersion = version;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<DocumentEntity> persist(DocumentEntity entity) {
        persistedEntity = entity;
        return Uni.createFrom().item(entity);
    }

    /** 记录最近一次 delete 的文档 ID，用于断言级联删除 */
    private static volatile String deletedDocumentId;

    public static void resetDeletedDocumentId() {
        deletedDocumentId = null;
    }

    public static String getDeletedDocumentId() {
        return deletedDocumentId;
    }

    @Override
    public Uni<Void> delete(DocumentId id) {
        deletedDocumentId = id.value();
        return Uni.createFrom().voidItem();
    }

    /** 删除 documents 行 + document_metadata（保留版本历史）：no-op，不触父类 Panache 依赖 */
    @Override
    public Uni<Void> deleteDocumentKeepVersions(DocumentId id) {
        return Uni.createFrom().voidItem();
    }

    /** findVersion 返回的版本记录（null = 版本不存在） */
    private static volatile com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion versionById;

    public static void setVersionById(com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion version) {
        versionById = version;
    }

    @Override
    public Uni<com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion> findVersion(String documentId, String versionNumber) {
        return Uni.createFrom().item(versionById);
    }

    /** 记录最近一次 updateVersionStatus 的 "documentId|version|status"（null = 未调用） */
    private static volatile String lastVersionStatusUpdate;

    public static String getLastVersionStatusUpdate() {
        return lastVersionStatusUpdate;
    }

    public static void resetVersionStatusUpdate() {
        lastVersionStatusUpdate = null;
    }

    @Override
    public Uni<Boolean> updateVersionStatus(String documentId, String versionNumber, String status) {
        lastVersionStatusUpdate = documentId + "|" + versionNumber + "|" + status;
        return Uni.createFrom().item(true);
    }

    /** 最近一次 markProcessing 的文档 ID（relay 发送 DOCUMENT_PUBLISHED 成功后调用） */
    private static volatile String markedProcessingDocumentId;
    private static volatile int markProcessingCallCount;

    public static void resetMarkProcessing() {
        markedProcessingDocumentId = null;
        markProcessingCallCount = 0;
    }

    public static String getMarkedProcessingDocumentId() {
        return markedProcessingDocumentId;
    }

    public static int getMarkProcessingCallCount() {
        return markProcessingCallCount;
    }

    @Override
    public Uni<Void> markProcessing(DocumentId id) {
        markedProcessingDocumentId = id.value();
        markProcessingCallCount++;
        return Uni.createFrom().voidItem();
    }

    // ========== 批量审核查询 mock ==========

    /** findReviewingByKbId 返回的列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> reviewingByKbId;

    /** findReviewingByCategoryIds 返回的列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> reviewingByCategoryIds;

    /** 最近一次 findReviewingByCategoryIds 接收的 categoryIds 参数（断言递归子树收集） */
    private static volatile java.util.Collection<String> lastReviewingCategoryIds;

    /** findByIds 返回的列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> docsByIds;

    public static void setReviewingByKbId(java.util.List<DocumentEntity> docs) {
        reviewingByKbId = docs;
    }

    public static void setReviewingByCategoryIds(java.util.List<DocumentEntity> docs) {
        reviewingByCategoryIds = docs;
    }

    public static java.util.Collection<String> getLastReviewingCategoryIds() {
        return lastReviewingCategoryIds;
    }

    public static void setDocsByIds(java.util.List<DocumentEntity> docs) {
        docsByIds = docs;
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findReviewingByKbId(String kbId) {
        java.util.List<DocumentEntity> docs = reviewingByKbId;
        return Uni.createFrom().item(docs == null ? java.util.List.of() : docs);
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findReviewingByCategoryIds(
            String kbId, java.util.Collection<String> categoryIds) {
        lastReviewingCategoryIds = categoryIds;
        java.util.List<DocumentEntity> docs = reviewingByCategoryIds;
        return Uni.createFrom().item(docs == null ? java.util.List.of() : docs);
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findByIds(java.util.Collection<String> documentIds) {
        java.util.List<DocumentEntity> docs = docsByIds;
        return Uni.createFrom().item(docs == null ? java.util.List.of() : docs);
    }

    // ========== 批量提交审核查询 mock ==========

    /** findDraftByKbId 返回的列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> draftByKbId;

    /** findDraftByCategoryIds 返回的列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> draftByCategoryIds;

    /** 最近一次 findDraftByCategoryIds 接收的 categoryIds 参数（断言递归子树收集） */
    private static volatile java.util.Collection<String> lastDraftCategoryIds;

    public static void setDraftByKbId(java.util.List<DocumentEntity> docs) {
        draftByKbId = docs;
    }

    public static void setDraftByCategoryIds(java.util.List<DocumentEntity> docs) {
        draftByCategoryIds = docs;
    }

    public static java.util.Collection<String> getLastDraftCategoryIds() {
        return lastDraftCategoryIds;
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findDraftByKbId(String kbId) {
        java.util.List<DocumentEntity> docs = draftByKbId;
        return Uni.createFrom().item(docs == null ? java.util.List.of() : docs);
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findDraftByCategoryIds(
            String kbId, java.util.Collection<String> categoryIds) {
        lastDraftCategoryIds = categoryIds;
        java.util.List<DocumentEntity> docs = draftByCategoryIds;
        return Uni.createFrom().item(docs == null ? java.util.List.of() : docs);
    }

    // ========== 目录/KB 删除级联（阻塞计数 + 级联目标 + 版本文件路径）==========

    /** 阻塞状态集合（DELETE_BLOCKING_STATUSES：REVIEWING/PUBLISHED/ARCHIVED），识别 count 调用 */
    private static final java.util.Set<String> BLOCKING_SIGNATURE =
            java.util.Set.of(DocumentEntity.STATUS_REVIEWING, DocumentEntity.STATUS_PUBLISHED, DocumentEntity.STATUS_ARCHIVED);

    /** count*ByStatusIn 返回的阻塞文档数（null = 0，即无阻塞） */
    private static volatile Long blockingDocCount;

    /** count*ByStatusIn 返回的级联删除目标文档数（null = 0，即无级联目标） */
    private static volatile Long cascadeDocCount;

    /** findBy*AndStatusIn 返回的级联删除目标文档列表（null = 空列表） */
    private static volatile java.util.List<DocumentEntity> cascadeDocs;

    /** findVersionFilePaths 返回的历史版本文件路径列表（null = 空列表） */
    private static volatile java.util.List<String> versionFilePaths;

    /** 最近一次 findVersionFilePaths 接收的 documentId（断言删除前收集） */
    private static volatile String lastVersionFilePathsDocumentId;

    public static void setBlockingDocCount(Long count) {
        blockingDocCount = count;
    }

    public static void setCascadeDocCount(Long count) {
        cascadeDocCount = count;
    }

    public static void setCascadeDocs(java.util.List<DocumentEntity> docs) {
        cascadeDocs = docs;
    }

    public static void setVersionFilePaths(java.util.List<String> paths) {
        versionFilePaths = paths;
    }

    public static void resetCascadeMocks() {
        blockingDocCount = null;
        cascadeDocCount = null;
        cascadeDocs = null;
        versionFilePaths = null;
        lastVersionFilePathsDocumentId = null;
    }

    /** 根据请求的状态集合判断命中阻塞还是级联计数 */
    private static Long countOrZero(java.util.Collection<String> statuses, Long blocking, Long cascade) {
        if (statuses != null && java.util.Objects.equals(new java.util.HashSet<>(statuses), BLOCKING_SIGNATURE)) {
            return blocking == null ? 0L : blocking;
        }
        return cascade == null ? 0L : cascade;
    }

    @Override
    public Uni<Long> countByCategoryIdsAndStatusIn(String kbId,
                                                   java.util.Collection<String> categoryIds,
                                                   java.util.Collection<String> statuses) {
        return Uni.createFrom().item(countOrZero(statuses, blockingDocCount, cascadeDocCount));
    }

    @Override
    public Uni<Long> countByKbIdAndStatusIn(String kbId, java.util.Collection<String> statuses) {
        return Uni.createFrom().item(countOrZero(statuses, blockingDocCount, cascadeDocCount));
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findByCategoryIdsAndStatusIn(
            String kbId, java.util.Collection<String> categoryIds, java.util.Collection<String> statuses) {
        return Uni.createFrom().item(cascadeDocs == null ? java.util.List.of() : cascadeDocs);
    }

    @Override
    public Uni<java.util.List<DocumentEntity>> findByKbIdAndStatusIn(
            String kbId, java.util.Collection<String> statuses) {
        return Uni.createFrom().item(cascadeDocs == null ? java.util.List.of() : cascadeDocs);
    }

    @Override
    public Uni<java.util.List<String>> findVersionFilePaths(String documentId) {
        lastVersionFilePathsDocumentId = documentId;
        return Uni.createFrom().item(versionFilePaths == null ? java.util.List.of() : versionFilePaths);
    }

    public static String getLastVersionFilePathsDocumentId() {
        return lastVersionFilePathsDocumentId;
    }

    // ========== 替换文件回收守卫 mock ==========

    /** findIdsByFilePaths 返回的 filePath → documentId 映射（null = 空映射，即路径无任何文档引用） */
    private static volatile java.util.Map<String, String> idsByFilePaths;

    public static void setIdsByFilePaths(java.util.Map<String, String> map) {
        idsByFilePaths = map;
    }

    @Override
    public Uni<java.util.Map<String, String>> findIdsByFilePaths(java.util.Collection<String> filePaths) {
        java.util.Map<String, String> map = idsByFilePaths;
        return Uni.createFrom().item(map == null ? java.util.Map.of() : map);
    }

    /**
     * 单测替换两段解析（documents 优先、版本表兜底）：直接由 {@link #setIdsByFilePaths} 预置的映射派生，
     * 不触达 DB —— 父类实现会走 panacheRepo（mock 构造传入 null）。
     */
    @Override
    public Uni<java.util.Map<String, com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository.PathRef>>
            findRefsByFilePaths(java.util.Collection<String> filePaths) {
        return findIdsByFilePaths(filePaths).map(ids -> {
            java.util.Map<String, com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository.PathRef>
                    refs = new java.util.HashMap<>();
            ids.forEach((path, id) -> refs.put(path,
                    new com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository.PathRef(
                            id, null, null)));
            return refs;
        });
    }
}
