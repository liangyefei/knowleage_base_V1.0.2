package com.hisilicon.knowledge.platform.knowledge.infrastructure.job;

import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentContentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.service.DocumentContentIndexService;
import io.quarkus.hibernate.reactive.panache.Panache;
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

/**
 * 文档内容抽取回填任务（document_contents 兜底）
 * <p>每小时低频扫描：已发布（PUBLISHED）且 {@code document_contents} 中没有与
 * {@code documents.file_path} 等值的<b>终态行</b>（READY/FAILED/SKIPPED）的文档，逐个补抽取。
 * 覆盖三类场景：</p>
 * <ul>
 *   <li>存量文档（功能上线前已入库）；</li>
 *   <li>上传/覆盖时抽取仍在途（异步滞后窗口，此时 filePath 不等值 → 内容不可搜，
 *       抽取完成后等值自动生效）；</li>
 *   <li>其它触发点遗漏（兜底）。</li>
 * </ul>
 * <p>注意：同一 filePath 的 FAILED 行<b>不重试</b>（等值终态行已存在则不进本任务候选），
 * 避免对加密/损坏文件反复消耗 CPU；文件被替换（新路径）后自然重试。
 * 批量上限 {@code kb.search.backfill-batch-size}（默认 100），逐个串行抽取，单条失败不影响其余。</p>
 *
 * <p><b>多 Pod 行级认领</b>：{@code ConcurrentExecution.SKIP} 仅防同一 JVM 内并发；
 * 多副本部署时各 Pod 的 Scheduler 独立触发，候选列表可能重叠。<b>不使用全局锁</b>——
 * 认领以 document_contents 行的原子 upsert 完成（
 * {@link DocumentContentPanacheRepository#claimPending}，{@code INSERT .. ON CONFLICT DO
 * UPDATE .. WHERE .. RETURNING}）：把行置为 PENDING（等值新路径），更新 0 行即他人已认领/
 * 无需处理，跳过。各 Pod 因此天然分片互斥、<b>并行</b>处理各自的子集，比全局锁更快。</p>
 *
 * <p><b>崩溃自愈</b>：认领后 Pod 宕机会遗留"等值路径 + PENDING"行；认领条件允许
 * {@code extracted_at} 早于 {@code kb.search.backfill-stale-claim-minutes}（默认 60 分钟）
 * 的 PENDING 行被重新认领接管。检索不受影响：内容命中只认 READY，PENDING 行不可搜。</p>
 */
@ApplicationScoped
public class DocumentContentBackfillJob {

    private static final Logger logger = LoggerFactory.getLogger(DocumentContentBackfillJob.class);

    /** 抽取服务（reindex 自带 @WithSession 独立会话） */
    private final DocumentContentIndexService contentIndexService;
    private final DocumentPanacheRepository documentPanacheRepository;
    private final DocumentContentPanacheRepository documentContentPanacheRepository;
    private final int batchSize;
    private final int staleClaimMinutes;

    @Inject
    public DocumentContentBackfillJob(DocumentContentIndexService contentIndexService,
                                      DocumentPanacheRepository documentPanacheRepository,
                                      DocumentContentPanacheRepository documentContentPanacheRepository,
                                      @ConfigProperty(name = "kb.search.backfill-batch-size", defaultValue = "100")
                                      int batchSize,
                                      @ConfigProperty(name = "kb.search.backfill-stale-claim-minutes", defaultValue = "60")
                                      int staleClaimMinutes) {
        this.contentIndexService = contentIndexService;
        this.documentPanacheRepository = documentPanacheRepository;
        this.documentContentPanacheRepository = documentContentPanacheRepository;
        this.batchSize = batchSize;
        this.staleClaimMinutes = staleClaimMinutes;
    }

    /**
     * 执行周期可配置（{@code kb.search.backfill-cron}，默认每小时 45 分，错开业务高峰与回收清理任务）；
     * SKIP 防同 JVM 并发，行级原子认领防跨 Pod 重复（见类注释）。
     */
    @Scheduled(cron = "{kb.search.backfill-cron:0 45 * * * ?}",
            concurrentExecution = ConcurrentExecution.SKIP)
    public Uni<Void> backfill() {
        final long start = System.currentTimeMillis();
        return drain(0)
                .invoke(done -> logger.info("Content extraction backfill completed: this pod processed={}, limit={}, costMs={}",
                        done, batchSize, System.currentTimeMillis() - start))
                .replaceWithVoid()
                .onFailure().invoke(err -> logger.error("Content extraction backfill job execution failed", err));
    }

