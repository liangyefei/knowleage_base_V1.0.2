package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictDetectionClient;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.DocumentEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanMode;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ScanTriggerType;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictScanJobRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.ConflictScanScopeEmptyException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.CategoryPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.CategoryPanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冲突检测命令服务（应用服务层）。
 *
 * <p>新需求：立即检测单选 KB + 多选文件/文件夹；全局串行队列（任务入 QUEUED，
 * 由 {@code ConflictScanQueueJob} 定时调度提交模型端）。后端展开 scope 为文件路径列表，
 * 拆成 ≤50 文件/批，每批一个 job 依次排队。</p>
 */
@ApplicationScoped
public class ConflictScanCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConflictScanCommandService.class);

    /** 模型端单批文件数上限（CONFLICT_MAX_FILES）。 */
    private static final int MAX_FILES_PER_BATCH = 50;
    private static final DateTimeFormatter BATCH_DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 模型端可见的文件路径前缀（对应平台 {@code documents.file_path} 之外的挂载视图）。 */
    private static final String MODEL_FILE_PATH_PREFIX = "/kb-files/";

    private final ConflictScanJobRepository scanJobRepository;
    private final DocumentRepository documentRepository;
    private final CategoryPanacheRepository categoryPanacheRepository;
    private final ConflictDetectionClient detectionClient;

    public ConflictScanCommandService(ConflictScanJobRepository scanJobRepository,
                                      DocumentRepository documentRepository,
                                      CategoryPanacheRepository categoryPanacheRepository,
                                      ConflictDetectionClient detectionClient) {
        this.scanJobRepository = scanJobRepository;
        this.documentRepository = documentRepository;
        this.categoryPanacheRepository = categoryPanacheRepository;
        this.detectionClient = detectionClient;
    }

    /**
     * 发起手动扫描（立即检测）：单 KB + 多选文件/文件夹。展开成文件路径列表、拆批、入队。
     *
     * @return 首批 jobId（前端可拿这个 jobId 轮询进度）；如果拆成多批，其余批次在队列中等待。
     */
    @WithTransaction
    public Uni<StartScanResult> startManualScan(String operatorId, StartConflictScanCommand command) {
        String kbId = command.kbId().trim();
        List<String> documentIds = command.documentIds() == null ? List.of() : command.documentIds();
        List<String> categoryIds = command.categoryIds() == null ? List.of() : command.categoryIds();

        if (documentIds.isEmpty() && categoryIds.isEmpty()) {
            throw new ConflictScanScopeEmptyException("检测范围不能为空：请至少选择一个文件或文件夹");
        }

        // 1. 展开 scope 为 PUBLISHED + COMPLETED 文档集合（去重）
        return resolveFilePaths(kbId, documentIds, categoryIds)
                .chain(paths -> {
                    if (paths.isEmpty()) {
                        logger.warn("Manual conflict scan rejected, no detectable documents in scope: kbId={}, operatorId={}",
                                kbId, operatorId);
                        throw new ConflictScanScopeEmptyException("所选范围内没有可检测的已发布知识片段");
                    }
                    // 2. 拆批
                    List<List<String>> batches = partition(paths, MAX_FILES_PER_BATCH);
                    logger.info("Manual conflict scan accepted: kbId={}, totalFiles={}, batches={}, operatorId={}",
                            kbId, paths.size(), batches.size(), operatorId);

                    // 3. 逐批落 QUEUED job（首批复返回给前端）
                    return persistBatches(kbId, operatorId, batches, documentIds, categoryIds)
                            .map(firstJobId -> new StartScanResult(firstJobId, null, paths.size(), batches.size()));
                });
    }

    /** 展开 scope：documentIds 直接取文件路径；categoryIds 展开含后代后取 PUBLISHED+COMPLETED 文档。 */
    private Uni<List<String>> resolveFilePaths(String kbId, List<String> documentIds, List<String> categoryIds) {
        Set<String> docIds = new LinkedHashSet<>(documentIds);

        Uni<List<CategoryPO>> catsUni = categoryIds.isEmpty()
                ? Uni.createFrom().item(List.of())
                : categoryPanacheRepository.find("kbId = ?1 and status = 1", kbId).list();

        return catsUni
                .chain(cats -> {
                    Set<String> subtreeIds = new HashSet<>();
                    for (String root : categoryIds) {
                        collectSubtree(cats, root, subtreeIds);
                    }
                    if (subtreeIds.isEmpty()) {
                        return Uni.createFrom().item(List.<DocumentEntity>of());
                    }
                    return documentRepository.findByCategoryIdsAndStatusIn(kbId, subtreeIds,
                            List.of(DocumentEntity.STATUS_PUBLISHED));
                })
                .chain(docsByCategory -> {
                    // 合并 category 展开的 docId
                    docsByCategory.forEach(d -> docIds.add(d.getId().value()));
                    if (docIds.isEmpty()) {
                        return Uni.createFrom().item(List.<String>of());
                    }
                    // 统一按 docIds 查完整文档实体（取 filePath）；同时校验 kbId 防越权
                    return documentRepository.findByIds(docIds)
                            .map(all -> all.stream()
                                    .filter(d -> kbId.equals(d.getKbId()))
                                    .filter(d -> DocumentEntity.STATUS_PUBLISHED.equals(d.getStatus()))
                                    .filter(d -> DocumentEntity.PROCESS_COMPLETED.equals(d.getProcessStatus()))
                                    .map(DocumentEntity::getFilePath)
                                    .filter(p -> p != null && !p.isBlank())
                                    .map(this::toModelPath)
                                    .distinct()
                                    .toList());
                });
    }

    private String toModelPath(String relativePath) {
        return MODEL_FILE_PATH_PREFIX + (relativePath.startsWith("/") ? relativePath.substring(1) : relativePath);
    }

    private static void collectSubtree(List<CategoryPO> all, String rootId, Set<String> out) {
        out.add(rootId);
        for (CategoryPO c : all) {
            if (rootId.equals(c.getParentId())) {
                collectSubtree(all, c.getId(), out);
            }
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return batches;
    }

    private Uni<String> persistBatches(String kbId, String operatorId, List<List<String>> batches,
                                          List<String> fileIds, List<String> categoryIds) {
        String scopeNodesJson = buildScopeJson(fileIds, categoryIds);
        final String[] firstJobId = new String[1];
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (int i = 0; i < batches.size(); i++) {
            final int idx = i;
            final List<String> batch = batches.get(i);
            chain = chain.chain(v -> nextBatchNo())
                    .flatMap(batchNo -> {
                        ConflictScanJob job = ConflictScanJob.queue(
                                batchNo, kbId, ScanTriggerType.MANUAL, ScanMode.FULL,
                                scopeNodesJson, null, batch.size(), operatorId);
                        ConflictScanJob withPaths = ConflictScanJob.builder()
                                .id(job.getId()).batchNo(job.getBatchNo()).kbId(job.getKbId())
                                .triggerType(job.getTriggerType()).mode(job.getMode())
                                .scopeNodesJson(job.getScopeNodesJson()).scopeFingerprint(job.getScopeFingerprint())
                                .status(job.getStatus()).totalDocuments(job.getTotalDocuments())
                                .createdBy(job.getCreatedBy()).createdAt(job.getCreatedAt())
                                .updatedAt(job.getUpdatedAt())
                                .filePathsJson(toJsonArray(batch))
                                .build();
                        return scanJobRepository.persist(withPaths)
                                .map(persisted -> {
                                    if (idx == 0) {
                                        firstJobId[0] = persisted.getId().value();
                                    }
                                    return null;
                                });
                    });
        }
        return chain.map(v -> firstJobId[0]);
    }

    private static String buildScopeJson(List<String> documentIds, List<String> categoryIds) {
        StringBuilder sb = new StringBuilder("{\"documentIds\":[");
        for (int i = 0; i < documentIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(documentIds.get(i))).append('"');
        }
        sb.append("],\"categoryIds\":[");
        for (int i = 0; i < categoryIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(categoryIds.get(i))).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Uni<String> nextBatchNo() {
        String day = LocalDateTime.now().format(BATCH_DAY_FMT);
        return scanJobRepository.countByBatchNoPrefix("CHK-" + day + "-")
                .map(count -> String.format("CHK-%s-%02d", day, count + 1));
    }

    /**
     * 发起手动扫描的返回结果。
     */
    public record StartScanResult(String jobId, String batchNo, int totalFiles, int totalBatches) {
    }
}
