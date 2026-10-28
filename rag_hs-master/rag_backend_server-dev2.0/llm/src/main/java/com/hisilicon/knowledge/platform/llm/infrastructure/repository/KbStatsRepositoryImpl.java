package com.hisilicon.knowledge.platform.llm.infrastructure.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import com.hisilicon.knowledge.platform.llm.domain.repository.KbStatsRepository;
import com.hisilicon.knowledge.platform.llm.infrastructure.mapper.KbStatsMapper;
import com.hisilicon.knowledge.platform.llm.infrastructure.po.KbStatsPO;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答 KB 统计仓储实现
 * <p>聚合统计用原生 SQL（ unnest 展开逗号分隔的 hit_kbs/selected_kbs，
 * date_trunc 按时间桶分组）；未命中分页用 Panache HQL（回答正文 join conversation_messages 回填）。</p>
 * <p><b>闲聊（no_hit_reason='chitchat'）口径只有一处定义</b>：命中率分母（概览/趋势/芯片）排除闲聊，
 * 未命中列表同样排除闲聊。两处谓词必须同义，否则会出现"命中率不是 100%、未命中列表却为空"。</p>
 * <p>类级 {@code @WithSession}：仪表盘查询链（Resource → QueryService → 本仓储）不在
 * JAX-RS 方法的自动 session 上下文内（QueryService 中转后 context 丢失），需显式开启
 * 惰性 session（见 quarkus规范.md：只读查询选用 @WithSession）。</p>
 */
@ApplicationScoped
@WithSession
public class KbStatsRepositoryImpl implements KbStatsRepository {

    private final KbStatsPanacheRepository panacheRepo;
    private final KbStatsMapper mapper = KbStatsMapper.INSTANCE;

    public KbStatsRepositoryImpl(KbStatsPanacheRepository panacheRepo) {
        this.panacheRepo = panacheRepo;
    }

    @Override
    public Uni<KbStatsEntity> persist(KbStatsEntity entity) {
        KbStatsPO po = mapper.toPO(entity);
        return panacheRepo.persist(po).map(ignored -> entity);
    }

    @Override
    public Uni<KbStatsEntity> findById(String messageId) {
        return panacheRepo.findById(messageId).onItem().transform(po -> po == null ? null : mapper.toDomain(po));
    }

    @Override
    public Uni<TotalsAgg> countTotals(LocalDateTime from, LocalDateTime to, String source, String kbId) {
        // 三列：全部行数（含闲聊，卡片展示）、命中数、闲聊数（不进命中率分母）
        String sql = "SELECT COUNT(*), "
                + "COALESCE(SUM(CASE WHEN " + notChitchat("qa_kb_stats") + " AND kb_hit THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN " + chitchat("qa_kb_stats") + " THEN 1 ELSE 0 END), 0) "
                + "FROM qa_kb_stats WHERE created_at >= ?1 AND created_at < ?2"
                + filterClause(source, kbId);
        return Panache.getSession()
                .flatMap(session -> {
                    var query = session.createNativeQuery(sql, Object[].class)
                            .setParameter(1, from)
                            .setParameter(2, to);
                    bindFilters(query, source, kbId);
                    return query.getResultList();
                })
                .onItem().transform(rows -> rows.stream()
                        .map(this::singleTotalsRow)
                        .toList())
                .onItem().transform(list -> list.get(0));
    }

    @Override
    public Uni<List<TrendAgg>> aggregateTrend(String bucket, LocalDateTime from, LocalDateTime to,
                                              String source, String kbId) {
        // day: yyyy-MM-dd；week: ISO 周历（IYYY=ISO 年，防跨年错周）；month: yyyy年MM月。
        // bucketStart 保留原始桶起始日，供前端排序/tooltip。
        String labelExpr = switch (bucket) {
            case "week" -> "TO_CHAR(DATE_TRUNC('week', created_at), 'IYYY\"年\"IW\"周\"')";
            case "month" -> "TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY\"年\"MM\"月\"')";
            default -> "TO_CHAR(DATE_TRUNC('day', created_at), 'YYYY-MM-DD')";
        };
        String sql = "SELECT " + labelExpr + " AS b, "
                + "TO_CHAR(DATE_TRUNC('" + bucket + "', created_at), 'YYYY-MM-DD') AS bstart, "
                + "COUNT(*), "
                + "COALESCE(SUM(CASE WHEN " + notChitchat("qa_kb_stats") + " AND kb_hit THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN " + chitchat("qa_kb_stats") + " THEN 1 ELSE 0 END), 0) "
                + "FROM qa_kb_stats WHERE created_at >= ?1 AND created_at < ?2"
                + filterClause(source, kbId)
                + " GROUP BY 1, 2 ORDER BY 2";
        return Panache.getSession()
                .flatMap(session -> {
                    var query = session.createNativeQuery(sql, Object[].class)
                            .setParameter(1, from)
                            .setParameter(2, to);
                    bindFilters(query, source, kbId);
                    return query.getResultList();
                })
                .onItem().transform(rows -> rows.stream()
                        .map(r -> new TrendAgg((String) r[0], (String) r[1], asLong(r[2]), asLong(r[3]),
                                asLong(r[4])))
                        .toList());
    }

