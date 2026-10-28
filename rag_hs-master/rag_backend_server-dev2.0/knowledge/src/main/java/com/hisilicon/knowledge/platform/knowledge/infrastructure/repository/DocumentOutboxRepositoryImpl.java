package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 事务发件箱仓储实现
 * <p>scanPending 使用 {@code FOR UPDATE SKIP LOCKED}：多实例（CCE 多副本）下
 * 同一行只会被一个实例锁住处理，其余实例自动跳过，无需分布式锁。</p>
 */
@ApplicationScoped
public class DocumentOutboxRepositoryImpl implements DocumentOutboxRepository {

    private final DocumentOutboxPanacheRepository panacheRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentOutboxRepositoryImpl(DocumentOutboxPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<Void> insert(KnowledgeEvent event) {
        DocumentOutboxPO po = new DocumentOutboxPO();
        po.setId(UUID.randomUUID().toString());
        po.setEventType(event.type().name());
        po.setKbId(event.kbId());
        po.setCategoryId(event.categoryId());
        po.setDocumentId(event.documentId());
        po.setOperatorId(event.operatorId());
        po.setPayload(serializePayload(event.payload()).orElse(null));
        po.setStatus(DocumentOutboxPO.STATUS_PENDING);
        po.setRetryCount(0);
        po.setCreatedAt(LocalDateTime.now());
        return panacheRepo.persist(po).replaceWithVoid();
    }

    /**
     * 序列化 payload（Map → JSON 文本）。null 或空 Map → 空 {@code Optional}（relay 重建时
     * payload=null，信封序列化为 {@code {}}，与无载荷语义一致）。
     */
    private Optional<String> serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // payload 为业务构建的简单 Map，序列化失败属编程错误，直接失败
            throw new IllegalStateException("outbox payload 序列化失败: " + payload, e);
        }
    }

    @Override
    public Uni<List<String>> getDistinctKbIds() {
        return panacheRepo.getSession().flatMap(session -> session
                .createNativeQuery("""
                        select distinct kb_id from document_outbox
                        where status = 'PENDING' and kb_id is not null
                        """)
                .getResultList()
                .map(list -> list.stream()
                        .map(Object::toString)
                        .toList()));
    }

    @Override
    public Uni<Boolean> tryAcquireKbLock(String kbId) {
        // PG 事务级 advisory lock：同一键同一时刻仅一个事务能获取；提交/回滚自动释放。
        // 锁键 = hashtext('outbox_kb:' || kbId)，前缀隔离避免与其他业务 advisory lock 冲突。
        return panacheRepo.getSession().flatMap(session -> session
                .createNativeQuery("""
                        select pg_try_advisory_xact_lock(hashtext('outbox_kb:' || ?1))
                        """)
                .setParameter(1, kbId)
                .getSingleResult()
                .map(v -> Boolean.TRUE.equals(v)));
    }

    @Override
    public Uni<List<DocumentOutboxPO>> scanPendingByKb(String kbId, int limit) {
        return panacheRepo.getSession().flatMap(session -> session
                .createNativeQuery("""
                        select * from document_outbox
                        where status = 'PENDING' and kb_id = ?1
                          and (next_retry_at is null or next_retry_at <= now())
                        order by created_at
                        limit ?2
                        """, DocumentOutboxPO.class)
                .setParameter(1, kbId)
                .setParameter(2, limit)
                .getResultList());
    }

    @Override
    public Uni<Void> markSent(String id) {
        return panacheRepo.update("status = ?1, sentAt = ?2 where id = ?3",
                DocumentOutboxPO.STATUS_SENT, LocalDateTime.now(), id).replaceWithVoid();
    }

    /**
     * 发送失败：retry_count +1 并设置下次重试时间（退避，保持 PENDING）
     */
    @Override
    public Uni<Void> markRetry(String id, LocalDateTime nextRetryAt) {
        return panacheRepo.update("retryCount = retryCount + 1, nextRetryAt = ?2 where id = ?1",
                id, nextRetryAt).replaceWithVoid();
    }

    /**
     * 作废指定文档全部未发送消息（PENDING → CANCELLED）
     * <p>生命周期回退（版本覆盖/草稿重传）后旧内容周期的事件不再投递；
     * 与文档更新同事务调用，回滚时一并回滚。</p>
     */
    @Override
    public Uni<Void> cancelPendingByDocument(String documentId) {
        return panacheRepo.update("status = ?1 where documentId = ?2 and status = ?3",
                DocumentOutboxPO.STATUS_CANCELLED, documentId, DocumentOutboxPO.STATUS_PENDING)
                .replaceWithVoid();
    }
}
