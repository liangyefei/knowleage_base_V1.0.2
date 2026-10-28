# MCP 网关（hisilicon-knowledge-mcp-gateway）

**已落地（阶段一：聚合转发）**：面向 agent 的**入站** MCP 聚合网关，把模型侧多个 MCP 工具服务
聚合成**一个** Streamable HTTP 端点对外暴露。

## 职责

外部 agent（IDE、Agent 平台、第三方集成）配**一份** `mcpServers` 指向网关，即可用到全部模型侧工具。

- 业务端口 **8080**、探针端口 **9000**（与 core / iam 同值——CCE 里各自独立容器，同值不冲突）。
- 路由前缀 **`/hisilicon-knowledge-mcp`**，MCP 端点 = **`/hisilicon-knowledge-mcp/mcp`**
  （扩展默认 `{root-path}/mcp`；另自动提供已废弃的 SSE 变体 `/mcp/sse`）。
- ⚠️ 前缀必须与 core（`/hisilicon-knowledge`）、iam（`/hisilicon-knowledge-iam`）**两两不同**：
  测试/生产经华为云 APIG 按**前缀**转发到 CCE 内的 Service，同前缀下 APIG 分不开（评估文档 §10.4）。
- 运维自检面（非 MCP 协议）：`GET /gateway/status`、`POST /gateway/refresh`。

## 与"模型侧 MCP"的区别（最容易搞混的一点）

| | 方向 | 现状 | 归属 |
| --- | --- | --- | --- |
| 本模块 `hisilicon-knowledge-mcp-gateway` | **入站**：agent → 平台 | 阶段一已落地 | 本模块 |
| 平台 → 模型 | **出站**：平台 → dual-mcp | 已存在，REST（`:9789`） | `commons-ai` |

模型侧若也要走 MCP 协议，客户端放 `commons-ai`，**不要**放进本模块。

## 实现口径：协议层用官方扩展，只写聚合逻辑

MCP 协议面（JSON-RPC 编解码、`initialize` 协商、Streamable HTTP 传输、会话、
`tools/list` 与 `tools/call` 派发）**全部由官方 Quarkiverse 扩展 `quarkus-mcp-server-http` 承担**。

本模块用它的**编程式 `ToolManager` API**（`newTool` / `setInputSchema` / `setAsyncHandler` / `removeTool`），
**不是**注解式 `@Tool`：上游工具清单是**运行期拉取**的（上游会升级版本、增减工具），
注解式要求编译期静态方法，不适用于网关。

```
启动 → 并发拉取各上游 tools/list → 按 上游名__工具名 注册进 ToolManager → tools/call 按前缀转发
```

### ⚠️ 版本必须锁 2.0.3

`quarkus-mcp-server-core` 的 `META-INF/quarkus-extension.properties` 里声明了
`requires-quarkus-version`，实测：

| 扩展版本 | requires-quarkus-version | 与本项目 3.39.2 |
| --- | --- | --- |
| **2.0.3** | `[3.33,)` | ✅ 兼容（已实测启动 + 全测试通过） |
| 2.1.0.Beta1 | `[3.40,)` | ❌ 会被拒 |

升级本项目 Quarkus 平台版本、或升级本扩展前，先核对目标版本的 `requires-quarkus-version`。

## 上游配置（增减不发版）

`application.yml` 的 `mcp-gateway.upstreams`，url 走环境变量（对齐 `KB_KG_PUBLIC_URL` 风格）：

```yaml
mcp-gateway:
  name-separator: "__"
  connect-timeout: 5s
  read-timeout: 60s
  load-on-startup: true
  upstreams:
    - name: hisi-code-mcp
      url: ${MCP_UPSTREAM_CODE_URL:http://139.9.104.64:9800/mcp}
    # hisi-codegraph :9801 / hisi-docs-mcp :9802 / hisi-docs-search :9803 同理
```

