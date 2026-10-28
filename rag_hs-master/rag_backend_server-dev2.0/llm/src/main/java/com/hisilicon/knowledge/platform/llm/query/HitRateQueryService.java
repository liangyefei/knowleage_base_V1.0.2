package com.hisilicon.knowledge.platform.llm.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.infrastructure.service.ChatAnswerImageRewriter;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository.TotalsAgg;
import com.hisilicon.knowledge.platform.llm.query.dto.ChipHitRateItem;
import com.hisilicon.knowledge.platform.llm.query.dto.HitRateSummaryItem;
import com.hisilicon.knowledge.platform.llm.query.dto.MissItem;
import com.hisilicon.knowledge.platform.llm.query.dto.TrendPointItem;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 命中率仪表盘查询服务（CQRS 查询侧，只读）
 * <p>口径（与产品确认）：</p>
 * <ul>
 *   <li>总查询数 = 时间窗内全部 qa_kb_stats 行（含闲聊，"全部计入"，仅作卡片展示）</li>
 *   <li>命中率 = kb_hit=true 行数 / 有效查询数；<b>有效查询数 = 全部行 - 闲聊行</b>
 *       （no_hit_reason='chitchat' 未触发检索，不计入分母，否则命中率永远无法由未命中列表解释）</li>
 *   <li>芯片维度 = 知识库维度（每芯片一个知识库），分母同样排除闲聊</li>
 *   <li>未命中列表 = kb_hit 非 true 且排除闲聊（与分母同源谓词：列表为空 ⟺ 命中率 100%）</li>
 *   <li>环比 = 与上一个等长时间窗的整体命中率对比（同一口径）</li>
 * </ul>
 * <p>芯片展示：kbId 映射为落库时的名称快照（qa_kb_stats.kb_names_json，见 migration_021）
 * 直接下发——统计是对当时事实的记录，知识库后续改名/删除不影响历史行；无快照（旧行/解析失败）
 * 回退展示 id 本身。</p>
 */
@ApplicationScoped
public class HitRateQueryService {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 支持的时间桶粒度（白名单，内联进原生 SQL 的 date_trunc） */
    private static final Set<String> GRANULARITIES = Set.of("day", "week", "month");
    private static final String DEFAULT_GRANULARITY = "day";

    /** 概览极值聚合的最小分母：范围内该芯片无关联行则不参与最高/最低 */
    private static final long MIN_CHIP_TOTAL = 1;

    private final KbStatsRepository kbStatsRepository;

    public HitRateQueryService(KbStatsRepository kbStatsRepository) {
        this.kbStatsRepository = kbStatsRepository;
    }

    /**
     * 概览四卡：整体命中率（+环比）、总查询数、芯片维度命中率最高/最低
     *
     * @param from 窗口起点（含，必填）
     * @param to   窗口终点（不含，必填）
     */
    public Uni<HitRateSummaryItem> summary(LocalDateTime from, LocalDateTime to, String source, String kbId) {
        LocalDateTime prevFrom = from.minus(java.time.Duration.between(from, to));
        return kbStatsRepository.countTotals(from, to, source, kbId)
                .flatMap(totals -> kbStatsRepository.countTotals(prevFrom, from, source, kbId)
                        .flatMap(prev -> enrichedChips(from, to, source, kbId)
                                .map(chips -> {
                                    long effective = effective(totals);
                                    double overall = rate(totals.hit(), effective);
                                    // 非命中 = 有效查询 - 命中；恒等于未命中列表（同谓词）的行数
                                    long misses = Math.max(effective - totals.hit(), 0);
                                    HitRateSummaryItem.Extremum max = extremum(chips,
                                            Comparator.comparingDouble(ChipHitRateItem::hitRate).reversed());
                                    HitRateSummaryItem.Extremum min = extremum(chips,
                                            Comparator.comparingDouble(ChipHitRateItem::hitRate));
                                    return new HitRateSummaryItem(overall, rate(prev.hit(), effective(prev)),
                                            totals.total(), totals.hit(), misses, totals.chitchat(), max, min);
                                })));
    }

