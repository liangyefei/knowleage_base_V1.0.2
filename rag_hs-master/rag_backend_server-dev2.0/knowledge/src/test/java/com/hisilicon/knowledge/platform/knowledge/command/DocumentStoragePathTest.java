package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储路径名称化（快照语义）单测：目录段清洗/回退/截断、类别链解析顺序、防环与断链终止。
 * 仓储用进程内假实现直喂构造器——被测方法不触 DB/FS。
 */
class DocumentStoragePathTest {

    // ========== 纯拼装器 ==========

    @Test
    void assembleShouldNestKbRootAndSegmentsInOrder() {
        String path = DocumentStoragePathResolver.assembleStoragePath(
                List.of("产品知识库", "一级目录", "二级目录"), "doc-1", "海思设计.pdf", "pdf");
        assertEquals("kb/产品知识库/一级目录/二级目录/海思设计.pdf", path);
    }

    @Test
    void assembleShouldSanitizeFileNameAndFallbackToDocumentId() {
        // 既有语义：文件名先取最后一段路径再清洗非法字符（"a/" 整段剥离，":*?" 移除）
        assertEquals("kb/产品库/bcd.txt",
                DocumentStoragePathResolver.assembleStoragePath(List.of("产品库"), "doc-1", "a/b:c*d?.txt", "txt"));
        // 文件名清洗后为空 → 回退 documentId（含扩展名）
        assertEquals("kb/产品库/doc-9.pdf",
                DocumentStoragePathResolver.assembleStoragePath(List.of("产品库"), "doc-9", "///", "pdf"));
    }

    // ========== 单目录段规则 ==========

    @Test
    void dirSegmentShouldCleanTruncateAndFallback() {
        assertEquals("无非法字符", DocumentStoragePathResolver.dirSegment("无:*?\"<>|非法字符", "id-1"));
        assertEquals("id-fallback", DocumentStoragePathResolver.dirSegment("///", "id-fallback"));
        String longName = "长".repeat(80);
        assertEquals(60, DocumentStoragePathResolver.dirSegment(longName, "id").length());
        // 截断不得切在代理对中间：UTF-16 高位代理结尾时回退一位
        String surrogateTail = "字".repeat(59) + "\uD83D\uDE00";
        String cut = DocumentStoragePathResolver.dirSegment(surrogateTail, "id");
        assertTrue(!Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "截断边界不应落在高位代理上");
    }

    // ========== 类别链解析（经公开的 storageDirForCategory） ==========

    @Test
    void shouldResolveChainFromLeafToRootInVisualOrder() {
        FakeKnowledgeBaseRepository kbRepo = new FakeKnowledgeBaseRepository();
        kbRepo.put(activeKb("kb-9", "产品知识库"));
        FakeCategoryRepository catRepo = new FakeCategoryRepository();
        catRepo.put(category("c1", "kb-9", null, "一级"));
        catRepo.put(category("c2", "kb-9", "c1", "二级"));
        catRepo.put(category("c3", "kb-9", "c2", "三级"));

        DocumentStoragePathResolver svc = newSvc(kbRepo, catRepo);

        assertEquals("kb/产品知识库/一级/二级/三级",
                svc.storageDirForCategory("kb-9", "c3").await().indefinitely());
        // 无类别直挂：仅 kb 名一层
        assertEquals("kb/产品知识库",
                svc.storageDirForCategory("kb-9", null).await().indefinitely());
    }

    @Test
    void shouldTerminateOnCycleAndMissingLinks() {
        FakeKnowledgeBaseRepository kbRepo = new FakeKnowledgeBaseRepository();
        kbRepo.put(activeKb("kb-9", "循环库"));
        FakeCategoryRepository catRepo = new FakeCategoryRepository();
        // 环：c1 ↔ c2
        catRepo.put(category("c1", "kb-9", "c2", "甲"));
        catRepo.put(category("c2", "kb-9", "c1", "乙"));

        DocumentStoragePathResolver svc = newSvc(kbRepo, catRepo);
        String path = svc.storageDirForCategory("kb-9", "c1").await().indefinitely();
        assertTrue(path.startsWith("kb/循环库/") && path.contains("甲") && path.contains("乙"),
                "环应在有限步内终止且包含已取名段: " + path);

        // 叶子存在、父链断裂（父 ID 无记录）：就地终止，不抛错
        FakeCategoryRepository broken = new FakeCategoryRepository();
        broken.put(category("leaf", "kb-9", "ghost", "孤儿"));
        DocumentStoragePathResolver brokenSvc = newSvc(kbRepo, broken);
        String orphan = brokenSvc.storageDirForCategory("kb-9", "leaf").await().indefinitely();
        assertEquals("kb/循环库/孤儿", orphan);
    }

