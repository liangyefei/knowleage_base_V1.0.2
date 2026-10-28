# 本地用 Keycloak 验证 OneAccess OIDC 链路

> 目的：在没有 OneAccess 实例时，用本地 Keycloak 把 **授权码登录 → 自动建号 → 一次性 code 换平台令牌 →
> 刷新时外部账号复核 → 登出 → 登出/禁用后拒绝** 整条链路真实跑通。
> Keycloak 与 OneAccess 都是标准 OIDC（授权码 + RS256），差异只在端点地址与属性映射，链路代码完全一致。

## 0. 前置

- `podman`（本机已装）。PostgreSQL（`localhost:5432`，库 `hisilicon_knowledge`，schema `knowledge`）与 Redis（`6379`）已在跑。
- 数据库里已有 `users` / `roles` / `user_roles` / `external_identities` 表；若无，先按 `docs/sql/init.sql` 建表。

## 1. 播种内置角色（迁移脚本）

迁移脚本未写 schema 前缀，psql 需带 `search_path=knowledge`：

```powershell
Get-Content docs\sql\migration_023_oneaccess_oidc.sql -Raw |
  podman exec -e PGOPTIONS='-c search_path=knowledge' -i postgres psql -U postgres -d hisilicon_knowledge -v ON_ERROR_STOP=1
Get-Content docs\sql\migration_024_roles_builtin.sql -Raw |
  podman exec -e PGOPTIONS='-c search_path=knowledge' -i postgres psql -U postgres -d hisilicon_knowledge -v ON_ERROR_STOP=1
```

## 2. 起 Keycloak

```powershell
# 国内网络直连 quay.io 会卡住，用 daocloud 的 quay 镜像源
podman pull quay.m.daocloud.io/keycloak/keycloak:26.0
podman run -d --name keycloak -p 8180:8080 `
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin `
  quay.m.daocloud.io/keycloak/keycloak:26.0 start-dev
# 就绪判断：GET http://localhost:8180/realms/master → 200
```

## 3. 建 realm / client / user

```powershell
podman exec keycloak /opt/keycloak/bin/kcadm.sh config credentials `
  --server http://localhost:8080 --realm master --user admin --password admin
podman exec keycloak /opt/keycloak/bin/kcadm.sh create realms -s realm=platform -s enabled=true
podman exec keycloak /opt/keycloak/bin/kcadm.sh create clients -r platform `
  -s clientId=hisilicon-kb -s enabled=true -s protocol=openid-connect -s publicClient=false `
  -s standardFlowEnabled=true -s 'secret=kb-secret-123' `
  -s 'redirectUris=["http://localhost:8080/hisilicon-knowledge-iam/oidc/callback/huawei"]'
podman exec keycloak /opt/keycloak/bin/kcadm.sh create users -r platform `
  -s username=zhangsan -s enabled=true -s email=zhangsan@example.com `
  -s 'firstName=San' -s 'lastName=Zhang'
```

### ⚠️ 重定向 URI 只能登记**一个**（与 `redirect-path` 一字不差）

`quarkus.oidc.authentication.redirect-path` 的值会被 quarkus-oidc **原样**当作 `redirect_uri` 发出，
所以 IdP 侧登记的地址**必须与它逐字一致，且只需登记这一个**：

```
http://localhost:8080/hisilicon-knowledge-iam/oidc/callback/huawei
```

- 不用登记裸路径 `/oidc/callback/huawei`（不带 root-path）：那是早期试错配置的产物，
  请求路由不到 JAX-RS（全部挂在 root-path 下），实测回调 404。
- 不用登记 `/hisilicon-knowledge-iam/oidc/callback`（无 `/huawei`）：显式配了 redirect-path 后永远不会用到。
- 登记多余地址＝多余的可用回调入口，是纯粹的暴露面，别留。
- **反过来也一样严格**：登记的地址与实际发出的不一致时，Keycloak 直接报
  `无效的参数 : redirect_uri`（实测），OneAccess 同理——所以两边必须对齐后再联调。
- 端口/域名按**实际对外入口**填（CCE 上填 APIG 域名，本地联调才是 `localhost:8080`）。

⚠️ 设置密码用 **管理 REST**（`kcadm.sh set-password` 在无 TTY 时报 "Console is not active"）：

```powershell
$t = (Invoke-RestMethod -Method Post -Uri "http://localhost:8180/realms/master/protocol/openid-connect/token" `
  -Body @{client_id='admin-cli';grant_type='password';username='admin';password='admin'}).access_token
