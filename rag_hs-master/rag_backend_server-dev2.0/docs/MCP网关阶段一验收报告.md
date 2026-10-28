# MCP 网关阶段一对接验收报告

> 日期：2026-10-22
> 范围：`hisilicon-knowledge-mcp-gateway` 阶段一（聚合转发 + 工具命名空间 + 失败隔离）
> 结论：**通过**。已对 CCE dev 环境 4 个真实上游完成"直连 vs 经网关"逐项对比。

## 1. 验收对象

CCE dev 环境的 4 个模型侧 MCP 服务（即用户提供的 `mcpServers` 配置）：

```json
{
  "mcpServers": {
    "hisi-code-mcp":    { "type": "http", "url": "http://139.9.104.64:9800/mcp" },
    "hisi-codegraph":   { "type": "http", "url": "http://139.9.104.64:9801/mcp" },
    "hisi-docs-mcp":    { "type": "http", "url": "http://139.9.104.64:9802/mcp" },
    "hisi-docs-search": { "type": "http", "url": "http://139.9.104.64:9803/mcp" }
  }
}
```

## 2. 上游实测基线（直连）

| 上游 | 端口 | 协商协议版本 | `serverInfo.name` | 工具数 | 下发 session |
|---|---|---|---|---|---|
| hisi-code-mcp | 9800 | `2025-06-18` | `hisi-code-mcp` | 11 | 是 |
| hisi-codegraph | 9801 | `2024-11-05` | **`hisi-code-mcp`**（与 9800 重名） | 1 | 是 |
| hisi-docs-mcp | 9802 | `2025-06-18` | `hisi-docs-mcp` | 7 | 否 |
| hisi-docs-search | 9803 | `2024-11-05` | `hisi_docs_search_chunk` | 1 | 否 |

**合计 20 个工具。**

工具清单（直连抓取）：

- **9800（11）**：`search_graph`、`query_graph`、`trace_path`、`get_code_snippet`、`get_graph_schema`、
  `get_architecture`、`search_code`、`list_projects`、`index_status`、`check_index_coverage`、`detect_changes`
- **9801（1）**：`codegraph_explore`
- **9802（7）**：`hisi_docs_kg_query`、`hisi_docs_kg_timeline`、`hisi_docs_kg_stats`、
  `hisi_docs_find_tunnels`、`hisi_docs_search`、`hisi_docs_get_drawer`、`hisi_docs_reconnect`
- **9803（1）**：`hisi_docs_search`

> ⚠️ **`hisi_docs_search` 同时存在于 9802 与 9803** —— 这是真实重名，不是假想风险。
> 它决定了 `上游名__工具名` 前缀命名空间是**上线必需**，而非可选优化。

## 3. 经网关的对比结果

网关以生产模式运行（业务端口 8099），配置即 `application.yml` 默认值。

### 3.1 工具聚合：20/20 全中，0 degraded

网关启动日志：

```
Upstream 'hisi-code-mcp'    initialized, negotiated protocolVersion=2025-03-26, session=<bff844cb...>
Upstream 'hisi-codegraph'   initialized, negotiated protocolVersion=2024-11-05, session=<c622097a...>
Upstream 'hisi-docs-mcp'    initialized, negotiated protocolVersion=2025-03-26, session=<none>
Upstream 'hisi-docs-search' initialized, negotiated protocolVersion=2024-11-05, session=<none>
Registered 11 tool(s) from upstream 'hisi-code-mcp'
Registered 1  tool(s) from upstream 'hisi-codegraph'
Registered 7  tool(s) from upstream 'hisi-docs-mcp'
Registered 1  tool(s) from upstream 'hisi-docs-search'
Upstream tool name 'hisi_docs_search' is ambiguous across 2 upstreams;
  candidates: [hisi-docs-mcp__hisi_docs_search, hisi-docs-search__hisi_docs_search]
MCP gateway registered 39 tool(s) from 4 upstream(s), degraded=[]
```

`/gateway/status` 自检结果：

```
toolCount=20
healthy=hisi-code-mcp, hisi-codegraph, hisi-docs-mcp, hisi-docs-search
degraded=(空)
```

**逐项对齐**：20 个上游工具全部以 `上游名__原始工具名` 出现在网关上，无遗漏、无重复覆盖。

### 3.2 同名工具不串台（关键验证）

用**同名** `hisi_docs_search` 分别调两个上游，结果确实不同，证明前缀路由生效：

| 调用名 | 上游 | 实际结果 |
|---|---|---|
| `hisi-docs-mcp__hisi_docs_search` | 9802 | `total_before_filter: 15` |
| `hisi-docs-search__hisi_docs_search` | 9803 | `total_before_filter: 5`，`source_file: README.md` |

同名工具若没有前缀，必然丢一个或串台；这里两者各自独立且数据不同。

### 3.3 裸名（不带前缀）调用与主备路由

| 场景 | 结果 |
|---|---|
| 唯一命中（如 `codegraph_explore`） | ✅ 直接转发成功 |
| **重名工具**（`hisi_docs_search`） | ✅ 按上游 `priority` **走主备**：主（9803）可用即走主；主传输层失败自动切备（9802），agent 无感知 |
| 两个带前缀名 | ✅ 各自直连对应上游（用于指名对比），结果与直连一致 |
| 完全不存在的名字 | ✅ 协议层回 `-32602`（非 5xx） |

主备口径（2026-10-22 定稿）：`hisi-docs-search`(9803) `priority: 1` 为主、`hisi-docs-mcp`(9802) `priority: 2` 为备。
`GET /gateway/status` 的 `failoverRoutes` 可直接看到排序。