    @Test
    void shouldFallbackToIdsWhenNamesMissing() {
        // KB 查不到 → 首段回退 kbId；类别名为全非法字符 → 回退类别 ID
        FakeKnowledgeBaseRepository kbRepo = new FakeKnowledgeBaseRepository();
        FakeCategoryRepository catRepo = new FakeCategoryRepository();
        catRepo.put(category("cid", "kb-x", null, ":"));

        DocumentStoragePathResolver svc = newSvc(kbRepo, catRepo);
        assertEquals("kb/kb-x/cid",
                svc.storageDirForCategory("kb-x", "cid").await().indefinitely());
    }

    @Test
    void shouldPreferPersistedDirNameOverCurrentName() {
        // dirName（创建时唯一化定格）优先于当前名称；NULL 段回退名称（存量兼容）
        FakeKnowledgeBaseRepository kbRepo = new FakeKnowledgeBaseRepository();
        kbRepo.put(kbWithDirName("kb-9", "现名库", "创建时段"));
        FakeCategoryRepository catRepo = new FakeCategoryRepository();
        catRepo.put(categoryWithDirName("c1", "kb-9", null, "现名一", "d1"));
        catRepo.put(categoryWithDirName("c2", "kb-9", "c1", "现名二", null));

        DocumentStoragePathResolver svc = newSvc(kbRepo, catRepo);

        assertEquals("kb/创建时段/d1/现名二",
                svc.storageDirForCategory("kb-9", "c2").await().indefinitely());
        // 叶子自身有 dirName 时同样优先
        assertEquals("kb/创建时段/d1",
                svc.storageDirForCategory("kb-9", "c1").await().indefinitely());
    }

    // ========== 基建 ==========

    private static DocumentStoragePathResolver newSvc(KnowledgeBaseRepository kbRepo,
                                                 CategoryRepository catRepo) {
        return new DocumentStoragePathResolver(kbRepo, catRepo, null, null);
    }

    private static KnowledgeBaseEntity activeKb(String id, String name) {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(id)).name(name).description("desc")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .createdBy("admin").createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static CategoryEntity category(String id, String kbId, String parentId, String name) {
        return categoryWithDirName(id, kbId, parentId, name, null);
    }

    private static CategoryEntity categoryWithDirName(String id, String kbId, String parentId,
                                                      String name, String dirName) {
        return CategoryEntity.builder()
                .id(CategoryId.of(id)).kbId(kbId).parentId(parentId).name(name).dirName(dirName)
                .sortOrder(1).status(CategoryEntity.STATUS_ACTIVE)
                .createdAt(java.time.LocalDateTime.now()).build();
    }

    private static KnowledgeBaseEntity kbWithDirName(String id, String name, String dirName) {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(id)).name(name).dirName(dirName).description("desc")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .createdBy("admin").createdAt(java.time.LocalDateTime.now())
                .build();
    }

    /** 进程内假 KB 仓储：只实现路径解析会触达的方法 */
    static class FakeKnowledgeBaseRepository implements KnowledgeBaseRepository {
        final Map<String, KnowledgeBaseEntity> store = new HashMap<>();

        void put(KnowledgeBaseEntity entity) { store.put(entity.getId().value(), entity); }

        @Override
        public Uni<KnowledgeBaseEntity> findById(KnowledgeBaseId id) {
            return Uni.createFrom().item(store.get(id.value()));
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

    /** 进程内假类别仓储：只实现路径解析会触达的方法 */
    static class FakeCategoryRepository implements CategoryRepository {
        final Map<String, CategoryEntity> store = new HashMap<>();

        void put(CategoryEntity entity) { store.put(entity.getId().value(), entity); }

        @Override
        public Uni<CategoryEntity> findById(CategoryId id) {
            return Uni.createFrom().item(store.get(id.value()));
        }

        @Override
        public Uni<CategoryEntity> findByName(String kbId, String parentId, String name) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Integer> maxSortOrder(String kbId, String parentId) { return Uni.createFrom().nullItem(); }

        @Override
        public Uni<CategoryEntity> persist(CategoryEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<CategoryEntity> update(CategoryEntity entity) { return Uni.createFrom().item(entity); }

        @Override
        public Uni<List<CategoryEntity>> findAllByKbId(String kbId) { return Uni.createFrom().item(List.of()); }
    }
}
