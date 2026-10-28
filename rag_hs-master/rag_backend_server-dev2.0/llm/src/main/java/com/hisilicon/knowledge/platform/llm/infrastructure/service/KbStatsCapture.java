package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * kb_stats 流式采集器（每流一个实例，feed 原始下发分片）。
 *
 * <p>仅观察不下发改写：multi_knowledge_base_chat 路径模型会在收尾事件（通常与 docs 同帧）
 * 附带 {@code kb_stats} 统计对象（rag_triggered/kb_hit/no_hit_reason/retrieved_count/used_count/
 * hit_kbs/rewritten_query/top_score/route_reason/degraded）。本采集器逐片解析顶层 JSON，
 * 首个含 {@code kb_stats} 对象的分片即捕获并保留原始 JSON；不含 kb_stats 的分片（answer/route_reasoning）
 * 直接忽略，docs 载荷透传不受任何影响（采集器不产出、不修改任何下发内容）。
 * 文件问答 / 纯 LLM 路径不挂接本采集器。</p>
 *
 * <p>解析失败（非 JSON / 非对象 / 序列化异常）仅 DEBUG 记录并忽略，不阻断流——统计采集
 * 属旁路能力，任何异常不得影响回答下发。</p>
 *
 * <p>注意：模型响应中的 {@code message_id} 恒为空串（模型端持久化已禁用，见对接文档 §7），
 * 本采集器不读取该字段；入库关联一律使用平台侧 assistantMessageId（由调用方在 complete 时提供）。</p>
 */
public class KbStatsCapture {

    private static final Logger logger = LoggerFactory.getLogger(KbStatsCapture.class);

    private static final String FIELD_KB_STATS = "kb_stats";

    private final ObjectMapper mapper;
    /** 首个捕获的 kb_stats 载荷（null=流内未出现 kb_stats） */
    private volatile KbStatsData captured;

    public KbStatsCapture(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 处理一个下发分片（模型 SSE data 内容原文）。
     * 已捕获后续分片直接跳过（收尾事件只出现一次）。
     */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty() || captured != null) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(chunk);
            if (root == null || !root.isObject()) {
                return;
            }
            JsonNode stats = root.get(FIELD_KB_STATS);
            if (stats == null || !stats.isObject()) {
                return;
            }
            captured = parse(stats);
            logger.debug("Captured kb_stats: kbHit={}, retrievedCount={}, usedCount={}",
                    captured.kbHit(), captured.retrievedCount(), captured.usedCount());
        } catch (Exception e) {
            // 旁路采集失败不阻断流
            logger.debug("Failed to parse kb_stats, ignoring this chunk: {}", e.toString());
        }
    }

    /**
     * @return 捕获的 kb_stats 数据；流内未出现 kb_stats（闲聊/未下发）返回 null
     */
    public KbStatsData captured() {
        return captured;
    }

    /**
     * kb_stats 载荷 → 落库数据（列名与 docs/sql/migration_019_kb_stats.sql 一致的下划线字段）。
     * hit_kbs 兼容数组（逗号连接）与字符串两种形态。
     */
    private KbStatsData parse(JsonNode s) {
        return new KbStatsData(
                bool(s, "rag_triggered"),
                bool(s, "kb_hit"),
                text(s, "no_hit_reason"),
                intVal(s, "retrieved_count"),
                intVal(s, "used_count"),
                hitKbs(s.get("hit_kbs")),
                text(s, "rewritten_query"),
                decimal(s, "top_score"),
                text(s, "route_reason"),
                bool(s, "degraded"),
                s.toString());
    }

    private Boolean bool(JsonNode s, String field) {
        JsonNode n = s.get(field);
        return (n == null || n.isNull()) ? null : n.asBoolean();
    }

    private String text(JsonNode s, String field) {
        JsonNode n = s.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private Integer intVal(JsonNode s, String field) {
        JsonNode n = s.get(field);
        return (n == null || n.isNull()) ? null : n.asInt();
    }

    private BigDecimal decimal(JsonNode s, String field) {
        JsonNode n = s.get(field);
        return (n == null || n.isNull()) ? null : n.decimalValue();
    }

    private String hitKbs(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            List<String> items = new ArrayList<>();
            n.forEach(item -> items.add(item.asText()));
            return String.join(",", items);
        }
        return n.asText();
    }

    /**
     * 捕获的 kb_stats 落库数据（字段顺序与 qa_kb_stats 列一致；kbStatsJson 为原始 JSON 留档）
     */
    public record KbStatsData(Boolean ragTriggered, Boolean kbHit, String noHitReason,
                              Integer retrievedCount, Integer usedCount, String hitKbs,
                              String rewrittenQuery, BigDecimal topScore, String routeReason,
                              Boolean degraded, String kbStatsJson) {
    }
}
