package com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging;

import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DMS Kafka 消息发布占位实现 — 空操作
 * <p>
 * 默认实现（{@code kb.messaging.kafka.enabled} 未配置或为 false 时启用）：
 * publish 直接返回成功（丢弃消息），保证无 Kafka 环境下业务链路可用。
 * 配置 {@code kb.messaging.kafka.enabled=true} 后由 {@link DmsStreamPublisher} 替换。
 * </p>
 */
@ApplicationScoped
// enableIfMissing=true：kb.messaging.kafka.enabled 未配置时启用（默认占位）；=true 时禁用（切换 DmsStreamPublisher）
@UnlessBuildProperty(name = "kb.messaging.kafka.enabled", stringValue = "true", enableIfMissing = true)
public class NoopStreamPublisher implements StreamPublisher {

    private static final Logger logger = LoggerFactory.getLogger(NoopStreamPublisher.class);

    @Override
    public Uni<Void> publish(KnowledgeEvent event) {
        logger.debug("Publish knowledge event (no-op implementation, message discarded): {}", event);
        return Uni.createFrom().voidItem();
    }
}
