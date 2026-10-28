# OIDC 通用组件（commons-oidc）

面向 **OneAccess（华为云应用身份管理服务，标准 OIDC）** 的通用组件。已实现，不再是纯占位。

## 当前内容

| 类 | 职责 |
| --- | --- |
| `PlatformJwtVerifier` | **资源服务器侧**：用 `rsa.public-key`（未配置回退 classpath `publicKey.pem`）本地做 RS256 验签 + exp + 自定义 claim `type` 校验；失败 fail-closed（A0342 无效 / A0343 过期） |
| `ExternalAccountValidator` | **端口**：外部账号当前是否仍有效的校验能力（fail-closed，A0224 失效 / C0114 提供方调用失败） |
| `OneAccessAccountValidator` | 调用 OneAccess 用户信息接口校验外部 access token；端点为配置项 `oidc.provider.huawei.userinfo-uri` |
| `ExternalAccount` | 外部账号身份模型（externalId / username / displayName / email / phone / picture / rawClaims） |
| `OidcClaims` | OIDC / OneAccess 标准 claim 名常量（与门户"映射配置"一一对应） |

设计说明：**OIDC 授权码流程、令牌交换、ID Token 验签由 `quarkus-oidc` 扩展承担**（配置在
`hisilicon-knowledge-iam-service`），本模块**不自研 OAuth 客户端**；本模块只放"可被多个服务复用的
令牌校验能力"与"外部账号有效性校验端口"。

## 边界

- 纯库模块：**不应用 `io.quarkus` 插件**，靠 `src/main/resources/META-INF/beans.xml`
  成为 bean archive 并被 Quarkus 索引（与其它 commons 模块一致）。
- 不放业务规则（角色/权限判定、用户 CRUD、建号）：那是 `system` 模块与 `hisilicon-knowledge-iam-service`。
- 不重复造 web 层：HTTP 拦截器仍归 `commons-web`，本模块只提供可被其调用的校验能力。

## 与 MCP 网关的关系

`hisilicon-knowledge-mcp-gateway` 未来做入站鉴权时，**直接用 `PlatformJwtVerifier`**
校验平台 JWT，按 `roles` claim / 后续 agent scope 判定工具可见性；不要复用
`commons-web` 的 `AuthenticationFilter`（它目前不验签）。

## 仍未解决的已知问题

`commons-web` 的 `AuthenticationFilter.filter()` 与 `JwtUtils` 目前**只做 Base64 解析**
（取 `sub` / `exp`），**不校验签名**，全仓库也没有任何 `mp.jwt.verify.*` 配置。
这意味着当前任何自签的 JWT 都能通过 `@Authenticated` 接口。

**本轮只新增了验签能力（`PlatformJwtVerifier`），尚未替换过滤器的不验签路径**——
替换是独立的 P0 收口工作，改 `AuthenticationFilter` 时需同时评估本地 RSA 登录链路。
详见《海思知识库架构设计-服务拆分评估.md》的 P0 条目。
