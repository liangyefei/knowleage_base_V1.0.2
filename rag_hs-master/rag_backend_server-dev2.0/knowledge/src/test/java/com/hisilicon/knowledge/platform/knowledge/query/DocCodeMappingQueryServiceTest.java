package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingClient;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingQuery;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.knowledge.command.DocCodeMappingCommand;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentVersion;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.facade.MockDocCodeMappingClient;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentRepositoryImpl;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.KnowledgeBaseRepositoryImpl;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @QuarkusTest}（mock 契约，不触库/不调模型）：{@link DocCodeMappingQueryService} 的
 * 版本解析、file 存储前缀裁剪（→ 模型项目内相对路径）、方向语义自动纠正与模型错误转换。
 *
 * <p>QuarkusTest 必需：服务内 {@code LocaleUtil.getAppMessage} 经 Qute MessageBundles 取
 * i18n 消息，需增强后的运行时。DocumentRepository/CategoryPanacheRepository/KnowledgeBaseRepository
 * 以 {@link QuarkusMock} 替换；模型端口以 {@link MockDocCodeMappingClient} 测试桩承接
 * （knowledge 测试 classpath 内 llm 真实实现不在场）。</p>
 */
@QuarkusTest
class DocCodeMappingQueryServiceTest {

    /** KB 物理目录段：存储路径形如 kb/CV610ALL/...，调用模型前裁掉 kb/CV610ALL/ 前缀 */
    private static final String KB_DIR_NAME = "CV610ALL";

