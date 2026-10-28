package com.hisilicon.knowledge.platform.knowledge.infrastructure.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging.StreamPublisher;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事务发件箱 relay 任务 — 扫描 PENDING 消息发送到 DMS Kafka
 * <p>
 * <b>同 KB 保序（多实例安全）</b>：每个 tick 先查出有 PENDING 消息的 KB 列表，
 * 对每个 KB 尝试获取 PG 事务级 advisory lock（{@code pg_try_advisory_xact_lock}）。
 * 一个 KB 同一时刻只会被一个实例（Pod）获取锁成功并独占处理该 KB 全部 PENDING 消息
 * （按 {@code created_at} 顺序），其余实例自动跳过——同一 KB 的消息严格按产生顺序发出，
 * 不同 KB 由不同实例并行处理。实例崩溃时锁随事务/连接释放，消息保持 PENDING 由下轮重试
 * （至少一次投递，模型侧按 documentId 幂等消费）。
 * 单实例内 {@code concurrentExecution = SKIP} 防止同 tick 重叠执行。
 * </p>
 * <p>
 * 所有知识库/文档事件（KB_CREATED/UPDATED/DELETED、DOCUMENT_PUBLISHED/ARCHIVED/
 * DELETED）均经 outbox 投递：业务事务内 {@code outboxRepository.insert}
 * 写入 PENDING 记录，本任务扫描按 KB + advisory lock + created_at 保序发送。
 * 统一 outbox 入口消除"直发成功的新消息先于 outbox 积压旧消息到达"的乱序，以及
 * 事务内直发成功但事务回滚产生的 phantom 消息。
 * </p>
 */
@ApplicationScoped
public class OutboxRelayJob {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final DocumentOutboxRepository outboxRepository;
    private final StreamPublisher streamPublisher;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final int batchSize;
    private final long retryBaseDelaySeconds;
    private final long retryMaxDelaySeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 自注入：relay()（@WithSession 只读编排）与 relayForKb()（@WithTransaction）的桥接。
     * 直接 this.relayForKb() 会绕过 {@code @WithTransaction} 拦截器（CDI 代理不生效）。
     */
    @Inject
    OutboxRelayJob self;

    public OutboxRelayJob(DocumentOutboxRepository outboxRepository,
                          StreamPublisher streamPublisher,
                          DocumentRepository documentRepository,
                          KnowledgeBaseRepository knowledgeBaseRepository,
                          @ConfigProperty(name = "kb.outbox.batch-size", defaultValue = "100") int batchSize,
                          @ConfigProperty(name = "kb.outbox.retry-base-delay-seconds", defaultValue = "10")
                          long retryBaseDelaySeconds,
                          @ConfigProperty(name = "kb.outbox.retry-max-delay-seconds", defaultValue = "300")
                          long retryMaxDelaySeconds) {
        this.outboxRepository = outboxRepository;
        this.streamPublisher = streamPublisher;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.batchSize = batchSize;
        this.retryBaseDelaySeconds = retryBaseDelaySeconds;
        this.retryMaxDelaySeconds = retryMaxDelaySeconds;
    }

    /**
     * 扫描所有有 PENDING 消息的 KB，逐 KB 尝试锁并处理
     */
    @Scheduled(every = "${kb.outbox.poll-interval:10s}", concurrentExecution = ConcurrentExecution.SKIP)
    @WithSession
    public Uni<Void> relay() {
        return outboxRepository.getDistinctKbIds()
                .flatMap(this::processKbs)
                .onFailure().invoke(err -> logger.error("outbox relay execution failed", err))
                .replaceWithVoid();
    }

