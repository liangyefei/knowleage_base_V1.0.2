package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseProblemType;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseSource;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictBadcaseStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecord;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictRecordId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictType;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.BadcaseTicketRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictRecordRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.ConflictSettingsRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.ConflictRecordItemPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.ConflictRecordItemPO;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.panache.common.Sort;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings;
import com.hisilicon.knowledge.platform.knowledge.exception.ConflictBadcaseDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.ConflictNotFoundException;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 冲突记录命令服务（应用服务层）：自动开关读写 + Badcase 提报。
 * <p>需求简化：无状态流转（resolve/ignore），无过期任务。</p>
 */
@ApplicationScoped
public class ConflictRecordCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConflictRecordCommandService.class);

    private final ConflictRecordRepository recordRepository;
    private final BadcaseTicketRepository badcaseTicketRepository;
    private final ConflictSettingsRepository settingsRepository;
    private final ConflictRecordItemPanacheRepository itemRepo;

    public ConflictRecordCommandService(ConflictRecordRepository recordRepository,
                                        BadcaseTicketRepository badcaseTicketRepository,
                                        ConflictSettingsRepository settingsRepository,
                                        ConflictRecordItemPanacheRepository itemRepo) {
        this.recordRepository = recordRepository;
        this.badcaseTicketRepository = badcaseTicketRepository;
        this.settingsRepository = settingsRepository;
        this.itemRepo = itemRepo;
    }

    /**
     * 切换自动检测开关。
     *
     * @param operatorId 操作员账号
     * @param enabled    是否开启
     * @return 更新后的设置
     */
    @WithTransaction
    public Uni<ConflictSettings> updateAutoDetection(String operatorId, boolean enabled) {
        return settingsRepository.findGlobal()
                .chain(opt -> {
                    ConflictSettings settings = opt
                            .map(s -> s.toggle(enabled, operatorId))
                            .orElseGet(() -> ConflictSettings.globalDefault(enabled).toggle(enabled, operatorId));
                    logger.info("Auto conflict detection switched: enabled={}, operatorId={}", enabled, operatorId);
                    return settingsRepository.save(settings);
                });
    }

    /**
     * 读写指定 KB 的覆盖开关（upsert）。
     */
    @WithTransaction
    public Uni<ConflictSettings> updateKbAutoDetection(String kbId, String operatorId, boolean enabled) {
        return settingsRepository.findByKbId(kbId)
                .chain(opt -> {
                    ConflictSettings settings = opt
                            .map(s -> s.toggle(enabled, operatorId))
                            .orElseGet(() -> ConflictSettings.forKb(kbId, enabled, operatorId));
                    logger.info("KB auto conflict detection switched: kbId={}, enabled={}, operatorId={}",
                            kbId, enabled, operatorId);
                    return settingsRepository.save(settings);
                });
    }

    /**
     * 提交 Badcase 工单（写入主表 badcase_tickets，回写 conflict_records 状态）。
     *
     * @return 工单号
     */
    @WithTransaction
    public Uni<String> submitBadcase(String operatorId, SubmitConflictBadcaseCommand command) {
        return findExisting(command.recordId())
                .chain(record -> {
                    if (record.getBadcaseStatus() == ConflictBadcaseStatus.SUBMITTED) {
                        logger.warn("Badcase submit rejected, already submitted: recordId={}, operatorId={}",
                                command.recordId(), operatorId);
                        throw new ConflictBadcaseDuplicateException("该冲突已提交 Badcase 工单");
                    }
                    // 查出证据列表（按 sortNo 排序）
                    return itemRepo.find("recordId", Sort.ascending("sortNo"), record.getId().value())
                            .list()
                            .chain(items -> {
                                // 构造 evidence 快照列表
                                List<Map<String, Object>> evidence = items.stream().map(po -> {
                                    java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
                                    e.put("documentId", po.documentId);
                                    e.put("docName", po.docNameSnapshot);
                                    e.put("version", po.versionSnapshot);
                                    e.put("excerpt", po.excerpt != null ? po.excerpt
                                            : (po.newTextExcerpt != null ? po.newTextExcerpt : po.oldTextExcerpt));
                                    e.put("chunkIndex", po.chunkIndex);
                                    e.put("isBaseline", po.baseline);
                                    e.put("sourcePath", po.oldSourcePath);
                                    return e;
                                }).toList();

                                // 收集涉及的文档 id / 名（related_doc_ids / related_doc_names）
                                List<String> docIds = items.stream()
                                        .map(po -> po.documentId).filter(java.util.Objects::nonNull).distinct().toList();
                                List<String> docNames = items.stream()
                                        .map(po -> po.docNameSnapshot).filter(java.util.Objects::nonNull).distinct().toList();

                                // 构造主表 Badcase 工单
                                BadcaseProblemType problemType = mapToProblemType(record.getType());
                                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("conflictRecordId", record.getId().value());
                                payload.put("conflictTopic", record.getTopic());
                                payload.put("evidence", evidence);

                                BadcaseTicket ticket = BadcaseTicket.create(
                                        BadcaseSource.CONFLICT_DETECTION,
                                        problemType,
                                        command.description(),
                                        record.getKbId(),
                                        null,  // kbNameSnapshot 暂不查
                                        docIds,
                                        docNames,
                                        payload,
                                        operatorId,
                                        operatorId
                                );
                                ConflictRecord updated = record.markBadcaseSubmitted(ticket.getId().value(), operatorId);
                                return recordRepository.merge(updated)
                                        .chain(v -> badcaseTicketRepository.persist(ticket))
                                        .map(t -> t.getTicketNo());
                            });
                });
    }

    private Uni<ConflictRecord> findExisting(String id) {
        return recordRepository.findById(ConflictRecordId.of(id))
                .map(opt -> opt.orElseThrow(() -> {
                    logger.warn("Conflict record not found: id={}", id);
                    return new ConflictNotFoundException("冲突记录不存在");
                }));
    }

    /** ConflictType → BadcaseProblemType（两者枚举名一一对应）。 */
    private static BadcaseProblemType mapToProblemType(ConflictType type) {
        if (type == null) return BadcaseProblemType.SEMANTIC_CONFLICT;
        return switch (type) {
            case FACT_CONFLICT -> BadcaseProblemType.FACT_CONFLICT;
            case SEMANTIC_CONFLICT -> BadcaseProblemType.SEMANTIC_CONFLICT;
            case VERSION_CONFLICT -> BadcaseProblemType.VERSION_CONFLICT;
            case CROSS_MODAL_CONFLICT -> BadcaseProblemType.CROSS_MODAL_CONFLICT;
            case NEAR_DUPLICATE_CONFLICT -> BadcaseProblemType.NEAR_DUPLICATE_CONFLICT;
        };
    }
}
