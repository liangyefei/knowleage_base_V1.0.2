package com.hisilicon.knowledge.platform;

/**
 * hisilicon-knowledge-mcp-gateway 启动标记类。
 *
 * <p>自身不含业务逻辑，仅作为 Quarkus 应用归档的锚点（同 {@code CoreService} /
 * {@code IamService} 写法）。本模块是**面向 agent 的入站 MCP 聚合网关**：
 * 用 {@code quarkus-mcp-server-http} 扩展提供 MCP 协议面，把模型侧多个 MCP 工具服务
 * 聚合为一个端点对外暴露。与"平台出站调用模型的 MCP"（commons-ai，REST）方向相反。</p>
 *
 * <p>端点：MCP 在 {@code /hisilicon-knowledge-mcp/mcp}（扩展默认 {@code {root-path}/mcp}），
 * 运维自检在 {@code /hisilicon-knowledge-mcp/gateway/status}。</p>
 */
public class McpGatewayService {
}