$uid = (podman exec keycloak /opt/keycloak/bin/kcadm.sh get users -r platform -q username=zhangsan |
  ConvertFrom-Json).id      # 或从 kcadm get users 输出里取 id
Invoke-RestMethod -Method Put -Uri "http://localhost:8180/admin/realms/platform/users/$uid/reset-password" `
  -Headers @{Authorization="Bearer $t"} -ContentType 'application/json' `
  -Body '{"type":"password","value":"zhangsan-123","temporary":false}'
```

## 4. 带环境变量启动 iam-service（dev 模式）

```powershell
$env:OIDC_TENANT_ENABLED='true'
$env:OIDC_AUTH_SERVER_URL='http://localhost:8180/realms/platform'
$env:OIDC_CLIENT_ID='hisilicon-kb'
$env:OIDC_CLIENT_SECRET='kb-secret-123'
$env:HUAWEI_USERINFO_URI='http://localhost:8180/realms/platform/protocol/openid-connect/userinfo'
.\gradlew.bat :hisilicon-knowledge-iam-service:quarkusDev --console=plain
```

就绪判断用**管理端口**：`GET http://localhost:9000/q/health` → 200
（业务端口 `8080/q/health` 是 404，这是设计如此）。

## 5. 用 curl 走完整链路

浏览器流程用 curl + cookie jar 三步模拟：
`/oidc/login/huawei`（302 → Keycloak authorize）
→ authorize（若已有 Keycloak 会话则直接 302 带回回调；否则返回登录表单）
→ POST `login-actions/authenticate`（表单 action 从 HTML 里取）
→ 回跳 `/hisilicon-knowledge-iam/oidc/callback/huawei?code=...`
→ quarkus-oidc 换 token，再 302 到 `frontend-callback-uri?code=<一次性 code>`
→ `POST /oidc/token {"code":"..."}` 换平台 JWT。

关键点：
- **一次性 code 60 秒有效、取出即删**，拿到后立即兑换，别中途看日志。
- 已登录时（Keycloak 会话 cookie 在）authorize 会**直接 302 回回调**，脚本要按"有无 location"分支处理，别再去解析登录表单。
- 应用侧 OIDC 会话 cookie 是会话级的，curl 新进程可能已丢；Keycloak 侧 `KEYCLOAK_IDENTITY` 有 10h Max-Age。

## 6. 验收检查点（本地已实测通过）

| 检查 | 期望 |
| --- | --- |
| 首次登录 | `/oidc/token` 返回平台 JWT，`sub=oa_xxx`、`roles=["community"]`、`source=oidc` |
| 数据库 | `users` 新增一条 `source='oidc'`、`external_identities` 有 `(oneaccess, <keycloak sub>)` 绑定、`user_roles` 绑 `community` |
| Redis | `oidc:at:oa_xxx` 存在（OneAccess access token，供刷新复核） |
| 二次登录 | 同一账号（`users` 计数不变），静默 SSO 无登录页 |
| 刷新 `/user/refresh` | 成功，新令牌仍 `source=oidc` + `roles`；内部真实调用 userinfo 复核 |
| 登出 `/oidc/logout/huawei` | 302 到登录页；Redis 中该用户令牌与 `oidc:at:*` 被清 |
| 登出后刷新 | 拒绝（`A0204 refresh token 已过期`） |
| 禁用用户（`users.status=2`）登录 | 拒绝：`A0202 用户账户被冻结`，不下发 code |
| 禁用用户刷新 | 拒绝：`A0202` |

## 7. dev 模式踩坑记录（重要）

**改了 `@WithTransaction` / `@WithSession` 注解、或新增嵌套类（如 record）后，
quarkusDev 的热重载会让这些增强/类加载半残**——表现为 `UnknownServiceException: ReactiveConnectionPool`、
`ClassNotFoundException: ...$RefreshContext`、或事务不生效。**联调时改这类代码必须完整重启 dev 进程**
（杀掉 gradle/java 进程后重新 `quarkusDev`），不要相信热重载的结果。

另外：同一请求内先后开多个 reactive session/transaction 会踩拦截器状态冲突（`CompositeException`），
所以刷新链路的 DB 访问**收敛在一次事务里**（`OidcUserCommandService#loadRefreshContext`），
OneAccess 的阻塞复核放在事务之外执行。