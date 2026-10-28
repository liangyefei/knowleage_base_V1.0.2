/**
 * 面向 agent 的入站 MCP 聚合网关（hisilicon-knowledge-mcp-gateway）。
 *
 * <p><b>方向</b>：入站。外部 agent（IDE、Agent 平台、第三方集成）通过 MCP 协议
 * 调用模型侧工具；网关把模型侧**多个** MCP 工具服务聚合成**一个** Streamable HTTP 端点。
 * 与"平台出站调用模型的 MCP"方向相反：后者已以 REST 形式存在于 {@code commons-ai}
 * （dual-mcp {@code haisi-dual-mcp:9789}），后续的模型侧 MCP 协议客户端也应落在
 * {@code commons-ai}，不要放进本模块。</p>
 *
 * <p><b>为什么单独一个服务</b>：MCP 的会话模型（初始化握手、能力协商、工具注册与调用）
 * 与平台既有的 REST 门面差异大；且接入方是机器而非浏览器前端，鉴权面
 * （OIDC 资源服务器 + 后续按 agent 授权的 scope）与前台/后台的用户面不同。
 * 混进 {@code hisilicon-knowledge-core-service} 会把"给 agent 开的门"和"给用户开的门"焊在一起。</p>
 *
 * <p><b>路由与端口</b>：{@code /hisilicon-knowledge-mcp/**}（独立前缀——APIG 按前缀转发，
 * 必须与 core 的 {@code /hisilicon-knowledge}、iam 的 {@code /hisilicon-knowledge-iam} 两两不同）。
 * 业务端口 8080、探针 9000；MCP 端点在 {@code {root-path}/mcp}。</p>
 *
 * <p><b>实现口径</b>：MCP 协议层（JSON-RPC 编解码、initialize 协商、Streamable HTTP 传输、
 * 会话、tools/list 与 tools/call 派发）**全部由官方 Quarkiverse 扩展
 * {@code quarkus-mcp-server-http} 承担**，本模块用它的**编程式 {@code ToolManager} API**
 * 做动态注册与转发——因为上游工具清单是运行期拉取的，注解式 {@code @Tool} 要求编译期静态方法，
 * 不适用于网关场景。本模块只写"拉清单 → 注册 → 转发"这一段聚合逻辑。</p>
 *
 * <p><b>鉴权口径（阶段 2，本轮未启用）</b>：入站请求将携带<b>平台签发的 JWT</b>
 * （与前台/后台同一套令牌，由 {@code hisilicon-knowledge-iam-service} 签发）。网关侧用
 * {@code commons-oidc} 的 {@code PlatformJwtVerifier} 做本地 RS256 验签 + exp / {@code type}
 * 校验后再信任 {@code sub} / {@code roles} claim，按角色或后续的 agent scope 判定工具可见性。
 * ⚠️ <b>不要</b>复用 {@code commons-web} 的 {@code AuthenticationFilter}——它目前只做 Base64
 * 解析、不验签（P0 旧账）。</p>
 *
 * @since 拆分后新增；阶段一（聚合转发）已落地
 */
package com.hisilicon.knowledge.platform.mcp;