**真机验证（dev 环境，2026-10-22）**：

| 场景 | 网关日志 / 调用结果 |
|---|---|
| 4 上游全活 | `Failover route registered for 'hisi_docs_search': [hisi-docs-search(p1), hisi-docs-mcp(p2)]`；裸名调用 → `total_before_filter=5`（**9803，主**） |
| 把 9803 指向 `127.0.0.1:1` | `Upstream 'hisi-docs-search' is degraded`；裸名调用 → `total_before_filter=15`（**9802，备**），`isError=false`，agent 无感知 |
| 带前缀名（对照） | `hisi-docs-search__…` → 5；`hisi-docs-mcp__…` → 15（各自直连，不串台） |

**自动化验证**（测试环境用 stub 复刻同样语义）：正常调用落到 priority 最高的 stub；用 kill switch
把主及其同端点备份打成 503 后，调用自动落到下一个健康候选，日志留下
`Failover: upstream 'stub-beta' unavailable ... trying next candidate: stub-delta` →
`... trying next candidate: stub-alpha`，最终成功。

### 3.4 失败隔离

把 `hisi-codegraph` 指向必然连不上的 `http://127.0.0.1:1/mcp` 后重启网关：

```
Registered 11 tool(s) from upstream 'hisi-code-mcp'
Upstream 'hisi-codegraph' is degraded (tools unavailable):
  AnnotatedConnectException: Connection refused: getsockopt: /127.0.0.1:1
Registered 7  tool(s) from upstream 'hisi-docs-mcp'
Registered 1  tool(s) from upstream 'hisi-docs-search'
MCP gateway registered 37 tool(s) from 3 upstream(s), degraded=[hisi-codegraph]
```

- 端点**没有被拖死**：其余 3 个上游 19 个工具照常列出与调用（实测调用成功）。
- 故障上游被准确标记为 `degraded`，可在 `/gateway/status` 看到。
- 调用故障上游的工具时回**协议错误**而非 5xx，agent 可自行处理。

## 4. 自动化测试（22 个用例，全绿）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `McpGatewayServiceSmokeTest` | 6 | 独立启动、MCP 协议层装配、`%test` 上游隔离、反向隔离（无 core/iam 路由）、root-path 唯一、探针不在业务端口 |
| `ToolRegistryTest` | 7 | 前缀命名空间、同名不塌缩、保留原始名、健康/degraded 记录、**主备顺序按 priority**、刷新幂等 |
| `McpProtocolEndpointTest` | 9 | 真实 MCP 协议 `tools/list` 聚合、描述来源标注、`tools/call` 路由正确性、**裸名走主 / 主挂 failover 到备**、别名可调用、未知工具错误码、状态接口 |

```
BUILD SUCCESSFUL
22 tests, 0 failures, 0 errors
```

⚠️ **failover 用例的假绿风险已专门排除**：若 kill switch 未生效，调用会因主上游健康而"恰好成功"。
已核对日志确实出现两次切换（`stub-beta` → `stub-delta` → `stub-alpha`），确认真的走了降级链。

⚠️ 测试**完全不依赖外网**：`application.yml` 的 `%test` 段把上游换成同进程的假上游
（`StubUpstreamResource`），可离线、可重复跑。

## 5. 验证命令

```bash
# 全部测试（离线可跑）
./gradlew :hisilicon-knowledge-mcp-gateway:test '-Dquarkus.native.enabled=false'

# 只验证装配
./gradlew :hisilicon-knowledge-mcp-gateway:quarkusAppPartsBuild \
  '-Dquarkus.native.enabled=false' '-Dquarkus.package.jar.enabled=true'

# 对真实上游手工验收（生产模式）
./gradlew :hisilicon-knowledge-mcp-gateway:quarkusAppPartsBuild \
  '-Dquarkus.native.enabled=false' '-Dquarkus.package.jar.enabled=true'
cd hisilicon-knowledge-mcp-gateway/build/quarkus-app
java "-Dquarkus.http.port=8099" "-Dquarkus.management.port=9099" -jar quarkus-run.jar
```

> ⚠️ PowerShell 下每个 `-D` 都要加引号，否则会被当成类名（`ClassNotFoundException: /http/port=8099`）。

然后按 MCP 协议访问 `http://localhost:8099/hisilicon-knowledge-mcp/mcp`：
`initialize` → `notifications/initialized` → `tools/list` / `tools/call`。

## 6. 验收结论

| 验收标准（方案 §4 阶段一） | 结果 |
|---|---|
| agent 客户端配置网关单端点后，能列出全部上游工具 | ✅ 20/20 |
| 且能成功调用 | ✅ 逐上游实测通过 |
| 任一上游停机，其余工具仍可用 | ✅ degraded 隔离生效 |
| 重名工具（`hisi_docs_search`）有默认可用入口且能容灾 | ✅ 主备路由：9803 主 / 9802 备；真机验证 9803 挂掉后自动落 9802 |
| 网关 root-path/探针符合平台规范 | ✅ 自动化测试断言；CCE 侧待部署核对 |

**遗留（不阻塞阶段一结论）**：

1. **CCE 部署与 APIG 前缀路由**未执行（代码/镜像定义已就绪）。
2. **native 镜像构建**未验证（仅验证 JVM 模式）。
3. **公网 EIP 9800–9803 无鉴权暴露**——实测公网可直连，建议运维按方案 §5 收敛。
4. **阶段二鉴权口径待澄清**：方案原写"验证华为账号 token（JWKS）"，
   而仓库现状是平台自签 JWT（`PlatformJwtVerifier` 本地验签）。