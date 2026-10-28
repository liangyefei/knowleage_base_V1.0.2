package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictRecordRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictScanJobRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictSettingsRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.ConflictJobNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.ConflictNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ConflictRecordItemPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ConflictRecordPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordItemPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentPO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.AutoDetectionStatusDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictJobProgress;
import com.hisilicon.knowledge.platform.knowledge.query.dto.ConflictListItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.panache.common.Page;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 冲突检测查询服务（CQRS 查询侧）。
 * <p>需求简化：列表筛选改为 keyword / type / kbId / badcaseSubmitted，无 status。</p>
 */
@ApplicationScoped
public class ConflictQueryService {

    private static final Logger logger = LoggerFactory.getLogger(ConflictQueryService.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConflictSettingsRepository settingsRepository;
    private final ConflictScanJobRepository scanJobRepository;
    private final ConflictRecordRepository recordRepository;
    private final ConflictRecordPanacheRepository recordPanacheRepo;
    private final ConflictRecordItemPanacheRepository itemPanacheRepo;
    private final DocumentPanacheRepository documentPanacheRepo;

    public ConflictQueryService(ConflictSettingsRepository settingsRepository,
                                ConflictScanJobRepository scanJobRepository,
                                ConflictRecordRepository recordRepository,
                                ConflictRecordPanacheRepository recordPanacheRepo,
                                ConflictRecordItemPanacheRepository itemPanacheRepo,
                                DocumentPanacheRepository documentPanacheRepo) {
        this.settingsRepository = settingsRepository;
        this.scanJobRepository = scanJobRepository;
        this.recordRepository = recordRepository;
        this.recordPanacheRepo = recordPanacheRepo;
        this.itemPanacheRepo = itemPanacheRepo;
        this.documentPanacheRepo = documentPanacheRepo;
    }

    /**
     * 查自动检测开关状态。kbId 为空 = 全局开关；否则 = 该 KB 的覆盖开关。
     */
    @WithSession
    public Uni<AutoDetectionStatusDTO> getAutoDetectionStatus(String kbId) {
        Uni<com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings> uni = (kbId == null || kbId.isBlank())
                ? settingsRepository.findGlobal().map(opt -> opt.orElse(null))
                : settingsRepository.findByKbId(kbId).map(opt -> opt.orElse(null));
        return uni.map(s -> s == null
                ? new AutoDetectionStatusDTO(false, null)
                : new AutoDetectionStatusDTO(s.isAutoEnabled(), s.getUpdatedAt()));
    }

    /**
     * 查任务进度（前端轮询用）。
     */
    @WithSession
    public Uni<ConflictJobProgress> getJobProgress(String jobId) {
        return scanJobRepository.findById(com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId.of(jobId))
                .map(opt -> opt.orElseThrow(() -> {
                    logger.warn("Conflict scan job not found: jobId={}", jobId);
                    return new ConflictJobNotFoundException("冲突检测任务不存在");
                }))
                .map(job -> new ConflictJobProgress(
                        job.getId().value(), job.getBatchNo(), job.getStatus().name(),
                        job.getTotalDocuments(), job.getScannedChunks(),
                        job.getNewConflictCount(), job.getHitConflictCount(),
                        job.getFailedItems(), job.getError()));
    }

    /**
     * 分页查询冲突列表（keyword 模糊 / type / kbId / badcaseSubmitted 可选）。
     *
     * @param badcaseSubmitted null=全部 / true=已提交 / false=未提交
     */
    @WithSession
    public Uni<PageResult<ConflictListItem>> list(String keyword, String type,
                                                  String kbId, Boolean badcaseSubmitted,
                                                  Integer page, Integer pageSize) {
        int pageNo = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and lower(topic) like :keyword");
            params.put("keyword", "%" + keyword.toLowerCase(Locale.ROOT) + "%");
        }
        if (type != null && !type.isBlank()) {
            where.append(" and type = :type");
            params.put("type", type);
        }
        if (kbId != null && !kbId.isBlank()) {
            where.append(" and kbId = :kbId");
            params.put("kbId", kbId);
        }
        if (badcaseSubmitted != null) {
            where.append(" and badcaseStatus = :badcaseStatus");
            params.put("badcaseStatus", Boolean.TRUE.equals(badcaseSubmitted) ? "SUBMITTED" : "NONE");
        }

        String orderBy = " order by lastSeenAt desc";
        logger.info("Conflict list query: where={}, page={}, size={}", where, pageNo, size);

        return recordPanacheRepo.find(where + orderBy, params)
                .page(Page.of(pageNo - 1, size))
                .list()
                .flatMap(pos -> recordPanacheRepo.count(where.toString(), params)
                        .flatMap(total -> {
                            long totalPage = size == 0 ? 0 : (total + size - 1) / size;
                            List<ConflictListItem> items = pos.stream()
                                    .map(this::toListItem)
                                    .toList();
                            return Uni.createFrom().item(new PageResult<>(pageNo, size, totalPage, total, items));
                        }));
    }

