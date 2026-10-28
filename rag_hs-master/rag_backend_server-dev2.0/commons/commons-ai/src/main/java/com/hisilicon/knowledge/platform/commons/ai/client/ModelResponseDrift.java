package com.hisilicon.knowledge.platform.commons.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dual-mcp 响应契约漂移探测。
 *
 * <p>背景：模型响应不在边界做强类型绑定后，字段改名/新增/挪位置不会让反序列化失败，但也意味着
 * "悄悄变了没人知道"——2026-10 {@code /graph_node} 把 {@code source/in_types/out_types} 挪进
 * {@code node}，平台静默丢字段即属此类。本类在解析每条响应前，按文档声明的字段集合比对实际响应：
 * 出现<b>未声明字段</b>（模型新增/改名）或文档声明但响应<b>缺失必需字段</b>，记 WARN。</p>
 *
 * <p>同一端点+同一字段只告警一次（进程内），避免高频接口刷屏；模型变更后看一条日志即可定位。</p>
 *
 * <p>检测逻辑用 {@link #findings} 表达（纯函数、不发日志）：生产侧 {@link #check} 只负责把结果按
 * 端点去重后写日志，{@code ModelContractTest} 则直接断言 {@code findings} 为空——两边共用同一套
 * 判定，不会出现"测试说没事、线上却在告警"的偏差。</p>
 */
final class ModelResponseDrift {

    private static final Logger log = LoggerFactory.getLogger(ModelResponseDrift.class);

    /** 已告警过的 endpoint|message，防重复刷屏 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ModelResponseDrift() {
    }

    /**
     * 探测一条响应节点的契约漂移，返回问题描述（纯函数，不写日志）。
     *
     * @param body       实际响应节点（非对象或空则无漂移可言，返回空列表）
     * @param documented 文档声明的字段集合（多出的字段视为漂移）
     * @param required   业务依赖的必需字段（缺失视为漂移）
     * @return 逐条问题描述，无漂移时为空列表
     */
    static List<String> findings(JsonNode body, Set<String> documented, Set<String> required) {
        if (body == null || body.isNull() || !body.isObject()) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        var names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!documented.contains(name)) {
                findings.add("undeclared field '" + name + "' (added or renamed by model?)");
            }
        }
        for (String name : required) {
            if (!body.has(name)) {
                findings.add("required field '" + name + "' missing");
            }
        }
        return findings;
    }

    /**
     * 比对一条响应节点，发现的漂移按 {@code endpoint|message} 去重后记 WARN。
     *
     * @param endpoint   端点标识（用于日志定位，如 {@code POST /graph_node}）
     * @param body       实际响应节点
     * @param documented 文档声明的字段集合（多出的字段视为漂移）
     * @param required   业务依赖的必需字段（缺失视为漂移）
     */
    static void check(String endpoint, JsonNode body, Set<String> documented, Set<String> required) {
        for (String finding : findings(body, documented, required)) {
            warnOnce(endpoint, finding);
        }
    }

    private static synchronized void warnOnce(String endpoint, String message) {
        if (WARNED.add(endpoint + "|" + message)) {
            log.warn("dual-mcp response contract drift, endpoint={}: {} (reported once per endpoint/field)",
                    endpoint, message);
        }
    }
}
