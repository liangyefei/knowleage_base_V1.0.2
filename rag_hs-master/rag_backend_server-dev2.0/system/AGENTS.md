# system 模块（身份与访问域）

用户、角色、权限、登录、令牌、RSA 密钥对。对应 DDD 的**支撑域**，部署单元为 `hisilicon-knowledge-iam-service`。

## 边界

拆分（`feat/split-domain-services`）后本模块**只剩身份与访问**：

- 字典域（45 个类）已搬到 `knowledge` 的 `knowledge.dict.**`
- 敏感词域（57 个类）已搬到 `llm` 的 `llm.sensitive.**`
- 因此 `build.gradle.kts` 已去掉 POI / awt / commons-csv / commons-dict / commons-sensitive / commons-abac

**不要再往本模块加业务域代码**：它只能依赖 `commons-*`，不得依赖 `knowledge` / `llm`。
反向隔离由 `hisilicon-knowledge-iam-service` 的冒烟测试守住（请求 `/system/dict/**`、`/system/sensitive/**` 必须 404）。

## 对外接口

- `PublicKeyResource`（`GET /publicKey`）与 `UserResource`（`POST /user/login`、`/refresh`、`/logout`），
  **都没有 `@Authenticated`**（登录前必须可访问），路径与拆分前逐字一致。
- **OneAccess OIDC**（`OauthResource`）：
  - `GET /oidc/login/huawei`（`@Authenticated`，触发 quarkus-oidc code flow）
  - `GET /oidc/callback/huawei`（与 `quarkus.oidc.authentication.redirect-path` 对齐）
  - `POST /oidc/token`：用一次性 code 换平台令牌对
  - `GET /oidc/logout/huawei`：吊销平台令牌 + 清 OIDC 会话 + 302 到登录页
- **角色管理**（`RoleResource` / `UserRoleResource`，全部 `@Authenticated`，写操作要求 `sys_admin`）：
  `GET/POST/PUT/PATCH/DELETE /system/roles/**`、`GET/PUT /system/users/{username}/roles`。
  内置角色（`is_preset=true`：`community`、`sys_admin`）**禁止删除、禁止改状态、role_key 不可变**；
  用户授权时内置 `community` 角色自动保留。

## 身份与令牌

- 平台 JWT 由 `UserEntity` 签发：`sub`、`iss`、`iat`、`exp`、`type=access|refresh`、
  `roles`（`roles.role_key` 列表，恒存在）、`source`（`local` / `oidc`）。
- 本地账号（RSA 口令）与 OIDC 账号共用同一 refresh/吊销机制（Redis）。
- **刷新时外部账号复核**：`users.source='oidc'` 的用户刷新令牌前，必须用 Redis 里的 OneAccess
  access token 调 `commons-oidc` 的 `ExternalAccountValidator`（fail-closed），取不到即拒绝续期。
- **每请求用户状态校验**：`commons-web` 的过滤器通过 `UserStatusCheckerImpl` 查状态（Redis 缓存
  `user:status:{username}`，TTL 600s），禁用用户即使持有效 token 也 401。

## 配置与密钥

- 本模块读取 `rsa.public-key` / `rsa.private-key`，以及 `oidc.provider.huawei.userinfo-uri`
  （外部账号刷新复核端点，空值即 fail-closed）；不读任何 `kb.*` 键。
- `publicKey.pem` / `privateKey.pem` 只在本模块的 resources 里 —— 也就是说只有 `hisilicon-knowledge-iam-service` 有私钥。
- `build.gradle.kts` 里 `privateKey.pem` 的镜像排除只在 `-Dquarkus.native.enabled=true` 时生效，
  而 `application.yml` 默认 `quarkus.native.enabled: true`：**两者口径不一致**（见评估文档的 P1），
  本地跑 native 构建前先确认私钥没被打进镜像。

## 测试注意

`src/test/resources/application.properties` 里必须保持：

- `quarkus.devservices.enabled=false`（否则 quarkus-oidc 会拉起 Keycloak Dev Services 拉镜像，测试挂起）
- `quarkus.oidc.tenant-enabled=false`（测试没有 OneAccess 租户）
- OIDC 相关的 `@QuarkusTest` 用 `QuarkusMock.installMockForType` 替换**实现类**（`OneAccessAccountValidator`），
  不能替换接口（要求 mock 可赋值给 bean 类型）。

### 刷新链路的两个运行期约束（联调踩过，别改回去）

1. **DB 访问必须收敛在一次事务里**：`OidcUserCommandService#loadRefreshContext`（`@WithTransaction`）
   一次取全"账号来源 + 角色"；同一请求内先后开多个 reactive session/transaction 会踩拦截器状态冲突
   （`CompositeException` / `UnknownServiceException`）。
2. **阻塞的外部复核必须在事务之外**：OneAccess 会话复核是同步 HTTP 且会切到 worker 线程，
   若套在事务会话里会触发 `HR000069`（会话跨线程使用）。
   因此 `UserCommandService#refresh` **不加** `@WithTransaction`。

### 本地真实链路验证

用本地 Keycloak 跑通整条 OIDC 链路（含建号/刷新复核/登出/禁用拒绝）的完整步骤见
[本地用 Keycloak 验证 OneAccess OIDC 链路](../../docs/deploy/local-keycloak-oidc-test.md)。
⚠️ 该文档记录了 dev 模式热重载在"改事务注解 / 新增嵌套类"后会半残的坑：这类改动联调时必须完整重启。

## 后续（本轮不做）

- 权限判定引擎（`commons-abac` 目前是空壳）；角色管理目前只做最小 `sys_admin` 守卫。
- `commons-web` 过滤器的不验签 P0 收口（`commons-oidc` 已提供 `PlatformJwtVerifier` 但尚未接线）。