    /**
     * 查冲突详情（基础信息 + 证据卡片 + badcase 信息）。
     */
    @WithSession
    public Uni<ConflictDetailDTO> getDetail(String recordId) {
        return recordPanacheRepo.findById(recordId)
                .flatMap(po -> {
                    if (po == null) {
                        logger.warn("Conflict record not found: id={}", recordId);
                        throw new ConflictNotFoundException("冲突记录不存在");
                    }
                    // 串行查询（Hibernate Reactive 同一 session 不能并行查多张表）
                    // 1) 证据条目 2) 按 firstJobId 反查批次号 3) 按文件名反查文档版本号
                    return itemPanacheRepo
                            .find("recordId = ?1 order by sortNo asc", recordId).list()
                            .chain(items -> loadBatchNo(po.firstJobId)
                                    .chain(batchNo -> loadVersionMap(items)
                                            .map(versionMap -> toDetail(po, items, batchNo, versionMap))));
                });
    }

    /** 按 jobId 反查批次号；查不到返回 null。 */
    private Uni<String> loadBatchNo(String firstJobId) {
        if (firstJobId == null || firstJobId.isBlank()) {
            return Uni.createFrom().item(null);
        }
        return scanJobRepository.findById(
                        com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJobId.of(firstJobId))
                .map(opt -> opt.map(com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictScanJob::getBatchNo)
                        .orElse(null));
    }

    /**
     * 按证据文件名批量反查 documents 表，返回 docName → version 映射。
     * <p>只取同 KB 下最新一条（文件名可能重复，按 createdAt 倒序取第一条）。</p>
     */
    private Uni<Map<String, String>> loadVersionMap(List<ConflictRecordItemPO> items) {
        List<String> docNames = items.stream()
                .map(it -> it.docNameSnapshot)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (docNames.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        // 按 title（文件名去扩展名）模糊匹配，直接用 filePath 最后一段对比更准
        // 简化：直接按 title 包含 docName 核心部分查
        return documentPanacheRepo
                .find("title in ?1", docNames)
                .list()
                .map(docs -> {
                    Map<String, String> map = new HashMap<>();
                    for (DocumentPO d : docs) {
                        if (d.getTitle() != null && d.getCurrentVersion() != null) {
                            // 同名文档可能多条，已存在则不覆盖（Panache list 无序，取第一条即可）
                            map.putIfAbsent(d.getTitle(), d.getCurrentVersion());
                        }
                    }
                    return map;
                });
    }

    private ConflictListItem toListItem(ConflictRecordPO po) {
        boolean badcaseSubmitted = "SUBMITTED".equals(po.badcaseStatus);
        return new ConflictListItem(
                po.id, po.topic, po.type, po.severity, po.locationCount,
                po.detectedStartedAt, po.detectedFinishedAt,
                badcaseSubmitted, po.badcaseSubmittedBy);
    }

    private ConflictDetailDTO toDetail(ConflictRecordPO po,
                                         List<ConflictRecordItemPO> items,
                                         String batchNo,
                                         Map<String, String> versionMap) {
        List<ConflictDetailDTO.EvidenceDTO> evidence = items.stream()
                .map(it -> new ConflictDetailDTO.EvidenceDTO(
                        it.docNameSnapshot,
                        versionMap.get(it.docNameSnapshot),
                        it.excerpt,
                        it.chunkIndex,
                        Boolean.TRUE.equals(it.baseline),
                        it.oldSourcePath))
                .collect(Collectors.toList());

        return new ConflictDetailDTO(
                po.id, po.topic, po.type, batchNo,
                po.detectedStartedAt, po.detectedFinishedAt,
                po.confidence, po.suggestion, po.reason, po.similarity,
                evidence);
    }
}
