package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.facade.MockCategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockDocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockKnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除知识库/类别时的文件数量上限（200）校验测试。
 * <p>当知识库/类别下待级联物理删除的 DRAFT/DEPRECATED 文档数超过 200 时，
 * 删除应在编排只读校验阶段直接抛 {@code BusinessException(A0420)} 拒绝，
 * 不进入删除事务（避免单次删除文件过多导致请求超时/网络错误）。</p>
 * <p>沿用进程内直接构造服务的手法（对齐 {@code DocumentCommandServiceReplaceFileRecycleTest}），
 * 仓库经 Mock 精确控制 count 返回值。拒绝路径不会触达 {@code self.deleteKbInTx/deleteCategoryInTx}，
 * 故 self 自注入置空即可。</p>
 */
@QuarkusTest
class KnowledgeBaseCategoryDeleteLimitTest {

    private static final String LOCALE = "zh-CN";
    private static final String KB_ID = "kb-limit";
    private static final String CAT_ID = "cat-limit";
    private static final String OP = "admin";

    private KnowledgeBaseEntity kb;
    private CategoryEntity category;

    @BeforeEach
    void setup() {
        MockDocumentRepository.resetCascadeMocks();
        MockKnowledgeBaseRepository.resetPersistedEntity();
        MockKnowledgeBaseRepository.resetUpdatedEntity();
        MockCategoryRepository.resetUpdatedEntities();
        kb = KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of(KB_ID))
                .name("限制库")
                .status(KnowledgeBaseEntity.STATUS_ACTIVE)
                .processStatus(KnowledgeBaseEntity.PROCESS_PENDING)
                .createdBy(OP)
                .createdAt(LocalDateTime.now())
                .build();
        category = CategoryEntity.builder()
                .id(CategoryId.of(CAT_ID))
                .kbId(KB_ID)
                .parentId(null)
                .name("限制目录")
                .sortOrder(1)
                .inheritParent(true)
                .status(CategoryEntity.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ======================== 知识库删除 ========================

    @Test
    void kbDeleteRejectedWhenCascadeDocsExceed200() {
        MockKnowledgeBaseRepository.setEntityById(kb);
        MockDocumentRepository.setCascadeDocCount(201L); // 阻塞0，级联201 > 200

        KnowledgeBaseCommandService service = new KnowledgeBaseCommandService(
                new MockKnowledgeBaseRepository(), null, new MockCategoryRepository(), null,
                new MockDocumentRepository(), null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(OP, KB_ID, LOCALE).await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0420, ex.getErrorCodeEnum());
        assertEquals("知识库下待删除文件数量超过上限200，请分批清理后再删除", ex.getMessage());
        // 拒绝路径不进入删除事务：KB 未被软删
        assertTrue(MockKnowledgeBaseRepository.getUpdatedEntity() == null,
                "超限应拒绝删除，不允许软删知识库");
    }

    // ======================== 类别删除 ========================

    @Test
    void categoryDeleteRejectedWhenCascadeDocsExceed200() {
        MockCategoryRepository.setEntityById(category);
        MockCategoryRepository.setAllCategories(List.of(category));
        MockDocumentRepository.setCascadeDocCount(201L);

        CategoryCommandService service = new CategoryCommandService(
                new MockKnowledgeBaseRepository(), new MockCategoryRepository(),
                new MockDocumentRepository(), null, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(OP, CAT_ID, LOCALE).await().indefinitely());

        assertEquals(ErrorCodeEnum.USER_ERROR_A0420, ex.getErrorCodeEnum());
        assertEquals("目录下待删除文件数量超过上限200，请分批清理后再删除", ex.getMessage());
        // 拒绝路径不进入删除事务：无类别被软删
        assertTrue(MockCategoryRepository.getUpdatedEntities().isEmpty(),
                "超限应拒绝删除，不允许软删类别");
    }
}