- **阶段三**：把 url 换成 CCE 内网地址并关闭公网 EIP 9800–9803（只改这里，不改代码）。
- ⚠️ 上游清单是**列表结构**，只能用 **yaml** 表达；写成 `.properties` 的 `upstreams[0].name`
  会因重复键被判 duplicate value 而配置校验失败（实测 SRCFG01007 + 起不来）。
- ⚠️ `Upstream` 必须是 **`interface` 嵌套组，不能用 `record`**：SmallRye 的集合元素只支持嵌套接口，
  用 record 会报 `SRCFG00013: No Converter registered for class ...$Upstream`（实测踩过）。

## 命名与路由规则

- 暴露名 = **`上游名__原始工具名`**（如 `hisi-docs-mcp__hisi_docs_search`）。
  ⚠️ 前缀**不是装饰**：`hisi_docs_search` **同时存在于 9802 与 9803**（实测），
  没有前缀就无法消歧。
- `tools/call`：优先按前缀精确匹配；不带前缀的**裸名**也可调用。
- `inputSchema` **原样透传**上游 JSON，网关不改写（避免 agent 侧 schema 漂移）。
- 工具描述后附加 `[via MCP gateway | upstream: X | tool: Y]`，便于 agent 理解与排障。

### 同名工具的主备路由（failover）—— 2026-10-22 定稿口径

重名工具不再"报候选清单"，而是按**上游 priority** 选主、主不通自动走备：

| 场景 | 行为 |
|---|---|
| 裸名 `hisi_docs_search` | 按 priority 选主（**9803 为主，9802 为备**），主挂自动切备，agent 无感知 |
| 两个带前缀名 | 照旧各自直连对应上游（用于指名压测/对比） |
| 无重名的工具（9800/9801 全部） | 行为不变，priority 用不上 |

配置（`application.yml`）：

```yaml
upstreams:
  - name: hisi-docs-mcp
    url: ${MCP_UPSTREAM_DOCS_URL:http://139.9.104.64:9802/mcp}
    priority: 2      # 备
  - name: hisi-docs-search
    url: ${MCP_UPSTREAM_SEARCH_URL:http://139.9.104.64:9803/mcp}
    priority: 1      # 主（数字越小越优先）
```

- **priority 只在同名工具分组内比较**，所以给不重名的上游配/不配都没有副作用。
- 未配置时默认 `0`，同 priority 按配置文件里的**声明顺序**排。
- 主备顺序可在 `GET /gateway/status` 的 `failoverRoutes` 里直接看到。

**failover 触发条件（有意收窄）**：

- ✅ **传输层失败**才切下一个：连不上、超时、HTTP 非 2xx。
- ❌ **上游业务应答不切**：JSON-RPC `error`（如"工具不在允许列表"）表示上游已正常应答，
  切备大概率同样失败，还会掩盖真实问题。
- 装载期：`degraded` 的上游直接从候选里跳过（其 `tools/list` 已失败，工具状态未知）；
  若全部候选都不可用，仍按序试一遍（degraded 集合可能过期），最终回工具级错误并列出各次尝试。

**回到主上游**：网关不做自动回切探测。主恢复后调一次 `POST /gateway/refresh`（或重启）即按 priority 重新选主。

### ⚠️ 别名无法"隐藏但可调用"（官方扩展的硬限制）

官方扩展的 `ToolFilter` 同时作用于 `tools/list` **与** `tools/call` 派发。
把别名设为不可见后，**调用它会被拒**（`Invalid tool name`），即"隐藏但可调用"表达不出来（实测）。
故只能在两者间取舍：

- **当前选择：保留别名并列出**（dev 环境 20 个上游工具 → 列表 39 条）。
  理由是网关的职责是让 agent 无痛用上工具，裸名调用很常见，且重名裸名现在能**主备自动切换**。
- 若将来确认列表冗余更有害，删掉 `ToolRegistry#registerAliases` 及其调用即可回到"仅前缀名"（正好 20 条）。

## 失败隔离

- 单个上游 `tools/list` 失败/超时 → 只跳过该上游，其余工具照常注册，端点不被拖死；
  失败者记入 `degraded`，可在 `/gateway/status` 看到。