    private static final KnowledgeBaseEntity KB = KnowledgeBaseEntity.builder()
            .id(KnowledgeBaseId.of("kb-1"))
            .name("CV610ALL")
            .dirName(KB_DIR_NAME)
            .status((short) 1)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    private static final DocumentEntity DOC = DocumentEntity.builder()
            .id(DocumentId.of("doc-1"))
            .kbId("kb-1")
            .title("CV610选型指南.pdf")
            .status(DocumentEntity.STATUS_PUBLISHED)
            .currentVersion("2")
            .filePath("kb/" + KB_DIR_NAME + "/docs/CV610选型指南.pdf")
            .docType(DocumentEntity.DOC_TYPE_DOCUMENT)
            .inheritParent(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    private static final DocumentEntity CODE_DOC = DocumentEntity.builder()
            .id(DocumentId.of("doc-code"))
            .kbId("kb-1")
            .title("驱动代码.c")
            .status(DocumentEntity.STATUS_PUBLISHED)
            .currentVersion("1")
            .filePath("kb/" + KB_DIR_NAME + "/src/驱动代码.c")
            .docType(DocumentEntity.DOC_TYPE_CODE)
            .inheritParent(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    @Inject
    DocumentRepository documentRepository;
    @Inject
    CategoryPanacheRepository categoryPanacheRepository;
    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;
    @Inject
    DocCodeMappingClient docCodeMappingClient;

    private DocumentRepository docRepo;
    private CategoryPanacheRepository catRepo;
    private KnowledgeBaseRepository kbRepo;

    @BeforeEach
    void installMocks() {
        MockDocCodeMappingClient.reset();
        // QuarkusMock 要求 mock 可赋给 bean 实现类（DocumentRepository 的 bean 类为 DocumentRepositoryImpl）
        docRepo = mock(DocumentRepositoryImpl.class);
        catRepo = mock(CategoryPanacheRepository.class);
        kbRepo = mock(KnowledgeBaseRepositoryImpl.class);
        QuarkusMock.installMockForInstance(docRepo, documentRepository);
        QuarkusMock.installMockForInstance(catRepo, categoryPanacheRepository);
        QuarkusMock.installMockForInstance(kbRepo, knowledgeBaseRepository);
        when(docRepo.findById(any())).thenReturn(Uni.createFrom().item(DOC));
        when(catRepo.count(any(String.class), any(Object[].class))).thenReturn(Uni.createFrom().item(1L));
        when(kbRepo.findById(any())).thenReturn(Uni.createFrom().item(KB));
    }

    private static DocCodeMappingCommand command(String version, String direction) {
        return new DocCodeMappingCommand("doc-1", version, direction, null, null, null);
    }

    private DocCodeMappingQueryService service() {
        return new DocCodeMappingQueryService(docRepo, catRepo, kbRepo, docCodeMappingClient);
    }

    // ======================== file 存储路径 → 模型项目内相对路径 ========================

    @Test
    void shouldStripKbPrefixForCurrentVersion() {
        service().map(command("2", "doc2code"), "zh-CN").await().indefinitely();

        DocCodeMappingQuery sent = MockDocCodeMappingClient.getLastQuery();
        // 模型项目根 = kb/{dirName}，file 必须是项目内相对路径（带 kb/{dirName}/ 会退化为文件名模糊匹配）
        assertEquals("docs/CV610选型指南.pdf", sent.file());
        assertEquals("kb-1", sent.project());
        assertEquals("doc2code", sent.direction());
        assertEquals(Boolean.FALSE, sent.exact());
        verify(docRepo, never()).findVersion(any(), any());
    }

    @Test
    void shouldStripKbPrefixForHistoricalVersion() {
        when(docRepo.findVersion("doc-1", "1"))
                .thenReturn(Uni.createFrom().item(new DocumentVersion(
                        "doc-1", "1", "kb/" + KB_DIR_NAME + "/docs/old-v1.pdf", 100L, "application/pdf",
                        true, DocumentVersion.STATUS_PUBLISHED, "admin", LocalDateTime.now())));

        service().map(command("1", "code2doc"), "zh-CN").await().indefinitely();

        // 历史版本行 file_path 同为存储路径，同样裁前缀；
        // direction 按文档 docType（DOCUMENT）自动纠正为 doc2code（入参 code2doc 被覆盖）
        DocCodeMappingQuery sent = MockDocCodeMappingClient.getLastQuery();
        assertEquals("docs/old-v1.pdf", sent.file());
        assertEquals("doc2code", sent.direction());
    }

    @Test
    void shouldKeepPathAsIsForNonKbPrefixPath() {
        // 非 kb/ 开头（异常/遗留数据）：原样传递（模型侧按项目内相对路径直接匹配）
        when(docRepo.findById(any())).thenReturn(Uni.createFrom().item(
                DocumentEntity.builder()
                        .id(DocumentId.of("doc-legacy"))
                        .kbId("kb-1")
                        .title("legacy.pdf")
                        .status(DocumentEntity.STATUS_PUBLISHED)
                        .currentVersion("1")
                        .filePath("unknown-prefix/legacy.pdf")
                        .docType(DocumentEntity.DOC_TYPE_DOCUMENT)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()));

        service().map(new DocCodeMappingCommand("doc-legacy", "1", "doc2code", null, null, null),
                "zh-CN").await().indefinitely();

        assertEquals("unknown-prefix/legacy.pdf", MockDocCodeMappingClient.getLastQuery().file());
    }

    @Test
    void shouldPassThroughWhenKbNotFoundOrDirNameBlank() {
        // KB 查不到（图谱含未在本平台注册的项目）/ dirName 为空（存量行）：透传原值，不阻断映射
        when(kbRepo.findById(any())).thenReturn(Uni.createFrom().nullItem());
        service().map(command("2", "doc2code"), "zh-CN").await().indefinitely();
        assertEquals("kb/" + KB_DIR_NAME + "/docs/CV610选型指南.pdf",
                MockDocCodeMappingClient.getLastQuery().file());

        MockDocCodeMappingClient.reset();
        KnowledgeBaseEntity blankDirName = KnowledgeBaseEntity.builder()
                .id(KnowledgeBaseId.of("kb-1"))
                .name("CV610ALL")
                .status((short) 1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(kbRepo.findById(any())).thenReturn(Uni.createFrom().item(blankDirName));
        service().map(command("2", "doc2code"), "zh-CN").await().indefinitely();
        assertEquals("kb/" + KB_DIR_NAME + "/docs/CV610选型指南.pdf",
                MockDocCodeMappingClient.getLastQuery().file());
    }

    @Test
    void shouldFallbackToFileNameWhenPathMisses() {
        // 首查 count=0 → 按文件名重试一次；重试命中则透传，hint 保留
        MockDocCodeMappingClient.setResultQueue(
                new DocCodeMappingResult("docs/miss.pdf", "kb-1", "doc2code", null, 0, null, "not matched", 3L),
                new DocCodeMappingResult("CV610选型指南.pdf", "kb-1", "doc2code", "document", 1, null, "fallback hit", 4L));

        DocCodeMappingResult result = service().map(command("2", "doc2code"), "zh-CN")
                .await().indefinitely();

        List<DocCodeMappingQuery> queries = MockDocCodeMappingClient.getAllQueries();
        assertEquals(2, queries.size());
        assertEquals("docs/CV610选型指南.pdf", queries.get(0).file());
        assertEquals("CV610选型指南.pdf", queries.get(1).file());
        assertEquals(1, result.count());
        assertEquals("fallback hit", result.hint());
    }

    @Test
    void shouldAppendHintWhenFileNameRetryAlsoMisses() {
        // 两次都未命中：hint 追加「未找到相关信息」，避免假空
        MockDocCodeMappingClient.setResultQueue(
                new DocCodeMappingResult("docs/miss.pdf", "kb-1", "doc2code", null, 0, null, null, 3L),
                new DocCodeMappingResult("miss.pdf", "kb-1", "doc2code", null, 0, null, "still empty", 4L));

        DocCodeMappingResult result = service().map(command("2", "doc2code"), "zh-CN")
                .await().indefinitely();

        assertEquals(0, result.count());
        assertTrue(result.hint().contains("still empty"));
        assertTrue(result.hint().contains("未找到相关信息"));
    }

    @Test
    void shouldNotRetryWhenFirstHitOrBareName() {
        // 首查命中 → 不重试，仅一次调用
        MockDocCodeMappingClient.setResult(new DocCodeMappingResult(
                "docs/x.pdf", "kb-1", "doc2code", "document", 1, null, null, 5L));
        service().map(command("2", "doc2code"), "zh-CN").await().indefinitely();
        assertEquals(1, MockDocCodeMappingClient.getAllQueries().size());
    }

    // ======================== 方向语义自动纠正 ========================

    @Test
    void shouldDeriveDirectionFromCodeDocType() {
        // 前端错传 doc2code 但选了代码文件 → 按 docType 自动纠正为 code2doc（见类 javadoc 理由）
        when(docRepo.findById(any())).thenReturn(Uni.createFrom().item(CODE_DOC));

        service().map(new DocCodeMappingCommand("doc-code", "1", "doc2code", null, null, null),
                "zh-CN").await().indefinitely();

        assertEquals("code2doc", MockDocCodeMappingClient.getLastQuery().direction());
        assertEquals("src/驱动代码.c", MockDocCodeMappingClient.getLastQuery().file());
    }

    @Test
    void shouldFailA0420WhenDirectionInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "auto"), "zh-CN").await().indefinitely());
        assertEquals("A0420", ex.getErrorCodeEnum().getCode());
        // 校验失败不应触达模型
        assertTrue(MockDocCodeMappingClient.getAllQueries().isEmpty());
    }

    // ======================== 校验与版本异常 ========================

    @Test
    void shouldFailA0430WhenDocumentNotFound() {
        when(docRepo.findById(any())).thenReturn(Uni.createFrom().nullItem());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0430", ex.getErrorCodeEnum().getCode());
    }

    @Test
    void shouldFailA0430WhenVersionNotExist() {
        when(docRepo.findVersion("doc-1", "9")).thenReturn(Uni.createFrom().nullItem());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("9", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0430", ex.getErrorCodeEnum().getCode());
    }

    @Test
    void shouldFailA0508WhenVersionArchived() {
        when(docRepo.findVersion("doc-1", "1"))
                .thenReturn(Uni.createFrom().item(new DocumentVersion(
                        "doc-1", "1", "kb/" + KB_DIR_NAME + "/docs/old-v1.pdf", 100L, "application/pdf",
                        true, DocumentVersion.STATUS_ARCHIVED, "admin", LocalDateTime.now())));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("1", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0508", ex.getErrorCodeEnum().getCode());
        assertTrue(MockDocCodeMappingClient.getAllQueries().isEmpty());
    }

    // ======================== 模型错误转换（含响应体 detail） ========================

    @Test
    void shouldMapModel404ToA0430() {
        MockDocCodeMappingClient.setModelStatusToThrow(404);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0430", ex.getErrorCodeEnum().getCode());
    }

    @Test
    void shouldMapModel400ToA0420() {
        MockDocCodeMappingClient.setModelStatusToThrow(400);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0420", ex.getErrorCodeEnum().getCode());
    }

    @Test
    void shouldMapModel422ToA0420WithDetail() {
        // 422：转 A0420 且 message 携带模型响应体 detail（不再笼统 C0001）
        MockDocCodeMappingClient.setModelStatusToThrow(422);
        MockDocCodeMappingClient.setErrorBody(
                "{\"detail\":\"File 'src/x.c' not found in project\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("A0420", ex.getErrorCodeEnum().getCode());
        assertTrue(ex.getMessage().contains("File 'src/x.c' not found in project"));
    }

    @Test
    void shouldMapModel500ToC0001() {
        MockDocCodeMappingClient.setModelStatusToThrow(500);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().map(command("2", "doc2code"), "zh-CN").await().indefinitely());
        assertEquals("C0001", ex.getErrorCodeEnum().getCode());
    }

    // ======================== 成功（含空结果）透传 ========================

    @Test
    void shouldPassThroughResultWithHint() {
        // 命中结果原样透传给门面（含 hint）
        DocCodeMappingResult hit = new DocCodeMappingResult(
                "docs/CV610选型指南.pdf", "kb-1", "doc2code", "document", 2, null, "部分路径未精确匹配", 3L);
        MockDocCodeMappingClient.setResult(hit);

        DocCodeMappingResult result = service().map(command("2", "doc2code"), "zh-CN")
                .await().indefinitely();
        assertEquals(2, result.count());
        assertEquals("部分路径未精确匹配", result.hint());
    }
}
