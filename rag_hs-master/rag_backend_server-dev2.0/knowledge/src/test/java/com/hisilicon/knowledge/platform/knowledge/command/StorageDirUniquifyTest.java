package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockCategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockDocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockFileStorageService;
import com.hisilicon.knowledge.platform.knowledge.facade.MockKnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 创建知识库/类别时同名物理目录自动唯一化单测（回归防护）。
 * <p>背景：旧 KB/类别改名后物理目录仍占旧名，再创建同名节点时
 * {@code ensureDirectory} 幂等成功但两个逻辑节点共享同一物理目录（文件混放、
 * 回收互扰）。创建环节须探测磁盘已占用并跳号唯一化（{seg}_1、{seg}_2…），
 * 实际段持久化到 {@code dirName}，预建目录使用唯一化后的路径。</p>
 * <p>服务用进程内直喂构造器（绕开事务拦截器），仓储/存储用 Mock 精确控制
 * 磁盘占用视图——不触真实 DB/FS。</p>
 */
@QuarkusTest
class StorageDirUniquifyTest {

    private static final String LOCALE = "zh-CN";
    private static final String OP = "admin";

    @Inject
    DocumentOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        MockKnowledgeBaseRepository.resetPersistedEntity();
        MockKnowledgeBaseRepository.resetUpdatedEntity();
        MockKnowledgeBaseRepository.setExistingEntity(null);
        MockKnowledgeBaseRepository.setEntityById(null);
        MockCategoryRepository.resetUpdatedEntities();
        MockCategoryRepository.setSiblingEntity(null);
        MockCategoryRepository.setEntityById(null);
        MockCategoryRepository.setMaxSortOrder(null);
        MockCategoryRepository.setAllCategories(null);
        MockFileStorageService.reset();
        MockDocumentOutboxRepository.reset();
        // create 流程仅写 KB_CREATED 事件，Mock 捕获即可（不触真实 DB）
        outboxRepository = new MockDocumentOutboxRepository();
    }

    @AfterEach
    void tearDown() {
        MockFileStorageService.reset();
    }

    private KnowledgeBaseCommandService newKbService() {
        return new KnowledgeBaseCommandService(
                new MockKnowledgeBaseRepository(), outboxRepository, null, null, null,
                new MockFileStorageService(), null, null);
    }

    private CategoryCommandService newCategoryService() {
        MockKnowledgeBaseRepository kbRepo = new MockKnowledgeBaseRepository();
        MockFileStorageService storage = new MockFileStorageService();
        // 真实 DocumentStoragePathResolver（父目录解析逻辑被测），仓储/存储全 Mock
        DocumentStoragePathResolver pathResolver = new DocumentStoragePathResolver(
                kbRepo, null, null, storage);
        return new CategoryCommandService(
                kbRepo, new MockCategoryRepository(), null,
                null, pathResolver, storage, null, null);
    }

    // ======================== 知识库创建 ========================

    /** 目录空闲 → 用原清洗段，不跳号 */
    @Test
    void kbCreateShouldUseCleanSegmentWhenDirFree() {
        newKbService().create(OP, new CreateKbCommand("产品知识库", null), LOCALE)
                .await().indefinitely();

        KnowledgeBaseEntity persisted = MockKnowledgeBaseRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals("产品知识库", persisted.getDirName(), "目录空闲时应持久化原段到 dirName");
        assertEquals("kb/产品知识库", MockFileStorageService.getLastEnsureDirPath(),
                "预建目录应使用唯一化后的段");
    }

    /** 目录被占用（旧改名 KB 占旧名）→ 自动跳号 _1，且 dirName 与预建目录一致 */
    @Test
    void kbCreateShouldUniquifyWhenDirOccupied() {
        MockFileStorageService.setExistingPaths(List.of("kb/产品知识库"));

        newKbService().create(OP, new CreateKbCommand("产品知识库", null), LOCALE)
                .await().indefinitely();

        KnowledgeBaseEntity persisted = MockKnowledgeBaseRepository.getPersistedEntity();
        assertEquals("产品知识库_1", persisted.getDirName(), "目录被占用应跳号 _1");
        assertEquals("kb/产品知识库_1", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 原段与 _1 均被占用 → 递增到 _2 */
    @Test
    void kbCreateShouldIncrementAcrossMultipleCollisions() {
        MockFileStorageService.setExistingPaths(List.of("kb/产品知识库", "kb/产品知识库_1"));

        newKbService().create(OP, new CreateKbCommand("产品知识库", null), LOCALE)
                .await().indefinitely();

        assertEquals("产品知识库_2", MockKnowledgeBaseRepository.getPersistedEntity().getDirName());
        assertEquals("kb/产品知识库_2", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 名称含点号：目录无扩展名语义，序号加在整段末尾（目录1.1 → 目录1.1_1，而非 目录1_1.1） */
    @Test
    void kbCreateShouldAppendSuffixAtEndForDottedName() {
        MockFileStorageService.setExistingPaths(List.of("kb/目录1.1"));

        newKbService().create(OP, new CreateKbCommand("目录1.1", null), LOCALE)
                .await().indefinitely();

        KnowledgeBaseEntity persisted = MockKnowledgeBaseRepository.getPersistedEntity();
        assertEquals("目录1.1_1", persisted.getDirName(), "点号不应被当作扩展名拆分");
        assertEquals("kb/目录1.1_1", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 名称合法化前后一致：唯一化基于清洗后的段（非法字符已剔除） */
    @Test
    void kbCreateShouldUniquifySanitizedSegmentConsistently() {
        MockFileStorageService.setExistingPaths(List.of("kb/abc"));

        newKbService().create(OP, new CreateKbCommand("a:b*c?", null), LOCALE)
                .await().indefinitely();

        KnowledgeBaseEntity persisted = MockKnowledgeBaseRepository.getPersistedEntity();
        assertEquals("abc_1", persisted.getDirName(), "应基于清洗段 abc 跳号");
        assertEquals("kb/abc_1", MockFileStorageService.getLastEnsureDirPath());
    }

    // ======================== 类别创建 ========================

    /** 根类别：叶段在 KB 目录（KB dirName 优先）下空闲 → 原段 */
    @Test
    void categoryCreateShouldUseFreeSegmentUnderKbDir() {
        MockKnowledgeBaseRepository.setEntityById(activeKb("kb-1", "旧名库", "旧名库"));

        newCategoryService().create(OP, "kb-1", new CreateCategoryCommand("报告", null), LOCALE)
                .await().indefinitely();

        CategoryEntity persisted = MockCategoryRepository.getPersistedEntity();
        assertNotNull(persisted);
        assertEquals("报告", persisted.getDirName());
        assertEquals("kb/旧名库/报告", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 叶段在父目录下被占用 → 跳号 _1（同 KB 旧类别改名后占旧名场景） */
    @Test
    void categoryCreateShouldUniquifyWhenLeafDirOccupied() {
        MockKnowledgeBaseRepository.setEntityById(activeKb("kb-1", "旧名库", "旧名库"));
        MockFileStorageService.setExistingPaths(List.of("kb/旧名库/报告"));

        newCategoryService().create(OP, "kb-1", new CreateCategoryCommand("报告", null), LOCALE)
                .await().indefinitely();

        CategoryEntity persisted = MockCategoryRepository.getPersistedEntity();
        assertEquals("报告_1", persisted.getDirName());
        assertEquals("kb/旧名库/报告_1", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 多次占用递增：报告 与 报告_1 均占用 → 报告_2 */
    @Test
    void categoryCreateShouldIncrementAcrossMultipleCollisions() {
        MockKnowledgeBaseRepository.setEntityById(activeKb("kb-1", "旧名库", "旧名库"));
        MockFileStorageService.setExistingPaths(List.of("kb/旧名库/报告", "kb/旧名库/报告_1"));

        newCategoryService().create(OP, "kb-1", new CreateCategoryCommand("报告", null), LOCALE)
                .await().indefinitely();

        assertEquals("报告_2", MockCategoryRepository.getPersistedEntity().getDirName());
        assertEquals("kb/旧名库/报告_2", MockFileStorageService.getLastEnsureDirPath());
    }

    /** 名称合法化前后一致：清洗段被占用时基于清洗段跳号 */
    @Test
    void categoryCreateShouldUniquifySanitizedSegmentConsistently() {
        MockKnowledgeBaseRepository.setEntityById(activeKb("kb-1", "旧名库", "旧名库"));
        MockFileStorageService.setExistingPaths(List.of("kb/旧名库/设计"));

        newCategoryService().create(OP, "kb-1", new CreateCategoryCommand("设:计?", null), LOCALE)
                .await().indefinitely();

        CategoryEntity persisted = MockCategoryRepository.getPersistedEntity();
        assertEquals("设计_1", persisted.getDirName());
        assertEquals("kb/旧名库/设计_1", MockFileStorageService.getLastEnsureDirPath());
    }

    // ======================== 基建 ========================

    private static KnowledgeBaseEntity activeKb(String id, String name, String dirName) {
        return KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(id))
                .name(name)
                .dirName(dirName)
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .createdBy(OP)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
