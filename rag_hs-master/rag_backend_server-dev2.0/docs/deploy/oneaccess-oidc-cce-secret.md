# OneAccess OIDC 对接 · CCE 部署片段

> 参考：华为云官方最佳实践 [通过 OIDC 协议单点登录至应用](https://support.huaweicloud.com/bestpractice-oneaccess/oneaccess_05_0023.html)
> 适用部署单元：`hisilicon-knowledge-iam-service`（OIDC RP 已落地：`quarkus-oidc` + `system` 域登录/建号）。

## 1. 创建 CCE Secret（存放 OIDC 凭据）

ClientSecret 属敏感信息，严禁进 ConfigMap / 代码仓库 / 镜像。用 `kubectl create secret` 创建：

```bash
kubectl -n <namespace> create secret generic oneaccess-oidc \
  --from-literal=OIDC_TENANT_ENABLED='true' \
  --from-literal=OIDC_AUTH_SERVER_URL='https://<oneaccess实例域名>' \
  --from-literal=OIDC_CLIENT_ID='<门户应用详情页的 ClientId>' \
  --from-literal=OIDC_CLIENT_SECRET='<门户应用详情页"启用"生成的 ClientSecret>' \
  --from-literal=HUAWEI_USERINFO_URI='https://<oneaccess实例域名>/api/v1/oauth2/userinfo' \
  --from-literal=OAUTH_FRONTEND_CALLBACK_URI='https://<对外域名>/oauth/callback' \
  --from-literal=OIDC_LOGOUT_REDIRECT_URI='https://<对外域名>/login'
```

### ⚠️ 两个地址的确切格式（有官方文档依据，别照抄博客）

| 变量 | 填什么 | 依据 |
| --- | --- | --- |
| `OIDC_AUTH_SERVER_URL` | `https://<实例域名>`（**不带** `/portal`、**不带** `/api/v1/...` 路径） | OneAccess 所有 API 端点均为 `https://{domain_name}/api/v1/oauth2/...`，故 base 就是域名根 |
| `HUAWEI_USERINFO_URI` | `https://<实例域名>/api/v1/oauth2/userinfo` | [获取用户信息](https://support.huaweicloud.com/intl/en-us/api-oneaccess/ShowUser_0.html)：`GET /api/v1/oauth2/userinfo`，头 `Authorization: Bearer {access_token}` |

- 实例域名可在 OneAccess 管理门户「设置 > 服务配置 > OIDC」页看到（另见
  [官方最佳实践](https://support.huaweicloud.com/bestpractice-oneaccess/oneaccess_05_0023.html)）。
  ⚠️ 门户展示的"认证地址"若带后缀（如 `/portal`），**以能通过 OIDC discovery 为准**——
  联调时先 `curl https://<域名>/.well-known/openid-configuration`，返回 JSON 说明 base 正确。
- token 端点为 `POST /api/v1/oauth2/token`（由 quarkus-oidc 经 discovery 自动取得，无需配置），
  见 [获取 Access Token](https://support.huaweicloud.com/intl/en-us/api-oneaccess/IssueOAuthAccessToken.html)。
- **userinfo 返回字段是非标准命名**：`id` / `userName` / `name` / `email` / `mobile`。
  应用侧 `OneAccessAccountValidator` 已同时兼容标准 OIDC 名（`sub`/`preferred_username`/`phone`）
  与这组 OneAccess 名，字段命名差异不会导致刷新复核误判。

- ⚠️ `OIDC_TENANT_ENABLED` **必须为 `true`**：应用侧默认 `false`（本地/测试无租户也能启动），
  CCE 上不设或设为 false 会让 OIDC 登录入口不可用。
- ⚠️ `OAUTH_FRONTEND_CALLBACK_URI` / `OIDC_LOGOUT_REDIRECT_URI` **生产必填**：
  它们是"登录成功后回前端"和"登出后回前端"的地址，默认值是 `http://localhost:5173/**`（前端 dev server），
  不覆盖会让用户在生产跳到 `localhost:5173`（浏览器报"无法连接"）。
  前者是带一次性 code 的落地页（前端路由），后者通常是登录页。
- `HUAWEI_USERINFO_URI` 是刷新令牌时复核外部账号的 userinfo 端点；
  留空则该复核 fail-closed —— 华为账号用户刷新会被拒（需重新走一次 OneAccess 登录，SSO 下通常静默）。
- 轮换 Secret 后需重启 Pod 才会读取新值（env 注入是启动时快照）。

## 2. Deployment 引用（env valueFrom.secretKeyRef）

在 `hisilicon-knowledge-iam-service` 的 Deployment 中为容器追加 env（不使用 envFrom，
避免整包注入、按需显式引用）：

```yaml
spec:
  template:
    spec:
      containers:
        - name: hisilicon-knowledge-iam-service
          env:
            - name: OIDC_TENANT_ENABLED
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OIDC_TENANT_ENABLED
            - name: OIDC_AUTH_SERVER_URL
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OIDC_AUTH_SERVER_URL
            - name: OIDC_CLIENT_ID
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OIDC_CLIENT_ID
            - name: OIDC_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OIDC_CLIENT_SECRET
            - name: OAUTH_FRONTEND_CALLBACK_URI
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OAUTH_FRONTEND_CALLBACK_URI
            - name: OIDC_LOGOUT_REDIRECT_URI
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: OIDC_LOGOUT_REDIRECT_URI
            # 可选：不配则外部账号刷新复核 fail-closed
            - name: HUAWEI_USERINFO_URI
              valueFrom:
                secretKeyRef:
                  name: oneaccess-oidc
                  key: HUAWEI_USERINFO_URI
```

应用侧对应的 `application.yml` 配置已就位：`quarkus.oidc.*`（含 `authentication.redirect-path`）、
放行路径、`oidc.provider.huawei.userinfo-uri`、`oidc.logout-redirect-uri`、
`platform.oauth.frontend-callback-uri`。`${OIDC_*:}` 空默认值不阻断启动，
但 `OIDC_TENANT_ENABLED` 为 false 时 OIDC 登录不生效；两个前端跳转地址不覆盖会指向 localhost。

## 3. OneAccess 门户侧人工操作清单

以下步骤在 OneAccess 管理门户完成（对应官方文档《通过 OIDC 协议单点登录至应用》）：

1. **添加自建应用**：「资源 > 应用」→ 自建应用「添加自建应用」，设置 Logo 与名称，保存。
2. **认证集成选 OIDC**：进入应用详情 → 通用信息 → 「认证集成」开启设置，协议选 **OIDC**，保存。
   ⚠️ 认证集成协议一旦设置**不可修改**。
3. **参数配置（认证参数）**：
   - **Redirect URL**（必填）：授权成功回调地址，须在可信域名范围内。填 IAM 服务的回调地址
     （应用侧 `quarkus.oidc.authentication.redirect-path` 配的是**含 root-path 的完整路径**
     `/hisilicon-knowledge-iam/oidc/callback/huawei`），经 APIG 对外的完整 URL，例如
     `https://<对外域名>/hisilicon-knowledge-iam/oidc/callback/huawei`。
     ⚠️ quarkus-oidc 把 `redirect-path` **原样**作为 `redirect_uri`（不会自动拼 root-path），
     配成裸路径会让 IdP 回跳到路由不到的地址（本地 Keycloak 联调已踩，表现为回调 404）。
     ⚠️ **只需登记这一个**：多登记的每个地址都是额外的可用回调入口（暴露面），
     且两边不一致时 IdP 直接报"无效的 redirect_uri"。
   - **Logout URL**（可选，**生产建议填**）：用户在 **OneAccess 门户**注销后的返回地址。
     填**前端登录页**，如 `https://<对外域名>/login`（与应用侧 `OIDC_LOGOUT_REDIRECT_URI` 同值）。
     不填则用户在门户注销后停在门户默认页；填了则"应用内登出"与"门户内注销"两条路径都回登录页。
   - 授权码模式保持默认开启；TOKEN 签名算法默认 RS256（与应用侧匹配）。
   - 如需刷新令牌：Refresh Token 有效期须大于 Access Token 有效期（默认 0 即不支持）。
     ⚠️ 本平台**不使用** IdP 的 refresh token（自己发 refresh + Redis 吊销），此项保持默认即可。
4. **映射配置**（认证集成 → 映射配置 → 添加映射）：
   - **用户唯一标识 → `sub`**（最关键：应用按它绑定平台账号，必须映射且不可用邮箱/手机等可变字段替代）；
   - 昵称/姓名（OneAccess 侧的 displayName / userName 属性）；
   - 邮箱（mail 属性）。
   映射决定认证成功后返回给应用的用户属性。
   ⚠️ **区分两个接口的字段命名**（联调时容易混淆）：
   - **ID Token**（登录时 quarkus-oidc 验签、我们读的）：标准 OIDC 命名，`sub` 即唯一标识；
   - **userinfo**（刷新复核时我们调的 `/api/v1/oauth2/userinfo`）：**非标准命名**
     `id` / `userName` / `name` / `email` / `mobile`，其中唯一标识叫 **`id`** 而不是 `sub`。
     应用侧已兼容两套命名，但排查日志时要按对应接口看字段。
5. **授权用户**：左侧「授权管理 > 应用账号」→「添加账号」，授权可访问本应用的账号/策略。
   未授权用户即使通过门户认证也无法进入应用。
6. **获取应用凭据**：应用详情页获取 **ClientId** 与 **ClientSecret**（ClientSecret 需点「启用」生成；
   OneAccess 不存储 ClientSecret，务必当场妥善保管；重置即时生效且旧值全部失效）。
7. **查看认证地址**：「设置 > 服务配置」→ OIDC → 查看认证地址（即 `OIDC_AUTH_SERVER_URL`），
   右上角「OIDC 设置」可查看认证参数。
8. **验证**：用已授权账号登录用户门户，单击目标应用即可单点进入企业应用。

## 4. 对应迁移脚本

数据库侧准备见 [migration_023_oneaccess_oidc.sql](../sql/migration_023_oneaccess_oidc.sql)（内置角色 `community` + `external_identities` 索引）
与 [migration_024_roles_builtin.sql](../sql/migration_024_roles_builtin.sql)（内置角色 `sys_admin` 并授予 admin）。