    /**
     * 时间趋势（折线=命中率，柱=查询次数）
     *
     * @param granularity day/week/month（null/非法=day）
     */
    public Uni<List<TrendPointItem>> trend(String granularity, LocalDateTime from, LocalDateTime to,
                                           String source, String kbId) {
        String bucket = granularity != null && GRANULARITIES.contains(granularity.trim().toLowerCase())
                ? granularity.trim().toLowerCase() : DEFAULT_GRANULARITY;
        return kbStatsRepository.aggregateTrend(bucket, from, to, source, kbId)
                .onItem().transform(rows -> rows.stream()
                        .map(r -> new TrendPointItem(r.bucket(), r.bucketStart(),
                                rate(r.hit(), Math.max(r.total() - r.chitchat(), 0)),
                                r.total(), r.hit(), r.chitchat()))
                        .toList());
    }

    /**
     * 芯片（知识库）维度命中率（横向条形图，按命中率降序；kbName=知识库名称）
     *
     * @param kbId 只看该知识库（null=全部；传入时仅返回该库一条）
     */
    public Uni<List<ChipHitRateItem>> byChip(LocalDateTime from, LocalDateTime to, String source, String kbId) {
        return enrichedChips(from, to, source, kbId);
    }

    /**
     * 未命中列表（分页；kb_hit 非 true，排除闲聊）。
     * <p>与命中率分母同源：同一筛选下 totalRow 恒等于概览的 totalMisses。</p>
     *
     * @param keyword 问题原文模糊匹配（null=不过滤）
     */
    public Uni<PageResult<MissItem>> misses(String keyword, LocalDateTime from, LocalDateTime to,
                                            String source, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 1000);
        return kbStatsRepository.findMisses(keyword, from, to, source, safePage, safeSize)
                .flatMap(rows -> kbStatsRepository.countMisses(keyword, from, to, source)
                        .map(total -> new PageResult<>(safePage, safeSize,
                                (total + safeSize - 1) / safeSize, total,
                                rows.stream().map(HitRateQueryService::toMissItem).toList())));
    }

    /** 导出行模型：未命中列表当前筛选结果全量（不分页） */
    public Uni<List<MissItem>> missesForExport(String keyword, LocalDateTime from, LocalDateTime to,
                                               String source) {
        return kbStatsRepository.findMisses(keyword, from, to, source, 1, Integer.MAX_VALUE / 2)
                .onItem().transform(rows -> rows.stream().map(HitRateQueryService::toMissItem).toList());
    }

    /** 实体 → 列表行：kbName=kb_names_json 快照按 selected_kbs 解析（失败回退 id 列表）；answerText 加工为干净 markdown */
    private static MissItem toMissItem(KbStatsEntity e) {
        return new MissItem(e.getMessageId(), e.getQueryText(), e.getAccount(), e.getSource(),
                resolveKbName(e.getKbNamesJson(), e.getSelectedKbs()),
                extractAnswerMarkdown(e.getAnswerText()), e.getCreatedAt());
    }

    /**
     * answerText 加工：落库 ASSISTANT 正文是模型 SSE 载荷原样拼接
     * （{@code {"type":"route_reasoning",..}{"answer":"..","type":"answer",..}..}），
     * 管理端（分页列表与 CSV/XLSX 导出共用）只要干净 markdown——与前端渲染口径一致：
     * 按序提取所有 {@code type=answer} 载荷的 {@code answer} 值拼接。
     * <p>降级口径：content 为空、无顶层 JSON 对象、任一载荷解析失败、或没有任何 answer 载荷
     * （如已是纯文本/旧数据）→ 原样返回，不抛异常。</p>
     */
    static String extractAnswerMarkdown(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        List<String> payloads = ChatAnswerImageRewriter.splitJsonValues(content);
        if (payloads.isEmpty()) {
            return content;
        }
        StringBuilder joined = new StringBuilder(content.length());
        try {
            // 切分无损校验：载荷应按序覆盖全部顶层对象；残留非空白片段（未闭合对象等）视为畸形 → 降级原文
            int cursor = 0;
            for (String payload : payloads) {
                int at = content.indexOf(payload, cursor);
                if (at < 0 || !content.substring(cursor, at).isBlank()) {
                    return content;
                }
                cursor = at + payload.length();
                com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(payload);
                if (root == null || !root.isObject()) {
                    return content;
                }
                com.fasterxml.jackson.databind.JsonNode type = root.get("type");
                if (type == null || !"answer".equals(type.asText())) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode answer = root.get("answer");
                if (answer != null && !answer.isNull()) {
                    joined.append(answer.asText());
                }
            }
            if (!content.substring(cursor).isBlank()) {
                return content;
            }
        } catch (Exception e) {
            return content;
        }
        return joined.length() == 0 ? content : joined.toString();
    }

    /**
     * 所属知识库名称解析：kb_names_json（id→name JSON 快照）按 selected_kbs 顺序取名称，
     * 多库逗号分隔；解析失败/无快照回退 id 列表；无选中库为空串。
     */
    static String resolveKbName(String kbNamesJson, String selectedKbs) {
        if (selectedKbs == null || selectedKbs.isBlank()) {
            return "";
        }
        java.util.Map<String, String> names = parseKbNames(kbNamesJson);
        return java.util.Arrays.stream(selectedKbs.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> names.getOrDefault(id, id))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** kb_names_json → id→name 映射（null/解析失败回退空映射，由调用方回退展示 id） */
    private static java.util.Map<String, String> parseKbNames(String kbNamesJson) {
        try {
            return JSON.readValue(kbNamesJson == null ? "{}" : kbNamesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {
                    });
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    /**
     * 芯片聚合 + 名称映射：聚合行 → DTO（kbName=kb_names_json 落库快照中的名称，
     * 无快照回退 kbId 本身）。summary 与 byChip 共用，保证极值 label 与条形图名称同源。
     * kbId 非空时仅聚合该库，与 countTotals 的 kbId 过滤口径一致。
     */
    private Uni<List<ChipHitRateItem>> enrichedChips(LocalDateTime from, LocalDateTime to,
                                                     String source, String kbId) {
        return kbStatsRepository.aggregateByChip(from, to, source, kbId)
                .flatMap(chips -> {
                    if (chips.isEmpty()) {
                        return Uni.createFrom().item(List.<ChipHitRateItem>of());
                    }
                    return kbStatsRepository.resolveKbNames()
                            .map(names -> chips.stream()
                                    .map(r -> new ChipHitRateItem(
                                            names.getOrDefault(r.kbId(), r.kbId()),
                                            rate(r.hit(), r.total()), r.hit(), r.total()))
                                    .toList());
                });
    }

    /**
     * 有效查询数 = 全部行 - 闲聊行（命中率分母）。
     * 闲聊（no_hit_reason='chitchat'）未触发检索，不计入命中率；与未命中列表的排除谓词同源，
     * 保证"列表为空 ⟺ 命中率 100%"。countTotals 的恒等式：total = chitchat + hit + miss。
     */
    private static long effective(TotalsAgg totals) {
        return Math.max(totals.total() - totals.chitchat(), 0);
    }

    /** 命中率：hit/total；分母为 0 时返回 0（无数据期不抛异常） */
    static double rate(long hit, long total) {
        return total <= 0 ? 0.0 : (double) hit / total;
    }

    /** 芯片 DTO → 极值项（total&lt;MIN_CHIP_TOTAL 的维度不参与；空集返回 null） */
    private static HitRateSummaryItem.Extremum extremum(List<ChipHitRateItem> chips,
                                                        Comparator<ChipHitRateItem> order) {
        return chips.stream()
                .filter(item -> item.total() >= MIN_CHIP_TOTAL)
                .min(order)
                .map(item -> new HitRateSummaryItem.Extremum(item.hitRate(), item.kbName()))
                .orElse(null);
    }
}
