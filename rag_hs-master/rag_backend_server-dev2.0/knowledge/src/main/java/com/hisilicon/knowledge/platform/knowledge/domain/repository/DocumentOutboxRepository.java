package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务发件箱仓储接口（document_outbox 表）
 * <p>业务事务内写入待发消息；relay 定时任务扫描发送并标记已发。</p>
 */
public interface DocumentOutboxRepository {

    /**
     * 插入待发消息（须在业务事务内调用，与业务数据同事务提交）
     * <p>内部将 {@link KnowledgeEvent} 的字段拆存到表列，
     * {@code payload}（Map）序列化为 JSON 文本存入 {@code payload} 列（null/空 → null）。</p>
     *
     * @param event 知识库领域事件
     * @return 完成信号
     */
    Uni<Void> insert(KnowledgeEvent event);

    /**
     * 查询有 PENDING 消息的知识库 ID 列表（relay 按 KB 分组处理，保序前置步骤）
     *
     * @return 存在 PENDING 消息的 kbId 去重列表
     */
    Uni<List<String>> getDistinctKbIds();

    /**
     * 尝试获取指定知识库的处理锁（PG advisory lock，事务级）
     * <p>同 KB 保序核心：一个 KB 同一时刻只会被一个实例（Pod）获取锁成功，
     * 该实例独占处理此 KB 全部 PENDING 消息（按 created_at 顺序），其他实例自动跳过。
     * 锁为事务级（xact lock），事务提交/回滚时自动释放，实例崩溃后由 PG 回收。</p>
     *
     * @param kbId 知识库 ID
     * @return true=获取成功（调用方应独占处理该 KB）；false=其他实例正在处理
     */
    Uni<Boolean> tryAcquireKbLock(String kbId);

    /**
     * 扫描指定知识库的待发送消息（同 KB 内按 created_at 排序）
     * <p>跳过 {@code next_retry_at > now()} 的行（退避：失败消息未到重试点不扫描；
     * 同 KB 保序——头部未到点则后续即使到点也查不出，整 KB 等待头部到点）。</p>
     *
     * @param kbId  知识库 ID
     * @param limit 批量上限
     * @return 待发送记录（原始 PO，仅内部 relay 使用）
     */
    Uni<List<com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentOutboxPO>> scanPendingByKb(String kbId, int limit);

    /**
     * 标记发送成功
     *
     * @param id 记录 ID
     * @return 完成信号
     */
    Uni<Void> markSent(String id);

    /**
     * 发送失败：retry_count +1 并设置下次重试时间（退避，保持 PENDING）
     * <p>relay 扫描时跳过 {@code next_retry_at > now()} 的行（同 KB 保序：头部未发则后续全部等待）。</p>
     *
     * @param id          记录 ID
     * @param nextRetryAt 下次允许重试的时间（由调用方按 retryCount 退避计算）
     * @return 完成信号
     */
    Uni<Void> markRetry(String id, LocalDateTime nextRetryAt);

    /**
     * 作废指定文档全部未发送消息（PENDING → CANCELLED）
     * <p>生命周期回退（版本覆盖 / 草稿同格式重传）后，旧内容周期的 DOCUMENT_PUBLISHED
     * 事件不应再投递——否则模型处理旧文件后的回调会落在已回退的
     * DRAFT 文档上，产生"草稿但已向量化"的状态污染。须与文档更新同事务调用。</p>
     *
     * @param documentId 文档 ID
     * @return 完成信号
     */
    Uni<Void> cancelPendingByDocument(String documentId);
}