    @Override
    public Uni<List<ChipAgg>> aggregateByChip(LocalDateTime from, LocalDateTime to, String source, String kbId) {
        // 行展开：selected_kbs ∪ hit_kbs 去重后每 kb 一行；hit=该 kb ∈ hit_kbs 且 kb_hit=true。
        // 闲聊行（no_hit_reason='chitchat'）不参与展开：未触发检索，不进芯片分母（与概览/趋势同口径）。
        // kbId 非空时只保留该 kb（与 summary 过滤口径一致）。
        boolean hasSource = source != null && !source.isBlank();
        boolean hasKbId = kbId != null && !kbId.isBlank();
        String sql = "WITH e AS ( "
                + "SELECT DISTINCT s.message_id, k.kb, s.kb_hit, "
                + "(k.kb = ANY(string_to_array(COALESCE(s.hit_kbs, ''), ','))) AS in_hit "
                + "FROM qa_kb_stats s, "
                + "unnest(string_to_array(concat_ws(',', COALESCE(s.selected_kbs, ''), COALESCE(s.hit_kbs, '')), ',')) AS k(kb) "
                + "WHERE s.created_at >= ?1 AND s.created_at < ?2"
                + (hasSource ? " AND s.source = ?3" : "")
                + " AND k.kb <> ''"
                + " AND " + notChitchat("s")
                + (hasKbId ? " AND k.kb = ?" + (hasSource ? 4 : 3) : "")
                + ") SELECT kb, COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN in_hit AND kb_hit THEN 1 ELSE 0 END), 0) AS hit "
                + "FROM e GROUP BY kb ORDER BY hit DESC";
        return Panache.getSession()
                .flatMap(session -> {
                    var query = session.createNativeQuery(sql, Object[].class)
                            .setParameter(1, from)
                            .setParameter(2, to);
                    int next = 3;
                    if (hasSource) {
                        query.setParameter(next++, source);
                    }
                    if (hasKbId) {
                        query.setParameter(next, kbId);
                    }
                    return query.getResultList();
                })
                .onItem().transform(rows -> rows.stream()
                        .map(r -> new ChipAgg((String) r[0], asLong(r[1]), asLong(r[2])))
                        .toList());
    }

    @Override
    public Uni<List<KbStatsEntity>> findMisses(String keyword, LocalDateTime from, LocalDateTime to,
                                               String source, int page, int size) {
        Map<String, Object> params = missParams(keyword, from, to, source);
        return panacheRepo.find(MISSES_WHERE + " ORDER BY s.createdAt DESC", params)
                .page(page - 1, size)
                .list()
                .onItem().transformToUni(this::withAnswerTexts);
    }

    @Override
    public Uni<Long> countMisses(String keyword, LocalDateTime from, LocalDateTime to, String source) {
        return panacheRepo.find(MISSES_WHERE, missParams(keyword, from, to, source)).count();
    }

    @Override
    public Uni<Map<String, String>> resolveKbNames() {
        // kb_names_json 快照 distinct 键值对展开（jsonb_each_text；TEXT 列显式转 ::jsonb）
        String sql = """
                SELECT DISTINCT t.key, t.value
                FROM qa_kb_stats s, jsonb_each_text(s.kb_names_json::jsonb) AS t(key, value)
                WHERE s.kb_names_json IS NOT NULL AND t.key <> ''
                """;
        return Panache.getSession()
                .flatMap(session -> session.createNativeQuery(sql, Object[].class)
                        .getResultList())
                .onItem().transform(rows -> {
                    Map<String, String> names = new HashMap<>();
                    rows.forEach(r -> names.put((String) r[0], (String) r[1]));
                    return names;
                });
    }