    /**
     * work-stealing 主循环：查一批候选 → 逐条认领+抽取 → 跑完（其中被其他 Pod 抢走的自然跳过）
     * 再查下一批，直到查空、整轮 0 认领（余量均被他人认领）或达到本 Pod 上限。
     * <p>多 Pod 场景：新鲜 PENDING 行不在候选内，各 Pod 在循环中自然分流到互斥子集；
     * 快照重叠部分的认领冲突只是一条条件 upsert（微秒级），无实际代价。</p>
     *
     * @param processed 本轮已处理数
     * @return 累计处理数
     */
    private Uni<Integer> drain(int processed) {
        if (processed >= batchSize) {
            return Uni.createFrom().item(processed);
        }
        return findCandidates()
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        return Uni.createFrom().item(processed);
                    }
                    // 串行抽取：同一时刻仅一个文件在解析（CPU 密集），单条失败不中断
                    Uni<Integer> chain = Uni.createFrom().item(0);
                    for (DocumentPO doc : candidates) {
                        chain = chain.flatMap(claimedHere -> claimAndReindex(doc)
                                .map(ok -> claimedHere + (ok ? 1 : 0))
                                .onFailure().invoke(err -> logger.warn(
                                        "Content extraction backfill failed for one item: documentId={}, filePath={}, error={}",
                                        doc.getId(), doc.getFilePath(), err.getMessage()))
                                .onFailure().recoverWithItem(claimedHere));
                    }
                    return chain.flatMap(claimedHere -> {
                        int total = processed + claimedHere;
                        // 整批 0 认领：余量全被其他 Pod 认领中 → 等下一轮，避免热循环
                        if (claimedHere == 0) {
                            return Uni.createFrom().item(total);
                        }
                        return drain(total);
                    });
                });
    }

    /**
     * 行级原子认领 + 抽取：认领成功（含无行新建 / 旧路径覆盖 / 超时 PENDING 接管）才抽取。
     *
     * @return true = 本 Pod 认领并完成抽取
     */
    private Uni<Boolean> claimAndReindex(DocumentPO doc) {
        LocalDateTime now = LocalDateTime.now();
        return documentContentPanacheRepository
                .claimPending(doc.getId(), doc.getFilePath(), now, now.minusMinutes(staleClaimMinutes))
                .flatMap(claimed -> claimed
                        ? contentIndexService.reindex(doc.getId(), doc.getFilePath()).map(v -> true)
                        : Uni.createFrom().item(false));
    }

    /**
     * 候选查询：PUBLISHED 且 document_contents 中无与 documents.file_path 等值的
     * <b>终态</b>行（READY/FAILED/SKIPPED）；等值的 PENDING 行视为"认领在途/超时遗留"，
     * 超过 stale-claim-minutes 仍重新进入候选（由认领条件接管）。
     * <p>编程式 withSession（{@code @WithSession} 注解在 backfill 内 this 自调用会被绕过）。
     * count 与 list 串行（同一 Mutiny.Session 上并发实体查询破坏 LoadContexts）。</p>
     */
    private Uni<List<DocumentPO>> findCandidates() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleClaimMinutes);
        return Panache.withSession(() -> {
            String where = "from DocumentPO d where d.status = :status and not exists "
                    + "(select 1 from DocumentContentPO c "
                    + " where c.documentId = d.id and c.filePath = d.filePath"
                    + " and (c.contentStatus <> 'PENDING' or c.extractedAt >= :staleBefore))";
            Map<String, Object> params = Map.of(
                    "status", "PUBLISHED",
                    "staleBefore", staleBefore);
            var query = documentPanacheRepository.find(
                    where + " order by d.id", params);
            return query.count()
                    .flatMap(total -> total == 0
                            ? Uni.createFrom().item(List.<DocumentPO>of())
                            : query.page(0, batchSize).list());
        });
    }
}
