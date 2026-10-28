package com.hisilicon.knowledge.platform.llm.command;

import com.hisilicon.knowledge.platform.llm.infrastructure.po.InsightBadcaseTicketPO;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.InsightSettingsPO;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.QaInsightPO;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.InsightBadcasePanacheRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.InsightSettingsPanacheRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.QaInsightPanacheRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.InsightAnalysisClient;
import com.hisilicon.knowledge.platform.llm.query.dto.InsightAnalyzeResult;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答洞察命令服务：手动批量分析 + badcase 提交。
 * <p>已移除定时扫描（仅保留手动触发）。</p>
 */
@ApplicationScoped
public class InsightCommandService {

    private static final Logger logger = LoggerFactory.getLogger(InsightCommandService.class);

    /** 每小批拉取条数（事务边界，一次查 50 条）。 */
    private static final int BATCH_SIZE = 50;
    /** 单次手动触发最多处理条数上限（防一次跑太久）。 */
    private static final int MAX_RUN_PER_INVOKE = 500;
    /** 每条之间的节流间隔（给模型端喘气）。 */
    private static final long THROTTLE_MS = 200;

    private final QaInsightPanacheRepository insightRepo;
    private final InsightSettingsPanacheRepository settingsRepo;
    private final InsightBadcasePanacheRepository badcaseRepo;
    private final InsightAnalysisClient analysisClient;

    public InsightCommandService(QaInsightPanacheRepository insightRepo,
                                 InsightSettingsPanacheRepository settingsRepo,
                                 InsightBadcasePanacheRepository badcaseRepo,
                                 InsightAnalysisClient analysisClient) {
        this.insightRepo = insightRepo;
        this.settingsRepo = settingsRepo;
        this.badcaseRepo = badcaseRepo;
        this.analysisClient = analysisClient;
    }

    /**
     * 批量分析未分析记录（手动触发）。
     * <p>模式：循环取小批（50条）→ 逐条调模型（200ms 节流）→ 每条独立小事务落库 → 直到 PENDING 清空或达到 {@link #MAX_RUN_PER_INVOKE} 上限。</p>
     *
     * @param startTime 时间范围起始（null=不限）
     * @param endTime   时间范围结束（null=不限）
     */
    public Uni<InsightAnalyzeResult> batchAnalyze(LocalDateTime startTime, LocalDateTime endTime) {
        final int[] totalScanned = {0};
        final int[] totalAnalyzed = {0};
        final int[] totalFailed = {0};

        return Uni.createFrom().voidItem()
                .chain(v -> fetchAndProcessBatch(startTime, endTime, totalScanned, totalAnalyzed, totalFailed))
                .repeat().until(batchDone -> batchDone)
                .toUni()
                .map(v -> new InsightAnalyzeResult(totalScanned[0], totalAnalyzed[0], totalFailed[0]));
    }

    @WithSession
    Uni<Boolean> fetchAndProcessBatch(LocalDateTime startTime, LocalDateTime endTime,
                                      int[] totalScanned, int[] totalAnalyzed, int[] totalFailed) {
        if (totalScanned[0] >= MAX_RUN_PER_INVOKE) {
            logger.info("Insight analyze reached max run per invoke: total={}", totalScanned[0]);
            return Uni.createFrom().item(false);
        }
        int limit = Math.min(BATCH_SIZE, MAX_RUN_PER_INVOKE - totalScanned[0]);

        String hql = "status = 'PENDING'";
        List<Object> params = new java.util.ArrayList<>();
        if (startTime != null) { hql += " and createdAt >= ?1"; params.add(startTime); }
        if (endTime != null)   { hql += " and createdAt <= ?" + (params.size() + 1); params.add(endTime); }

        return insightRepo.find(hql, io.quarkus.panache.common.Sort.ascending("createdAt"), params.toArray())
                .range(0, limit - 1)
                .list()
                .chain(pendingList -> {
                    if (pendingList.isEmpty()) {
                        return Uni.createFrom().item(false);
                    }
                    totalScanned[0] += pendingList.size();
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (QaInsightPO po : pendingList) {
                        chain = chain
                                .chain(v -> analysisClient.classify(po.getQuestion(), po.getAnswer()))
                                .onItem().transformToUni(outcome -> persistOne(po, outcome))
                                .onItem().invoke(count -> {
                                    if (count == 1) totalAnalyzed[0]++; else totalFailed[0]++;
                                })
                                .chain(v -> Uni.createFrom().voidItem()
                                        .onItem().delayIt().by(Duration.ofMillis(THROTTLE_MS)));
                    }
                    return chain.map(v -> totalScanned[0] < MAX_RUN_PER_INVOKE);
                });
    }

