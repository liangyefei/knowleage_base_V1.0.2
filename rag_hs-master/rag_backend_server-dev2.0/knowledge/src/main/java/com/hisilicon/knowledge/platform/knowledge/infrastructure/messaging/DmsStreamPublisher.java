package com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DMS Kafka 消息发布实现 — 通过 SmallRye Reactive Messaging 发送到
 * {@code kb-document-processing} topic（分区键 = kbId，同 KB 保序）。
 * <p>
 * 仅在 {@code kb.messaging.kafka.enabled=true} 时启用（否则由
 * {@link NoopStreamPublisher} 占位，本地无 Kafka 也可运行）。
 * </p>
 * <p>
 * 消息体为 JSON 信封（见架构文档 §3.9.3.8）：
 * <pre>
 * {
 *   "messageId": "&lt;uuid&gt;",
 *   "eventType": "DOCUMENT_PUBLISHED",
 *   "kbId": "...",
 *   "documentId": "...",
 *   "timestamp": "2026-08-05T10:30:00.000Z",
 *   "payload": { ... }   // KnowledgeEvent.payload，null 时为 {}
 * }
 * </pre>
 * </p>
 * <p>
 * <b>仅由 {@code OutboxRelayJob} 调用</b>发 Kafka：业务层事件统一经
 * {@code outboxRepository.insert(KnowledgeEvent)} 入 outbox（与业务数据同事务提交），
 * 不再直接调用本类 {@code publish}。relay 扫描 PENDING 记录、重建事件后调用本类发送。
 * 发送失败 relay markRetry 保序重发（至少一次投递，模型侧按 documentId 幂等消费兜底）。
 * </p>
 */
@ApplicationScoped
@IfBuildProperty(name = "kb.messaging.kafka.enabled", stringValue = "true")
public class DmsStreamPublisher implements StreamPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DmsStreamPublisher.class);

    private static final DateTimeFormatter ENVELOPE_TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private final MutinyEmitter<String> emitter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DmsStreamPublisher(@Channel("kb-document-processing-out") MutinyEmitter<String> emitter) {
        this.emitter = emitter;
    }

    @Override
    public Uni<Void> publish(KnowledgeEvent event) {
        String messageJson;
        try {
            messageJson = buildEnvelope(event);
        } catch (JsonProcessingException e) {
            // 信封构建失败属编程错误：直接失败（outbox relay 重试，直接调用回滚）
            return Uni.createFrom().failure(new IllegalStateException("构建消息信封失败: " + event, e));
        }
        logger.info("Publish knowledge event to DMS Kafka: eventType={}, kbId={}, documentId={}",
                event.type(), event.kbId(), event.documentId());
        Message<String> message = Message.of(messageJson)
                .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(event.kbId())
                        .build());
        return emitter.sendMessage(message)
                .onFailure().invoke(err -> logger.warn(
                        "DMS Kafka message send failed: eventType={}, kbId={}, documentId={}, error={}",
                        event.type(), event.kbId(), event.documentId(), err.getMessage()));
    }

    private String buildEnvelope(KnowledgeEvent event) throws JsonProcessingException {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("messageId", UUID.randomUUID().toString());
        envelope.put("eventType", event.type().name());
        envelope.put("kbId", event.kbId());
        envelope.put("documentId", event.documentId());
        envelope.put("timestamp", ENVELOPE_TIMESTAMP.format(event.occurredAt().toInstant(ZoneOffset.UTC)));
        envelope.put("payload", event.payload() != null ? event.payload() : Map.of());
        return objectMapper.writeValueAsString(envelope);
    }
}