- 装载是**异步**的（订阅后立即返回），上游慢不会拖住启动导致探针失败。
- 单上游 `tools/call` 失败 → 回**工具级错误结果**（不是 5xx），agent 能看到原因并自行决定重试。
- 上游工具清单**不缓存**：启动拉一次 + `POST /gateway/refresh` 手动刷新
  （上游升级版本后调一次即可，不必重启/发版）。

## 鉴权口径（阶段 2，本轮**未启用**）

入站请求将携带**平台签发的 JWT**（与前台/后台同一套令牌：华为账号 / OneAccess 登录后由
`hisilicon-knowledge-iam-service` 签发，内含 `sub`、`roles`、`source` claim）。网关侧必须：

1. 用 `commons-oidc` 的 `PlatformJwtVerifier` 做**本地 RS256 验签** + exp + `type` 校验，
   通过后才信任 `sub` / `roles`；
2. 按角色或后续的 **agent scope** 判定工具可见性，不要按用户角色一刀切；
3. **不要**复用 `commons-web` 的 `AuthenticationFilter`——它目前只做 Base64 解析、不验签（P0 旧账）。

`application.yml` 里的 `rsa.public-key` 就是为此预留（阶段一留空不影响启动）。

> ⚠️ 实施阶段 2 前需澄清一处不一致：对接方案 §4 写的是"验证**华为账号签发**的 token（JWKS/iss/aud）"，
> 而本仓库现状是**平台自签 RS256 JWT**（`PlatformJwtVerifier` 用 `rsa.public-key` 本地验签）。
> agent 到底拿华为账号 token 还是平台 JWT，需要先定。

## token 统计（阶段 3，本轮**未做**）

按日统计账号 token 使用、管理员可改单账号限额。
**埋点位置已经预留**：`ToolRegistry#forward` 是工具调用的**唯一收口点**，
后续计量（初步统计即可，不需精确）落在这里，本轮不引入任何计量代码。

## 构建与测试

```bash
./gradlew :hisilicon-knowledge-mcp-gateway:quarkusDev '-Dquarkus.native.enabled=false'   # 本地开发
./gradlew :hisilicon-knowledge-mcp-gateway:test '-Dquarkus.native.enabled=false'         # 全测试
./gradlew :hisilicon-knowledge-mcp-gateway:quarkusAppPartsBuild '-Dquarkus.native.enabled=false' '-Dquarkus.package.jar.enabled=true'   # 只验证装配
./gradlew :hisilicon-knowledge-mcp-gateway:build '-Dquarkus.native.enabled=true'         # native（默认容器构建）
```

⚠️ 本服务是 `package.jar.enabled: false` + `native.enabled: true`，只传 `-Dquarkus.native.enabled=false`
会让 `quarkusAppPartsBuild` 报 `No artifact results were produced`（不是代码问题），需补
`-Dquarkus.package.jar.enabled=true`。

⚠️ 本机同时起多个服务时覆盖端口（`-D` 必须在 `-jar` 之前；PowerShell 里要给每个 `-D` 加引号，
否则会被当成类名）：
```
java "-Dquarkus.http.port=8099" "-Dquarkus.management.port=9099" -jar quarkus-run.jar
```

### 测试（22 个用例，全绿）

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `McpGatewayServiceSmokeTest` | 6 | 独立启动、MCP 协议层装配、`%test` 上游隔离、反向隔离（无 core/iam 路由）、root-path 唯一、探针不在业务端口 |
| `ToolRegistryTest` | 7 | 前缀命名空间、同名工具不塌缩、保留原始名、健康/degraded 记录、**主备顺序按 priority**、刷新幂等 |
| `McpProtocolEndpointTest` | 9 | 真实 MCP 协议 `tools/list` 聚合、描述来源标注、`tools/call` 路由正确性、**裸名走主 / 主挂 failover 到备**、别名可调用、未知工具错误码、状态接口 |

⚠️ **测试必须脱离 dev 网络**：`application.yml` 的 `%test` 段把上游换成同进程的
`StubUpstreamResource`。三个实测坑（改测试配置前必读）：

