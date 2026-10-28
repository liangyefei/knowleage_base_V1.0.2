package com.hisilicon.knowledge.platform.mcp.registry;

import com.hisilicon.knowledge.platform.mcp.config.McpGatewayConfig;

/**
 * 一条工具路由：网关暴露名 → 上游 + 上游侧原始工具名 + 原始 schema。
 *
 * <p>暴露名规则：{@code 上游名<separator>原始工具名}（如 {@code hisi-docs-mcp__hisi_docs_search}）。
 * 前缀**不是**装饰：实测 {@code hisi_docs_search} 同时存在于 9802 与 9803，
 * 没有前缀就无法消歧（见方案 §4 阶段1 第 2 条、§5 风险表）。</p>
 *
 * @param exposedName  对 agent 暴露的工具名（含上游前缀）
 * @param upstream     该工具所属上游
 * @param originalName 上游侧的原始工具名（转发时用）
 * @param description  工具描述（原样透传上游）
 * @param inputSchema  输入 schema（原样透传上游 JSON，网关不改写以免 agent 侧 schema 漂移）
 */
public record ToolRoute(
        String exposedName,
        McpGatewayConfig.Upstream upstream,
        String originalName,
        String description,
        Object inputSchema) {

    /**
     * 构造暴露名。
     *
     * @param separator 前缀分隔符（配置项 {@code mcp-gateway.name-separator}）
     * @param upstreamName 上游名
     * @param originalName 上游侧原始工具名
     * @return 暴露名
     */
    public static String exposedName(String separator, String upstreamName, String originalName) {
        return upstreamName + separator + originalName;
    }
}