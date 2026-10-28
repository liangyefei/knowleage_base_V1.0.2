package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeBaseChatResolver;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.KnowledgeBasePO;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：{@link KnowledgeBaseChatResolver} 就绪过滤（真实 PostgreSQL）。
 *
 * <p>验证：前端传入 kbIds，解析器仅返回 {@code status=1} 且 {@code process_status='COMPLETED'}
 * 的就绪子集（向量库就绪可对话），按 {@code created_at} 升序；未就绪（PENDING）/已删（status=0）
 * 被过滤；不存在的 id 无命中。</p>
 *
 * <p>语义变更：旧版按 official+chip_model+docType/version 跨三层元数据解析；现改为前端直传 kbIds，
 * 解析器仅做就绪守卫。运行：{@code ./gradlew :knowledge:integrationTest --rerun-tasks}</p>
 */
@QuarkusTest
class KnowledgeBaseChatResolverIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

    @Inject
    KnowledgeBaseChatResolver resolver;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @BeforeEach
    void seed() {
        sessionFactory.withTransaction(session -> session
                        .createQuery("delete from KnowledgeBaseMetadataPO").executeUpdate()
                        .chain(() -> session.createQuery("delete from CategoryMetadataPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentMetadataPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentVersionPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from DocumentPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from CategoryPO").executeUpdate())
                        .chain(() -> session.createQuery("delete from KnowledgeBasePO").executeUpdate())
                        .chain(() -> persistAll(session,
                                kb("kb-1", "就绪库1", (short) 1, "COMPLETED", 1),
                                kb("kb-2", "就绪库2", (short) 1, "COMPLETED", 2),
                                kb("kb-3", "未就绪PENDING", (short) 1, "PENDING", 3),
                                kb("kb-4", "已删库", (short) 0, "COMPLETED", 4),
                                kb("kb-5", "就绪库3", (short) 1, "COMPLETED", 5)))
                        .replaceWithVoid())
                .await().indefinitely();
    }

    @Test
    void shouldFilterReadyKbIds() {
        // 传入全部 5 个，仅返回就绪的 kb-1/kb-2/kb-5（status=1 且 COMPLETED），按 created_at 升序
        List<String> result = resolve(List.of("kb-1", "kb-2", "kb-3", "kb-4", "kb-5"));
        assertEquals(List.of("kb-1", "kb-2", "kb-5"), result);
    }

    @Test
    void shouldReturnEmptyWhenAllUnready() {
        // 全部未就绪/已删 → 空列表
        List<String> result = resolve(List.of("kb-3", "kb-4"));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForUnknownIds() {
        // 传入不存在的 id → 空列表（解析器按 kb.id IN 过滤，无命中）
        List<String> result = resolve(List.of("kb-unknown"));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        List<String> result = resolve(List.of());
        assertTrue(result.isEmpty());
    }

    private List<String> resolve(List<String> kbIds) {
        return sessionFactory.withSession(s -> resolver.resolveKbIds(kbIds))
                .await().indefinitely();
    }

    private static <T> Uni<Void> persistAll(Mutiny.Session session, T... entities) {
        Uni<Void> uni = Uni.createFrom().voidItem();
        for (T entity : entities) {
            uni = uni.chain(() -> session.persist(entity));
        }
        return uni;
    }

    private static KnowledgeBasePO kb(String id, String name, Short status, String processStatus, int seq) {
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(id);
        po.setName(name);
        po.setStatus(status);
        po.setProcessStatus(processStatus);
        po.setCreatedBy("admin");
        po.setCreatedAt(BASE.plusSeconds(seq));
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }
}
