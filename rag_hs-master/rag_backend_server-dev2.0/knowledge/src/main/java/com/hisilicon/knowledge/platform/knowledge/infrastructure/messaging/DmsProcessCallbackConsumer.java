package com.hisilicon.knowledge.platform.knowledge.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.knowledge.command.DocumentCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.KnowledgeBaseCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.ProcessCallbackCommand;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 模型处理结果消费器
 * <p>
 * 模型服务通过 DMS Kafka 发送处理结果回调，本应用作为消费者消费消息并更新文档/知识库的
 * 处理状态。仅在 {@code kb.messaging.kafka.enabled=true} 时启用。
 * </p>
 * <p>
 * <b>环境隔离（2026-10-13 故障后固化）</b>：topic / 消费组 / 死信主题三件套按环境显式配对
 * （{@code <base>[-suffix]} / {@code <base>-consumer[-suffix]} / {@code <base>-dlq[-suffix]}，
 * 命名后缀在最后，与 DMS 控制台逐字一致），且**实际生效的是 CCE 挂载的配置文件**——
 * 仓库 application.yml 只是 dev 默认值与模板，改它不影响任何环境。
 * 绝不允许两个环境落进同一个消费组——那会让 A 环境拿走 B 环境的分区与位点，
 * B 的 pod 成员在线、探针全绿、却永不消费（本次故障的根因）。
 * </p>
 * <p>
 * {@link #onProcessed(String)} 是 SmallRye Reactive Messaging 的 {@code @Incoming}
 * 入口（通道 {@code kb-document-processed-in}），返回 {@code Uni} 驱动消息确认：
 * <ul>
 *   <li>成功完成（含幂等跳过、资源不存在跳过）→ ACK</li>
 *   <li>非法信封 / 未知 eventType / 必填字段缺失 → <b>ERROR 日志 + ACK</b>（这类消息永远无法被
 *       应用；也不让 nack 有机会因"死信写入失败"而终止整条消费通道）</li>
 *   <li>DB 等<b>瞬时故障</b> → 有界重试（默认 3 次尝试、退避 200ms，配置
 *       {@code kb.messaging.callback.retry.max-attempts} / {@code .backoff-ms}），
 *       每次重试都是独立事务；重试耗尽才 nack → 死信（消息保留供人工对账重放）</li>
 *   <li>业务/参数类<b>不可恢复</b>错误（{@link BusinessException} / {@link IllegalArgumentException}）
 *       → 不重试，直接 nack → 死信</li>
 * </ul>
 * </p>
 * <p>
 * 入站消息为 JSON 信封（见 kafka-structure.md §3 + 架构文档 §3.9.3.8）：
 * <pre>
 * {
 *   "messageId": "...",                       // 模型新生成，去重主键
 *   "eventType": "PROCESSING_COMPLETED" | "PROCESSING_FAILED",
 *   "kbId": "...",
 *   "documentId": "..." | null,               // KB 级回调为 null
 *   "timestamp": "...",
 *   "payload": { "processedAt": "...", "chunkCount": 45 }            // COMPLETED
 *             | { "failedAt": "...", "errorCode": "500", "error": "..." }  // FAILED
 * }
 * </pre>
 * 按 {@code documentId} 是否为空分流：非空 → 文档级（{@link DocumentCommandService#processCallback}）；
 * 空 → 知识库级（{@link KnowledgeBaseCommandService#processCallback}）。两级均做
 * 状态机守卫 + messageId 幂等去重，详见对应服务方法。文档级 FAILED（向量化失败）回调
 * 同时将 PUBLISHED 文档生命周期打回 DRAFT（决策在服务层，见
 * {@code DocumentCommandService#resolveLifecycleStatus}）。
 * </p>
 * <p>
 * 冲突检测结果回传已改为平台主动轮询模型端 {@code GET /tasks/{id}}（见 ConflictScanQueueJob），
 * 不再消费 Kafka 上的 CONFLICT_SCAN_* 事件；本类只处理文档/知识库的 PROCESSING_* 事件。
 * </p>
 */
@ApplicationScoped
@IfBuildProperty(name = "kb.messaging.kafka.enabled", stringValue = "true")
public class DmsProcessCallbackConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DmsProcessCallbackConsumer.class);

    private static final String EVENT_COMPLETED = "PROCESSING_COMPLETED";
    private static final String EVENT_FAILED = "PROCESSING_FAILED";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentCommandService documentCommandService;
    private final KnowledgeBaseCommandService knowledgeBaseCommandService;

    /** 瞬时故障最大尝试次数（含首次）：默认 3 次、退避 200ms，总耗时秒级，远小于 max.poll.interval.ms */
    private final int retryMaxAttempts;
    /** 重试退避基数（毫秒）：第 n 次重试等待 base × 2^(n-1)，由 Mutiny withBackOff 计算 */
    private final long retryBackoffMs;

    @Inject
    public DmsProcessCallbackConsumer(DocumentCommandService documentCommandService,
                                      KnowledgeBaseCommandService knowledgeBaseCommandService,
                                      @ConfigProperty(name = "kb.messaging.callback.retry.max-attempts",
                                              defaultValue = "3") int retryMaxAttempts,
                                      @ConfigProperty(name = "kb.messaging.callback.retry.backoff-ms",
                                              defaultValue = "200") long retryBackoffMs) {
        this.documentCommandService = documentCommandService;
        this.knowledgeBaseCommandService = knowledgeBaseCommandService;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(1L, retryBackoffMs);
    }

    /**
     * DMS Kafka 入站消息入口（通道 kb-document-processed-in，topic 由环境决定）
     *
     * @param messageJson Kafka 消息体（JSON 信封）
     * @return 处理完成信号（成功/脏消息 → ACK；瞬时故障重试耗尽、不可恢复错误 → nack → DLQ）
     */
    @Incoming("kb-document-processed-in")
    public Uni<Void> onProcessed(String messageJson) {
        return consume(messageJson);
    }

    /**
     * 消费单条处理结果消息（解析 + 路由 + 派发；也供单元测试直接调用）
     * <p>
     * 非法信封（解析失败 / 未知 eventType / 缺 messageId / 缺 kbId）→ ERROR 日志 + <b>ACK</b>：
     * 这类消息永远不可能被应用，而 nack 一旦遇到"死信写入失败"就会终止整条消费通道
     * （该 Pod 此后永不消费、无日志、探针全绿）。原始报文完整打印备查。
     * </p>
     *
     * @param messageJson DMS 消息体（JSON 信封）
     * @return 处理完成信号；瞬时故障重试耗尽后失败（→ nack → DLQ）
     */
    public Uni<Void> consume(String messageJson) {
        ProcessCallbackCommand cmd;
        try {
            cmd = parse(messageJson);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid callback message, ACKed and discarded (not sent to DLQ): error={}, raw={}", e.getMessage(),
                    abbreviate(messageJson));
            return Uni.createFrom().voidItem();
        }
        return dispatch(cmd)
                // 瞬时故障有界重试：每次重试都会重新进入 @WithTransaction 方法 → 新事务，
                // 失败事务的回滚不会污染下一次尝试（消息本身按 messageId 幂等，重复应用安全）。
                .onFailure(DmsProcessCallbackConsumer::isTransient)
                .retry().withBackOff(Duration.ofMillis(retryBackoffMs)).atMost(retryMaxAttempts - 1L)
                .onFailure().invoke(err -> logger.error(
                        "Callback processing failed, moved to DLQ (manual reconciliation and replay required): messageId={}, documentId={}, eventType={}, "
                                + "transient={}, attempts={}",
                        cmd.messageId(), cmd.documentId(), cmd.eventType(), isTransient(err), retryMaxAttempts, err));
    }

    /** 按 documentId 是否为空分流派发（文档级 / KB 级） */
    private Uni<Void> dispatch(ProcessCallbackCommand cmd) {
        boolean failed = EVENT_FAILED.equals(cmd.eventType());
        if (cmd.kbLevel()) {
            if (failed) {
                logger.info("Consuming model processing result (KB level): messageId={}, kbId={}, eventType={}, "
                                + "failedAt={}, errorCode={}, error={}, totalDuration={}, callbackedAt={}",
                        cmd.messageId(), cmd.kbId(), cmd.eventType(),
                        cmd.processedAt(), cmd.errorCode(), cmd.error(), cmd.totalDuration(), cmd.callbackedAt());
            } else {
                logger.info("Consuming model processing result (KB level): messageId={}, kbId={}, eventType={}, "
                                + "processedAt={}, chunkCount={}, totalDuration={}, callbackedAt={}",
                        cmd.messageId(), cmd.kbId(), cmd.eventType(),
                        cmd.processedAt(), cmd.chunkCount(), cmd.totalDuration(), cmd.callbackedAt());
            }
            return knowledgeBaseCommandService.processCallback(cmd, "zh-CN");
        }
        if (failed) {
            logger.info("Consuming model processing result (document level): messageId={}, documentId={}, eventType={}, "
                            + "failedAt={}, errorCode={}, error={}, totalDuration={}, callbackedAt={}",
                    cmd.messageId(), cmd.documentId(), cmd.eventType(),
                    cmd.processedAt(), cmd.errorCode(), cmd.error(), cmd.totalDuration(), cmd.callbackedAt());
        } else {
            logger.info("Consuming model processing result (document level): messageId={}, documentId={}, eventType={}, "
                            + "processedAt={}, chunkCount={}, totalDuration={}, callbackedAt={}",
                    cmd.messageId(), cmd.documentId(), cmd.eventType(),
                    cmd.processedAt(), cmd.chunkCount(), cmd.totalDuration(), cmd.callbackedAt());
        }
        return documentCommandService.processCallback(cmd, "zh-CN");
    }

    /**
     * 是否属于"重试有意义"的瞬时故障。
     * <p>业务/参数类错误（{@link BusinessException}、{@link IllegalArgumentException}）重试无意义 → 直接进死信；
     * 其余（DB 连接、超时、死锁、序列化失败、以及未预期的运行时异常）一律按瞬时处理——
     * 宁可多试两次，也不要因为一条瞬时抖动永久丢掉回调（文档会永远停在 PROCESSING）。
     * 判定沿 cause 链向上最多 5 层，兼容被包装过的异常。</p>
     */
    private static boolean isTransient(Throwable err) {
        Throwable current = err;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current instanceof BusinessException || current instanceof IllegalArgumentException) {
                return false;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return true;
    }

    /** 日志用报文摘要（超长截断，避免脏消息把日志打爆） */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 1000 ? raw : raw.substring(0, 1000) + "...(截断, 共 " + raw.length() + " 字符)";
    }

    /**
     * 解析信封为 {@link ProcessCallbackCommand}。非法信封抛 {@link IllegalArgumentException}。
     */
    private ProcessCallbackCommand parse(String messageJson) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(messageJson);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("非法处理结果消息(解析失败): " + messageJson, e);
        }

        // 用 asText(null)：JSON null / 缺失均返回 null，不依赖 NullNode.asText() 的版本差异
        String messageId = root.path("messageId").asText(null);
        String kbId = root.path("kbId").asText(null);
        String documentId = root.path("documentId").asText(null);
        String eventType = root.path("eventType").asText(null);

        // 严格匹配两个回调事件类型，移除 startsWith 兜底（防拼错被误判为 FAILED）
        if (!EVENT_COMPLETED.equals(eventType) && !EVENT_FAILED.equals(eventType)) {
            throw new IllegalArgumentException("非法处理结果消息(未知 eventType): " + messageJson);
        }
        // messageId 必填（去重主键）
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("非法处理结果消息(缺少 messageId): " + messageJson);
        }
        // KB 级回调（documentId 为空）必须带 kbId；文档级回调必须带 documentId
        boolean kbLevel = (documentId == null || documentId.isBlank());
        if (kbLevel) {
            if (kbId == null || kbId.isBlank()) {
                throw new IllegalArgumentException("非法处理结果消息(KB级缺少 kbId): " + messageJson);
            }
        } else if (kbId == null || kbId.isBlank()) {
            // 文档级也应透传 kbId（便于审计/路由），缺失视为非法
            throw new IllegalArgumentException("非法处理结果消息(文档级缺少 kbId): " + messageJson);
        } else {
            // 文档级且 kbId 完整：校验通过，继续解析
            logger.debug("Callback message validated: messageId={}, kbId={}, documentId={}",
                    messageId, kbId, documentId);
        }

        JsonNode payload = root.path("payload");
        boolean completed = EVENT_COMPLETED.equals(eventType);
        String error = payload.path("error").asText(null);
        String errorCode = payload.path("errorCode").asText(null);
        // chunkCount：COMPLETED 时回传；KB 级为 0；FAILED 时缺失→0（服务侧 FAILED 忽略）
        int chunkCount = payload.path("chunkCount").asInt(0);
        // 处理时间：COMPLETED 取 processedAt，FAILED 取 failedAt；解析失败置空（服务侧兜底 now()）
        LocalDateTime processedAt = parseTime(completed
                ? payload.path("processedAt").asText(null)
                : payload.path("failedAt").asText(null)).orElse(null);
        // 透传字段：原样字符串，不解析不转换（缺失/JSON null → null）
        String totalDuration = payload.path("totalDuration").asText(null);
        String callbackedAt = payload.path("callbackedAt").asText(null);

        return new ProcessCallbackCommand(messageId, kbId, documentId, eventType,
                error, errorCode, chunkCount, processedAt, totalDuration, callbackedAt);
    }

    /** 解析 ISO-8601 时间（带 Z/offset），失败返回空 {@code Optional}（不因时间字段失败整条消息） */
    private static Optional<LocalDateTime> parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(iso).toLocalDateTime());
        } catch (DateTimeParseException e) {
            logger.warn("Callback time field parsing failed, defaulting to null: {}", iso, e);
            return Optional.empty();
        }
    }
}