1. 测试 resources 里的 `application.yml` / `application.properties` **盖不住**主 `application.yml`
   的 `mcp-gateway.upstreams`（主配置源 ordinal 更高）；
2. 索引列表是**按索引合并、不是整体替换**——`%test` 只给 2 项时，base 的索引 2..3 仍存活，
   测试会**真的去连 139.9.104.64**（曾出现"意外通过"）。故 `%test` 必须覆盖到 base 的全部 4 个索引；
3. stub 的 url **必须带 root-path**（`http://localhost:8089/hisilicon-knowledge-mcp/stub/alpha`），
   漏掉前缀会 404，表现为"上游全部 degraded"。

**故障注入**：`StubUpstreamResource.DISABLED` 集合里的路径段（`"alpha"`/`"beta"`）会让该 stub
直接回 **HTTP 503**（传输层失败），用于测试 failover 与 degraded 隔离。
用例负责在 `finally` 里清理，避免污染共享 Quarkus 实例的其他用例。

⚠️ `McpAssured.ToolsPage#findByName` 在**找不到时抛 `NoSuchElementException`**，不是返回 `null`；
断言"某工具不存在"要用 `page.tools().stream().anyMatch(...)`。

⚠️ **判定 failover 用例是否真跑到了切换**：光看"测试绿"不够——若 kill switch 没生效，
调用会因主上游健康而"恰好成功"，形成假绿。看日志里有没有
`Failover: upstream 'X' unavailable for tool 'Y', trying next candidate: Z`。

## 部署与联调（CCE 上线实测沉淀，2026-10-22）

### ⚠️ 客户端请求头：`Accept` 必须同时含两种媒体类型

MCP Streamable HTTP 规范的硬要求，由扩展强制执行（实测于生产端点）：

| 客户端 Accept 头 | 结果 |
|---|---|
| `application/json, text/event-stream` | ✅ 200 |
| `application/json`（缺 SSE） | ❌ **400** |
| `*/*` | ❌ **400** |
| 不带 Accept | ❌ **400** |

顺序无关。排查前端/网关 400 时**先看这个**——极易误判成"代理把请求吃了"。

### ⚠️ 多副本必须有会话亲和（sessionAffinity: ClientIP）

MCP 旧版（`2025-06-18`/`2024-11-05`）是**有状态**的：`initialize` 建立的会话只存在单个 Pod 内存。
**多副本 + 无亲和 = 后续请求被轮询到别的 Pod，间歇 404**（实测 3 次里 2 次 404；加亲和后 5/5 全通）。

Service 上必须配：

```yaml
sessionAffinity: ClientIP
sessionAffinityConfig:
  clientIP:
    timeoutSeconds: 10800   # 3h，对齐桌面客户端长会话
```

两个已知缺口（心里有数即可）：

1. **滚动更新/重启后亲和失效**——发版期间 MCP 短暂不可用，是当前架构固有代价；
2. **同一出口 IP 的用户粘到同一 Pod**——统一 NAT 出口时负载不均，内部工具规模可接受。

**根治路径（无需改代码）**：MCP `2026-07-28` 起支持无状态模式（每个请求自带 `_meta`/协议头，
无会话），扩展 2.0.x **在同一端点自动双模**——客户端协商到新版本即走无状态、天然多副本；
旧客户端继续走有状态 + 亲和。Claude Code / Cursor / Trae / Hermes 等客户端升级跟上规范后自动受益。
另外注意：**2.0.3 不支持把会话外置到 Redis**（配置参考里无任何会话存储项），别往这个方向设计。

### 验收命令（经前端转发，从外部验证全链路）

前端按**前缀原样转发** `/hisilicon-knowledge-mcp` 到 Service（nginx `proxy_pass` 不带尾斜杠，
或显式补全前缀——带尾斜杠会吃掉前缀导致后端 404）。