    private Uni<Void> processKbs(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        logger.info("outbox relay scanned {} knowledge bases to process", kbIds.size());
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String kbId : kbIds) {
            chain = chain.flatMap(v -> self.relayForKb(kbId));
        }
        return chain;
    }

    /**
     * 处理单个知识库的全部 PENDING 消息（同 KB 保序：锁内按 created_at 顺序发送）
     * <p>每行：发布 → 成功 markSent / 失败 markRetry（保持 PENDING，下次扫描重试）。
     * 单行失败不阻塞同 KB 其他行。</p>
     */
    @WithTransaction
    public Uni<Void> relayForKb(String kbId) {
        return outboxRepository.tryAcquireKbLock(kbId)
                .flatMap(locked -> {
                    if (!locked) {
                        // 其他实例正在处理此 KB，本实例跳过（同 KB 保序依赖）
                        return Uni.createFrom().voidItem();
                    }
                    return outboxRepository.scanPendingByKb(kbId, batchSize)
                            .flatMap(this::processBatch);
                });
    }

    private Uni<Void> processBatch(List<DocumentOutboxPO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        logger.info("outbox relay processing {} pending messages in the knowledge base", rows.size());
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (DocumentOutboxPO row : rows) {
            chain = chain.flatMap(v -> sendOne(row));
        }
        return chain;
    }

    private Uni<Void> sendOne(DocumentOutboxPO row) {
        var typeOpt = toEventType(row.getEventType());
        if (typeOpt.isEmpty()) {
            // 未知类型永远不会发送成功，直接标记已发送避免死循环（内部写入，正常情况下不会发生）
            logger.error("outbox unknown event type, discarded: id={}, eventType={}", row.getId(), row.getEventType());
            return outboxRepository.markSent(row.getId());
        }
        KnowledgeEvent.EventType type = typeOpt.get();
        // 重建完整事件：透传 categoryId/operatorId/payload；occurredAt 取入队时刻 createdAt，
        // 使信封 timestamp 与 relay 的 created_at 保序一致（反映事件发生顺序而非重放时刻）
        KnowledgeEvent event = new KnowledgeEvent(type, row.getKbId(), row.getCategoryId(),
                row.getDocumentId(), row.getOperatorId(), row.getCreatedAt(),
                deserializePayload(row.getPayload()).orElse(null));
        return streamPublisher.publish(event)
                .flatMap(v -> outboxRepository.markSent(row.getId())
                        .flatMap(ignored -> markProcessing(type, row)))
                .onFailure().recoverWithUni(err -> {
                    LocalDateTime nextRetryAt = computeNextRetry(row.getRetryCount());
                    logger.warn("outbox message send failed, kept for retry (next={}): id={}, eventType={}, documentId={}, retryCount={}, error={}",
                            nextRetryAt, row.getId(), row.getEventType(), row.getDocumentId(), row.getRetryCount(), err.getMessage());
                    return outboxRepository.markRetry(row.getId(), nextRetryAt);
                });
    }

    /**
     * 按事件类型将文档/知识库 processStatus 置为 PROCESSING（Kafka 发送成功后、与 markSent 同事务调用）。
     * <p>仅"触发模型向量化/初始化"的事件置 PROCESSING：
     * <ul>
     *   <li>DOCUMENT_PUBLISHED → 文档 markProcessing</li>
     *   <li>KB_CREATED → 知识库 markProcessing</li>
     * </ul>
     * 移除/更新类事件（ARCHIVED/DELETED/KB_UPDATED/KB_DELETED）不动 processStatus。
     * where PENDING/FAILED 守卫由 repository 保证：发送成功即开启新处理周期，上一周期遗留的
     * FAILED 随新周期清掉（否则新周期 COMPLETED 回调被 processCallback 终态守卫当过期跳过）；
     * 不覆盖 COMPLETED（回调先到置终态的竞态保护）、PROCESSING 幂等。</p>
     */
    private Uni<Void> markProcessing(KnowledgeEvent.EventType type, DocumentOutboxPO row) {
        return switch (type) {
            case DOCUMENT_PUBLISHED ->
                    documentRepository.markProcessing(DocumentId.of(row.getDocumentId()));
            case KB_CREATED ->
                    knowledgeBaseRepository.markProcessing(KnowledgeBaseId.of(row.getKbId()));
            default -> Uni.createFrom().voidItem();
        };
    }

    /**
     * 反序列化 outbox 存储的 payload JSON（Text → Map）。null/空 → 空 {@code Optional}（信封序列化为 {}）。
     * <p>反序列化失败不阻断发送：丢弃 payload 并继续发，避免毒丸行卡住同 KB 后续消息。</p>
     */
    private Optional<Map<String, Object>> deserializePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, new TypeReference<>() {}));
        } catch (JsonProcessingException e) {
            logger.warn("outbox payload deserialization failed, payload dropped and sending continues: payload={}", payload, e);
            return Optional.empty();
        }
    }

    /**
     * 按 retryCount 计算退避后的下次重试时间：delay = min(base << retryCount, max)。
     * <p>retryCount=0（首次失败）→ base（与 poll-interval 相当，快速重试瞬时故障）；
     * 之后指数倍增，封顶 max（默认 5min）。避免故障期 relay 每个 tick 狂试头部失败消息
     * 给 Kafka 压力、且同 KB 后续消息被头部阻塞。</p>
     */
    private LocalDateTime computeNextRetry(Integer retryCount) {
        long count = retryCount == null ? 0L : retryCount.longValue();
        long delay = retryBaseDelaySeconds;
        for (long i = 0; i < count && delay < retryMaxDelaySeconds; i++) {
            delay *= 2;
        }
        return LocalDateTime.now().plusSeconds(Math.min(delay, retryMaxDelaySeconds));
    }

    private Optional<KnowledgeEvent.EventType> toEventType(String name) {
        try {
            return Optional.of(KnowledgeEvent.EventType.valueOf(name));
        } catch (IllegalArgumentException e) {
            logger.error("outbox unknown event type, skip sending: {}", name);
            return Optional.empty();
        }
    }
}
