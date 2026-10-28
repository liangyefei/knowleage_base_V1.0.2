package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 集成测试（真实 PostgreSQL）：{@code findByIdForUpdate} 悲观锁查询必须能执行。
 * <p>背景：曾用 {@code session.find(PO, id, PESSIMISTIC_WRITE)}，Hibernate Reactive 对它另行
 * 生成内部锁查询 {@code select id from knowledge_bases where id=$1 for no key update} 且不绑定
 * id 参数，执行报 "expected 1 parameter but actual 0"，删除知识库接口 500。
 * 本测试连真实 PG（kb_test），锁查询走真实 SQL 路径，参数绑定缺陷一旦回归立即在此失败。</p>
 * <p>运行：{@code ./gradlew :knowledge:integrationTest --tests "*KnowledgeBaseRepositoryLockIT*" --rerun-tasks}</p>
 */
@QuarkusTest
class KnowledgeBaseRepositoryLockIT {

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @Inject
    KnowledgeBaseRepository knowledgeBaseRepository;

    @Test
    void findByIdForUpdateLocksPersistedRow() {
        KnowledgeBaseEntity kb = KnowledgeBaseEntity.create("lock-it-kb", "锁查询回归", "tester")
                .withDirName("lock_it_kb");

        KnowledgeBaseEntity found = sessionFactory.withTransaction(session ->
                        knowledgeBaseRepository.persist(kb)
                                .chain(persisted -> knowledgeBaseRepository
                                        .findByIdForUpdate(KnowledgeBaseId.of(persisted.getId().value()))))
                .await().indefinitely();

        assertNotNull(found, "findByIdForUpdate should return the persisted KB row");
        assertEquals(kb.getName(), found.getName());
        assertEquals(KnowledgeBaseEntity.STATUS_ACTIVE, found.getStatus());
    }

    @Test
    void findByIdForUpdateReturnsNullForMissingId() {
        // 锁不存在的行：应返回 null 而不是抛参数绑定/实体不存在异常
        KnowledgeBaseEntity found = sessionFactory.withTransaction(session ->
                        knowledgeBaseRepository.findByIdForUpdate(KnowledgeBaseId.of("kb_not_exist_lock_it")))
                .await().indefinitely();

        assertNull(found, "missing id should yield null, not a lock-statement failure");
    }

    @Test
    void concurrentTransactionsSerializeOnRowLock() {
        // 并发互斥冒烟：两个事务先后对同一行加悲观锁，锁序排队各自成功
        KnowledgeBaseEntity kb = KnowledgeBaseEntity.create("lock-it-kb-concurrent", "并发锁回归", "tester")
                .withDirName("lock_it_kb_concurrent");

        String firstName = sessionFactory.withTransaction(s1 ->
                        knowledgeBaseRepository.persist(kb)
                                .chain(persisted -> knowledgeBaseRepository
                                        .findByIdForUpdate(KnowledgeBaseId.of(persisted.getId().value())))
                                .chain(locked -> sessionFactory.withTransaction(s2 ->
                                        knowledgeBaseRepository.findByIdForUpdate(
                                                KnowledgeBaseId.of(locked.getId().value())))
                                        .map(second -> second.getName())))
                .await().indefinitely();

        assertEquals("lock-it-kb-concurrent", firstName);
    }
}