```powershell
# 1) initialize（必须带正确的 Accept，见上）
$r = Invoke-WebRequest -Uri "http://<host>/hisilicon-knowledge-mcp/mcp" -Method Post `
  -Headers @{ "Content-Type"="application/json"; "Accept"="application/json, text/event-stream" } `
  -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"p","version":"1"}}}'
$sid = $r.Headers['mcp-session-id']

# 2) 用返回的 session 调 tools/list —— 这一步是会话亲和是否生效的试金石
#    （间歇 404 = 亲和没生效/多副本轮询到了别的 Pod）
```

运维自检面**不经过 MCP 协议**，直接看上游健康：`GET /hisilicon-knowledge-mcp/gateway/status`
（`healthyUpstreams` / `degradedUpstreams` / `failoverRoutes`）。上游连不上先看这里，不用猜。

### 上游地址的配置纪律

`application.yml` 里默认值**故意写成 `localhost`**——真实地址不提交代码库（2026-10-22 定）。
CCE 上必须用环境变量注入，否则 `localhost` 指向 Pod 自己、上游全 degraded：

```
MCP_UPSTREAM_CODE_URL=http://haisi-dual-mcp:9800/mcp
MCP_UPSTREAM_CODEGRAPH_URL=http://haisi-dual-mcp:9801/mcp
# 9802/9803 模型组尚未部署，部署后注入并 POST /gateway/refresh 即可，无需重启
```

## 真实环境验证已做过什么（2026-10-22，dev 环境）

对 CCE dev 的 4 个真实上游跑通（生产模式 runner jar，业务端口 8099）：

- **聚合 20 个工具、0 degraded**：`hisi-code-mcp` 11 + `hisi-codegraph` 1 + `hisi-docs-mcp` 7 + `hisi-docs-search` 1，
  与直连 4 个上游逐一核对一致。
- **协议版本逐上游协商**：9800 与 9802 得 `2025-03-26`，9801 与 9803 得 `2024-11-05`
  （上游对版本不敏感；网关对外支持版本与对上游协商版本分别维护）。
- **同名工具不串台**：`hisi-docs-mcp__hisi_docs_search` 返回 15 条结果、
  `hisi-docs-search__hisi_docs_search` 返回 5 条不同结果——同名但确实打到不同上游。
- **重名裸名走主备**（真机验证）：
  - 4 上游全活：`Failover route registered for 'hisi_docs_search': [hisi-docs-search(p1), hisi-docs-mcp(p2)]`，
    裸名调用 → `total_before_filter=5`（**9803 主**）
  - 把 9803 指向 `127.0.0.1:1`：`Upstream 'hisi-docs-search' is degraded`，
    裸名调用 → `total_before_filter=15`（**9802 备**），`isError=false`，agent 无感知
  - 带前缀名作为对照：`hisi-docs-search__…` → 5、`hisi-docs-mcp__…` → 15，各自直连不串台
- **唯一裸名**（如 `codegraph_explore`）直接转发成功。
- **失败隔离**：把 `hisi-codegraph` 指向 `127.0.0.1:1` → 该上游 degraded，
  其余 3 个上游 19 个工具照常可用，调用也正常。

复现方式：改 `application.yml` 的 url 或注入 `MCP_UPSTREAM_*_URL` 环境变量后启动 runner jar，
再按 MCP 协议（`initialize` → `notifications/initialized` → `tools/list` / `tools/call`）访问
`http://<host>:<port>/hisilicon-knowledge-mcp/mcp`。

## 后续（未做）

- **阶段 2**：平台 JWT 验签接入（见上文"鉴权口径"）、审计（调用方/工具名/参数摘要/结果状态/耗时）、
  工具权限过滤（按身份过滤 `tools/list` 与 `tools/call`）、工具结果敏感词检查挂钩点（commons-sensitive 端口）。
- **阶段 3**：网络收口（上游切内网、关公网 EIP）+ **token 按日统计与管理员限额**（埋点在 `ToolRegistry#forward`）。
- native 镜像构建尚未在本轮验证（`native.enabled: true`，参数照抄 iam 的精简版；
  若报反射/资源缺失，按提示补，不要无条件复制 core 那套 POI/Tika 参数）。