    @WithTransaction
    Uni<Integer> persistOne(QaInsightPO po, InsightAnalysisClient.ClassifyOutcome outcome) {
        if (outcome == null) {
            return Uni.createFrom().item(0);
        }
        po.setStatus("ANALYZED");
        po.setCategory(mapCategory(outcome.category()));
        po.setCategoryReason(outcome.reason());
        po.setAnalyzedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return insightRepo.persist(po)
                .replaceWithVoid()
                .map(v -> 1)
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Persist insight failed: id={}, err={}", po.getId(), e.getMessage());
                    return Uni.createFrom().item(0);
                });
    }

    /**
     * 提交洞察 badcase 工单。
     * <p>source=PAGE_QA，problem_type 由前端从 7 种问答类问题类型中自选；
     * question/answer 从 qa_insights 快照取出存 source_payload；description 为前端填写的问题描述。</p>
     *
     * @return 工单号
     */
    @WithTransaction
    public Uni<String> submitBadcase(InsightBadcaseCommand cmd, String account) {
        if (cmd.problemType() == null || cmd.problemType().isBlank() || !QA_PROBLEM_TYPES.contains(cmd.problemType())) {
            return Uni.createFrom().failure(new IllegalArgumentException("非法的问题类型: " + cmd.problemType()));
        }
        return insightRepo.findById(cmd.insightId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("洞察记录不存在: " + cmd.insightId()))
                .chain(po -> {
                    InsightBadcaseTicketPO ticket = new InsightBadcaseTicketPO();
                    LocalDateTime now = LocalDateTime.now();
                    String uuid32 = java.util.UUID.randomUUID().toString().replace("-", "");
                    ticket.setId("bc" + uuid32);
                    String datePart = String.format("%04d%02d%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
                    ticket.setTicketNo("BC-" + datePart + "-" + uuid32.substring(0, 8).toUpperCase());
                    ticket.setSource("PAGE_QA");
                    ticket.setProblemType(cmd.problemType());
                    ticket.setDescription(cmd.description() == null || cmd.description().isBlank()
                            ? po.getQuestion() : cmd.description());
                    ticket.setStatus("PENDING");
                    ticket.setKbId(po.getKbId());
                    ticket.setSourcePayload("{\"question\":\"" + escape(po.getQuestion())
                            + "\",\"answer\":\"" + escape(po.getAnswer() == null ? "" : po.getAnswer())
                            + "\",\"insightId\":\"" + po.getId() + "\"}");
                    ticket.setSubmittedBy(account);
                    ticket.setSubmittedByName(account);
                    ticket.setSubmittedAt(now);
                    ticket.setCreatedAt(now);
                    ticket.setUpdatedAt(now);
                    return badcaseRepo.persist(ticket).map(InsightBadcaseTicketPO::getTicketNo);
                });
    }

    /** PAGE_QA 来源支持的 7 种问题类型（与 knowledge 模块 BadcaseProblemType 枚举保持一致）。 */
    private static final java.util.Set<String> QA_PROBLEM_TYPES = java.util.Set.of(
            "WRONG_ANSWER", "CODE_ERROR", "IRRELEVANT_ANSWER",
            "OUTDATED_INFO", "IRRELEVANT_RESULT",
            "SENSITIVE_NOT_FLAGGED", "NOT_SENSITIVE_FLAGGED");

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 模型返回中文 → 平台枚举名；"无"返回 null。 */
    private static String mapCategory(String chinese) {
        if (chinese == null || chinese.isBlank()) return null;
        return switch (chinese.trim()) {
            case "研发难点" -> "RESEARCH_PAIN";
            case "潜在风险" -> "POTENTIAL_RISK";
            case "潜在需求" -> "POTENTIAL_NEED";
            default -> null;
        };
    }

    /** 合法周期类型。 */
    private static final java.util.Set<String> VALID_SCHEDULE = java.util.Set.of("DAILY", "WEEKLY", "MONTHLY");

    /**
     * 更新定时配置。
     *
     * @param scheduleType DAILY / WEEKLY / MONTHLY
     * @param scheduleTime HH:mm 格式
     * @param scheduleDay  WEEKLY: 1~7（周一~周日）；MONTHLY: 1~31；DAILY: null
     */
    @WithTransaction
    public Uni<Void> updateSchedule(String scheduleType, String scheduleTime, Integer scheduleDay) {
        if (scheduleType == null || !VALID_SCHEDULE.contains(scheduleType)) {
            return Uni.createFrom().failure(new IllegalArgumentException("非法的周期类型: " + scheduleType));
        }
        String time = (scheduleTime == null || scheduleTime.isBlank()) ? "02:00" : scheduleTime;
        try {
            java.time.LocalTime.parse(time);
        } catch (Exception e) {
            return Uni.createFrom().failure(new IllegalArgumentException("非法的时间点格式(HH:mm): " + scheduleTime));
        }
        // 校验 schedule_day
        Integer day = null;
        if ("WEEKLY".equals(scheduleType)) {
            if (scheduleDay == null || scheduleDay < 1 || scheduleDay > 7) {
                return Uni.createFrom().failure(new IllegalArgumentException("WEEKLY 需 scheduleDay=1~7"));
            }
            day = scheduleDay;
        } else if ("MONTHLY".equals(scheduleType)) {
            if (scheduleDay == null || scheduleDay < 1 || scheduleDay > 31) {
                return Uni.createFrom().failure(new IllegalArgumentException("MONTHLY 需 scheduleDay=1~31"));
            }
            day = scheduleDay;
        }
        final String finalTime = time;
        final Integer finalDay = day;
        return settingsRepo.findById(1)
                .chain(po -> {
                    if (po == null) {
                        po = new InsightSettingsPO();
                        po.setId(1);
                    }
                    po.setScheduleType(scheduleType);
                    po.setScheduleTime(finalTime);
                    po.setScheduleDay(finalDay);
                    po.setUpdatedAt(LocalDateTime.now());
                    return settingsRepo.persist(po).replaceWithVoid();
                });
    }
}