    /**
     * 原生 SQL 可选过滤子句（有值才拼条件，避免 "NULL 参数 IS NULL" 触发 PG 42P18
     * could not determine data type of parameter）。占位符序号从 ?3 起。
     */
    private static String filterClause(String source, String kbId) {
        StringBuilder sb = new StringBuilder();
        if (source != null && !source.isBlank()) {
            sb.append(" AND source = ?3");
        }
        if (kbId != null && !kbId.isBlank()) {
            // CAST 显式定型，防 ANY(text[]) 比较时参数类型无法推断
            sb.append(" AND CAST(?")
                    .append(source != null && !source.isBlank() ? 4 : 3)
                    .append(" AS text) = ANY(string_to_array(concat_ws(',', selected_kbs, hit_kbs), ','))");
        }
        return sb.toString();
    }

    /** 按 {@link #filterClause} 的占位符序号绑定过滤参数 */
    private static void bindFilters(org.hibernate.reactive.mutiny.Mutiny.SelectionQuery<Object[]> query,
                                    String source, String kbId) {
        int next = 3;
        if (source != null && !source.isBlank()) {
            query.setParameter(next++, source);
        }
        if (kbId != null && !kbId.isBlank()) {
            query.setParameter(next, kbId);
        }
    }

    /**
     * 闲聊判定（原生 SQL 形态，别名参与列限定）。
     * <p><b>必须与 {@link #MISSES_WHERE} 里的 HQL 谓词同义</b>：命中率分母排除闲聊、未命中列表同样排除闲聊，
     * 两处一旦不一致就会出现"率不是 100% 但列表为空"。大小写/首尾空白归一，NULL 归空串
     * （直接比较 NULL 会得到 unknown，静默丢行）。</p>
     */
    private static String chitchat(String alias) {
        return "LOWER(TRIM(COALESCE(" + alias + ".no_hit_reason, ''))) = 'chitchat'";
    }

    /** {@link #chitchat} 的取反（原生 SQL 形态） */
    private static String notChitchat(String alias) {
        return "LOWER(TRIM(COALESCE(" + alias + ".no_hit_reason, ''))) <> 'chitchat'";
    }

    /**
     * 未命中行公共过滤条件：kb_hit 非 true（含 NULL——模型未下发该字段时 kb_hit 为 NULL，不能用 = false
     * 表达"非命中"）且排除闲聊（与 {@link #chitchat} 同源的 HQL 谓词，闲聊非知识缺失，不进补知识工作流）。
     * <p>包级可见供单测守卫谓词不漂移。</p>
     */
    static final String MISSES_WHERE = """
            FROM KbStatsPO s
            WHERE (s.kbHit = false OR s.kbHit IS NULL)
              AND LOWER(TRIM(COALESCE(s.noHitReason, ''))) <> 'chitchat'
              AND (:keyword IS NULL OR s.queryText LIKE :keyword)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt < :to)
              AND (:source IS NULL OR s.source = :source)
            """;

    private Map<String, Object> missParams(String keyword, LocalDateTime from, LocalDateTime to, String source) {
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%");
        params.put("from", from);
        params.put("to", to);
        params.put("source", (source == null || source.isBlank()) ? null : source);
        return params;
    }

    /**
     * 关联回填模型回答内容：message_id（=assistantMessageId）join conversation_messages 的
     * ASSISTANT 消息正文（TEXT 列，实体映射天然无 255 截断）；查不到（模型失败未落库等）置 null。
     */
    private Uni<List<KbStatsEntity>> withAnswerTexts(List<KbStatsPO> pos) {
        if (pos.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<String> ids = pos.stream().map(po -> po.messageId).toList();
        return Panache.getSession()
                .flatMap(session -> session.createQuery(
                                "SELECT m.id, m.content FROM ConversationMessagePO m "
                                        + "WHERE m.id IN :ids AND m.role = 'ASSISTANT'", Object[].class)
                        .setParameter("ids", ids)
                        .getResultList())
                .onItem().transform(rows -> {
                    Map<String, String> answers = new HashMap<>();
                    rows.forEach(r -> answers.put((String) r[0], (String) r[1]));
                    return pos.stream()
                            .map(po -> {
                                KbStatsEntity entity = mapper.toDomain(po);
                                entity.setAnswerText(answers.get(po.messageId));
                                return entity;
                            })
                            .toList();
                });
    }

    /** 单行 totals 查询结果 → TotalsAgg（total / hit / chitchat） */
    private TotalsAgg singleTotalsRow(Object[] r) {
        return new TotalsAgg(asLong(r[0]), asLong(r[1]), asLong(r[2]));
    }

    /** PG 计数返回类型（Long/BigInteger/Number）统一为 long */
    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
