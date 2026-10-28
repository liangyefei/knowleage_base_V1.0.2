package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentRepositoryImpl;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批量删除测试专用 Mock — 替换 {@link DocumentRepositoryImpl}
 * <p>独立于共享的 {@link MockDocumentRepository}（避免与既有 delete 用例的单实体语义互相干扰）：
 * 支持按文档 ID 精确控制 {@code findById}、按 (documentId, version) 控制 {@code findVersion}
 * （未命中视为版本不存在）、按版本键注入删除失败，并按调用顺序累计 {@code deleteVersion}
 * 供批量断言。</p>
 */
public class MockBatchDeleteDocumentRepository extends DocumentRepositoryImpl {

    /** 按文档 ID 的实体表：命中返回实体，未命中视为不存在；null = 回退 singleEntity */
    private static volatile Map<String, DocumentEntity> entitiesById;

    /** 兜底单实体（无论请求哪个 ID 都返回它；null = 全部不存在） */
    private static volatile DocumentEntity singleEntity;

    /** 按 "documentId|version" 键的版本表：命中返回版本，未命中视为版本不存在 */
    private static volatile Map<String, DocumentVersion> versionsByKey;

    /** deleteVersion 时注入失败版本键集合（模拟执行异常） */
    private static volatile Set<String> failDeleteVersionKeys;

    /** 累计 deleteVersion 的 "documentId|version"（按调用顺序） */
    private static final List<String> deletedVersionKeys = new CopyOnWriteArrayList<>();

    /** 无参构造，跳过父类的 CDI 注入 */
    public MockBatchDeleteDocumentRepository() {
        super(null, null, null);
    }

    public static void setEntitiesById(Map<String, DocumentEntity> map) {
        entitiesById = map;
    }

    public static void setSingleEntity(DocumentEntity entity) {
        singleEntity = entity;
    }

    public static void setVersionsByKey(Map<String, DocumentVersion> map) {
        versionsByKey = map;
    }

    public static void setFailDeleteVersionKeys(Set<String> keys) {
        failDeleteVersionKeys = keys;
    }

    public static List<String> getDeletedVersionKeys() {
        return List.copyOf(deletedVersionKeys);
    }

    public static void reset() {
        entitiesById = null;
        singleEntity = null;
        versionsByKey = null;
        failDeleteVersionKeys = null;
        deletedVersionKeys.clear();
        findVersionCalls.clear();
    }

    @Override
    public Uni<DocumentEntity> findById(DocumentId id) {
        Map<String, DocumentEntity> map = entitiesById;
        if (map != null) {
            DocumentEntity hit = map.get(id.value());
            return hit == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(hit);
        }
        DocumentEntity entity = singleEntity;
        return entity == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(entity);
    }

    /** 累计 findVersion 的 "documentId|version"（调试用） */
    private static final List<String> findVersionCalls = new CopyOnWriteArrayList<>();

    @Override
    public Uni<DocumentVersion> findVersion(String documentId, String versionNumber) {
        findVersionCalls.add(documentId + "|" + versionNumber);
        Map<String, DocumentVersion> map = versionsByKey;
        if (map == null) {
            return Uni.createFrom().nullItem();
        }
        DocumentVersion hit = map.get(documentId + "|" + versionNumber);
        return hit == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(hit);
    }

    public static List<String> getFindVersionCalls() {
        return List.copyOf(findVersionCalls);
    }

    public static String debugVersionsByKey() {
        Map<String, DocumentVersion> map = versionsByKey;
        return map == null ? "null" : map.keySet().toString();
    }

    @Override
    public Uni<Boolean> deleteVersion(String documentId, String versionNumber) {
        String key = documentId + "|" + versionNumber;
        deletedVersionKeys.add(key);
        Set<String> failKeys = failDeleteVersionKeys;
        if (failKeys != null && failKeys.contains(key)) {
            return Uni.createFrom().failure(new RuntimeException("mock 版本删除执行失败: " + key));
        }
        return Uni.createFrom().item(true);
    }

    /** 删除当前版本（DRAFT 文档）路径：no-op，不触父类 Panache 依赖（无参构造下均为 null） */
    @Override
    public Uni<Void> deleteDocumentKeepVersions(DocumentId id) {
        return Uni.createFrom().voidItem();
    }

    /** 文档级物理删除路径：no-op，不触父类 Panache 依赖 */
    @Override
    public Uni<Void> delete(DocumentId id) {
        return Uni.createFrom().voidItem();
    }
}