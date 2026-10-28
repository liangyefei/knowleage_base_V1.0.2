package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import io.smallrye.mutiny.Uni;

/**
 * 知识库仓储接口
 */
public interface KnowledgeBaseRepository {

    /**
     * 按 ID 查询知识库
     */
    Uni<KnowledgeBaseEntity> findById(KnowledgeBaseId id);

    /**
     * 按 ID 查询知识库并加行级悲观锁（PG SELECT ... FOR UPDATE），锁持续到当前事务提交/回滚。
     * <p>用途：知识库结构性变更（删除类别/KB）与文档落库（上传 finalize/zip 条目落库）的
     * 跨事务互斥——双方事务开头锁同一 KB 行，锁序一致无死锁；上传事务持锁校验 KB/类别活跃，
     * 删除事务持锁后提交的删除对后续上传立即可见，消除"校验通过→删除提交→文档插入已删目录"竞态。
     * 锁在 DB 层实现，多副本（CCE）天然安全。仅可在事务内调用。</p>
     */
    Uni<KnowledgeBaseEntity> findByIdForUpdate(KnowledgeBaseId id);

    /**
     * 按名称查询知识库（用于唯一性校验）
     */
    Uni<KnowledgeBaseEntity> findByName(String name);

    /**
     * 持久化知识库
     */
    Uni<KnowledgeBaseEntity> persist(KnowledgeBaseEntity entity);

    /**
     * 更新知识库（merge 持久化）
     */
    Uni<KnowledgeBaseEntity> update(KnowledgeBaseEntity entity);

    /**
     * 标记知识库进入模型处理中（processStatus: PENDING → PROCESSING）。
     * <p>由 outbox relay 在 KB_CREATED 消息发送到 Kafka 成功后调用，与 outbox markSent 同事务。
     * {@code where process_status='PENDING'} 守卫：不覆盖终态，PROCESSING 幂等；清空旧失败原因。</p>
     *
     * @param id 知识库 ID
     * @return 完成信号
     */
    Uni<Void> markProcessing(KnowledgeBaseId id);
}
