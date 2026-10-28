# MCP 令牌 · 前端引导页对接文档

> 面向：前端联调同学
> 适用：dev / test / prod（浏览器 URL 仍是 `/hisilicon-knowledge/**`）
> **一句话结论：这个页面只做四件事——让用户拿到 MCP 令牌、复制一段配置到 Cursor/Claude、看自己今天用了多少、能在旧电脑丢了的时候一键吊销。接口共 6 个（5 个自助 + 1 组管理员），全部走平台现有登录态，不需要任何新登录方式。**

---

## 1. 先改路由，否则一调就 404

这些接口在 **iam** 里（`@Path("/system/mcp-tokens")`），但浏览器可见路径沿用 `/hisilicon-knowledge/**`。
现有 nginx 只把 `user/login|refresh|logout` 和 `publicKey` 转发到 iam，**`/system/mcp-tokens/**` 目前会落到 core → 404**。
在 [前端对接调整说明](海思知识库-前端对接调整说明.md) §3 那段 `location` 里追加一条即可（正则 location 优先于前缀 location）：

```nginx
location ~ ^/hisilicon-knowledge/(user/(login|refresh|logout)|publicKey|system/mcp-tokens/.*)$ {
    rewrite ^/hisilicon-knowledge/(.*)$ /hisilicon-knowledge-iam/$1 break;
    proxy_pass http://hisilicon-knowledge-iam-service:8080;
    proxy_set_header Authorization $http_authorization;
}
```

- 前端代码里**只写 `/hisilicon-knowledge/system/mcp-tokens/...`**，不要手写 `-iam` 前缀。
- 上线第一天请务必用 §9 的自检脚本验一遍，返回 404 就是这条没生效。

---

## 2. 页面结构建议（4 个区块，一个页面够用）

```
┌─ MCP 令牌 ────────────────────────────────────────────────┐
│ ① 我的令牌卡片                                            │
│    [已签发] 笔记本 · 有效期至 2026-11-22 21:52 · 最近使用 21:52│
│    [复制令牌] [复制 mcpServers 配置] [重新签发] [吊销]      │
│    上一次签发已使旧令牌失效（这条提示要显眼）                │
├───────────────────────────────────────────────────────────┤
│ ② 三步引导（静态文案 + 可复制的 JSON）                      │
│    1) 复制令牌  2) 粘到 Cursor/Claude 的 mcpServers  3) 验证 │
├───────────────────────────────────────────────────────────┤
│ ③ 今日用量：调用 12 / 2000 次 · 返回 4.2 万 / 2000 万字符    │
│    （进度条；limitCalls=null 时展示"默认 2000 次/天"）      │
├───────────────────────────────────────────────────────────┤
│ ④ 历史令牌（可折叠，默认收起）：只读列表 + 吊销              │
└───────────────────────────────────────────────────────────┘
```

**必须做对的两件事：**

1. **令牌明文只在"签发"响应里出现一次**，列表接口不返回 token。所以签发成功后不要只弹个 toast——要把令牌放进页面上的只读输入框（带复制按钮），并提示"离开页面就再也看不到了，丢了只能重新签发"。
2. **重新签发 = 旧令牌立即失效**（响应里有 `replacedJti`）。按钮必须二次确认，文案写清楚"旧令牌会立刻失效，已经配置好的电脑要用新令牌重配"。

---

## 3. 接口清单

前缀统一 `/hisilicon-knowledge`；除标注外都需要 `Authorization: Bearer <登录 accessToken>`。
响应统一是 `{ code, data, message }`，**`code === "0"` 才是成功**——业务错误同样是 HTTP 200，别只看状态码。

| # | 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/system/mcp-tokens` | 我的令牌列表 | 登录用户 |
| 2 | POST | `/system/mcp-tokens` | 签发（**即轮换**） | 登录用户 |
| 3 | GET | `/system/mcp-tokens/usage` | 我的今日用量 | 登录用户 |
| 4 | DELETE | `/system/mcp-tokens/{jti}` | 吊销我的某个令牌 | 登录用户 |
| 5 | GET | `/system/mcp-tokens/{username}/tokens` | 查某人的令牌 | sys_admin |
| 6 | GET | `/system/mcp-tokens/{username}/usage` | 查某人用量 | sys_admin |
| 7 | GET | `/system/mcp-tokens/{username}/quota` | 查某人限额 | sys_admin |
| 8 | PUT | `/system/mcp-tokens/{username}/quota` | 改某人限额 | sys_admin |

---

## 4. 逐个接口（响应为真机抓取，令牌已截断）

### 4.1 我的令牌列表 `GET /system/mcp-tokens`

```json
{"code":"0","data":[
  {"jti":"28b51cd3-…","label":"笔记本","createdAt":"2026-10-23 21:52:17",
   "expiresAt":"2026-11-22 21:52:17","revokedAt":null,"usable":true,"lastUsedAt":null},
  {"jti":"4cc1da30-…","label":"我的电脑","createdAt":"2026-10-23 21:52:17",
   "expiresAt":"2026-11-22 21:52:17","revokedAt":"2026-10-23 21:52:17","usable":false,
   "lastUsedAt":null}
],"message":"操作成功"}
```

- **没有令牌时返回空数组**，不是 404。
- 列表包含已吊销/过期的历史记录，按 `createdAt` 倒序；页面上"我的当前令牌"取 `usable === true` 的那条（`usable = revokedAt 为空 且 未过期`）。
- 时间格式固定 `yyyy-MM-dd HH:mm:ss`，**没有时区后缀**，按北京时间显示即可，不要 `new Date(str)` 直接解析（Safari 会得到 Invalid Date，`str.replace(' ','T')` 再解析）。

### 4.2 签发 / 轮换 `POST /system/mcp-tokens`

请求体（`label` 可选，≤128 字符，空串会被存成 `default`；建议前端默认填"我的电脑"）：

```json
{"label":"笔记本"}
```

响应（**这是唯一一次能看到 `token` 的机会**）：

```json
{"code":"0","data":{
  "jti":"28b51cd3-1a85-4377-9385-1638927d797a",
  "label":"笔记本",
  "token":"eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.…（约 700 字符的单行 JWT）",
  "expiresAt":"2026-11-22 21:52:17",
  "gatewayUrl":"http://localhost:8080/hisilicon-knowledge-mcp/mcp",
  "mcpServersJson":"{\"mcpServers\":{\"hisilicon-knowledge\":{\"type\":\"streamable-http\",\"url\":\"http://…/mcp\",\"headers\":{\"Authorization\":\"Bearer eyJ…\"}}}}",
  "replacedJti":"4cc1da30-6b33-4500-bab7-72b859cfe9ac"
},"message":"操作成功"}
```

- `mcpServersJson` 是一个**被转义成字符串的 JSON**（`JSON.parse` 一次就得到对象），内容等价于：

```json
{
  "mcpServers": {
    "hisilicon-knowledge": {
      "type": "streamable-http",
      "url": "<gatewayUrl>",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

  前端不要再自己拼这个结构——`JSON.parse(res.data.mcpServersJson)` 后 `JSON.stringify(obj, null, 2)` 展示即可，保证和后端配置保持一致。
- `replacedJti` 非空 = 刚刚吊销了这个旧令牌，请在界面上明确提示。
- `gatewayUrl` 来自服务端配置（生产应配成对外域名）。**如果运维漏配，这里会是 `http://localhost:8080/...`**，页面照实展示即可；发现是 localhost 请反馈后端，不要前端硬编码替换。

### 4.3 我的今日用量 `GET /system/mcp-tokens/usage?date=YYYY-MM-DD`

```json
{"code":"0","data":{"username":"mcp_tester","date":"2026-10-23","calls":12,"chars":42100,
  "estimatedTokens":10525,"limitCalls":null,"limitChars":null,"overQuota":false},"message":"操作成功"}
```

- `date` 可省略（默认今天）；传非法日期返回 `A0421 参数格式不匹配`。
- **`limitCalls / limitChars` 为 `null` 表示"用系统默认值"（2000 次 / 2000 万字符/天）**，不是"不限"。所以展示要写成："12 / 2000（默认额度）"，不要写"12 / 不限"。
- `estimatedTokens = chars / 4`，仅供体感参考，真实 token 数不等价，文案别写"精确 token 消耗"。
- `overQuota=true` 时把进度条标红并提示"今日额度已用完，明天 0 点（北京时间）重置"。

### 4.4 吊销 `DELETE /system/mcp-tokens/{jti}`

```json
{"code":"0","data":null,"message":"操作成功"}
```

- 幂等性一般：jti 不存在时返回 `A0430 请求的数据不存在`（HTTP 200），按"已经被吊销了"处理即可，不用报错打扰用户。
- 吊销后该令牌**立即**失效（网关读 Redis 黑名单，不是等 30 天过期）。
- 只能吊销自己的；管理端吊销他人见 §8。

---

## 5. 字段语义速查

| 字段 | 语义 | 前端怎么用 |
| --- | --- | --- |
| `jti` | 令牌唯一 ID（不是令牌本身） | 列表 key、吊销入参；**不要当令牌用** |
| `token` | 真正的 JWT | 只在签发响应里；一次性展示 |
| `usable` | 吊销为空且未过期 | 决定显示"可用/已失效" |
| `revokedAt` | 吊销时间，未吊销为 `null` | 有心跳线时展示"已于 xx 吊销" |
| `lastUsedAt` | 最近一次真实调用网关的时间 | 展示"最近使用"；`null` = 从未用过（很有用的排障信息） |
| `expiresAt` | 过期时间（默认签发 +30 天） | 剩余天数倒计时；<7 天提示"即将过期，请重新签发" |
| `replacedJti` | 本次轮换被吊销的旧 jti | 提示"旧令牌已失效" |
| `limitCalls/limitChars` | `null` = 系统默认；`0` = 完全禁止 | 见 §4.3；`0` 要在管理端二次确认 |
| `gatewayUrl` | 网关 MCP 端点（服务端配置） | 展示 + 拼进配置示例 |

**轮换策略（务必在引导页用一句话讲明白）**：一个账号同时只有 1 个可用令牌。新签发即吊销旧的——这是防"令牌在旧机器上继续跑"的设计，不是 bug。

---

## 6. 错误码

| code | 含义 | 前端处理 |
| --- | --- | --- |
| `0` | 成功 | — |
| `A0301` | 未登录 / 登录态失效 | 跳登录页 |
| `A0400` | 参数不合法（如 `label` 超 128、限额为负） | 表单就地提示 |
| `A0404` | 非系统管理员访问管理端 | 隐藏管理入口 / 提示无权限 |
| `A0421` | 参数格式不对（如 `date=2026-13-45`） | 用日期选择器，别让用户手输 |
| `A0430` | 数据不存在（吊销了不存在的 jti、查了不存在的人） | 静默刷新列表 |
| `A0405` | 方法不允许 | 前端写错方法 |

404 几乎只有一种原因：§1 的 nginx rewrite 没生效。

---

## 7. 引导文案（可直接用）

给用户的"三步"卡：

1. **复制令牌**（按钮）
2. **粘贴到客户端**：Cursor `~/.cursor/mcp.json`、Claude Desktop `claude_desktop_config.json`，内容就是上面 `mcpServersJson`；VS Code / 其他支持 streamable-http 的客户端同理。
3. **验证**：在客户端里问一句"列一下你有哪些工具"，能看到工具列表就通了。

**注意事项（这四条建议原样展示，能省掉大量答疑）：**

- 令牌等同密码，不要发群里、不要提交到 git、不要截图外发。
- 一个账号一个令牌，换设备就重新签发，旧设备立即失效。
- 令牌 30 天过期，过期后需重新签发。
- 每日额度：默认 2000 次调用 / 2000 万字符返回（按天重置，北京时间 0 点）。超额后调用会返回"额度已用完"的提示，不是接口挂了。

---

## 8. 管理端（sys_admin 才显示，普通用户不要渲染入口）

- `GET /system/mcp-tokens/{username}/tokens` — 看某人令牌（同样只有 `jti/label/时间/usable`，**看不到明文**）。
- `GET /system/mcp-tokens/{username}/usage?date=…` — 看某人某天用量；**该响应同时带 `limitCalls/limitChars`**，可以当"用量+限额"合一接口用。
- `GET /system/mcp-tokens/{username}/quota` → `{"username":"mcp_tester","dailyCalls":null,"dailyChars":null}`
- `PUT /system/mcp-tokens/{username}/quota`，body `{"dailyCalls":5000,"dailyChars":20000000}`；传 `null` = 恢复系统默认；**传 0 = 该账号当天完全不能用**（合法值，UI 要拦一下确认）。

查不存在的人：`tokens` 返回空数组（`code=0`），不会 404——不要据此判断"用户存在"。

---

## 9. 30 秒自检

```bash
入口=http://<前端访问入口>
TOKEN=<登录后的 accessToken>

# ① rewrite 是否生效：应返回 {"code":"A0301",...} 而不是 404 / HTML
curl -s $入口/hisilicon-knowledge/system/mcp-tokens

# ② 登录态是否透传：应返回 {"code":"0","data":[...]}
curl -s -H "Authorization: Bearer $TOKEN" $入口/hisilicon-knowledge/system/mcp-tokens

# ③ 用量
curl -s -H "Authorization: Bearer $TOKEN" $入口/hisilicon-knowledge/system/mcp-tokens/usage
```

再补一条**必须由人手动验**的：拿签发出来的 `mcpServersJson` 配到 Cursor 里，问一句"你有哪些工具"，能列出工具即链路通（网关当前聚合 4 个上游、16 个工具）。

---

## 10. 与现有登录体系的关系（避免误用）

- 页面本身用的是**现有登录 accessToken**（`user/login` 那个），和 MCP 令牌是两码事。
- 登录接口的 `username`/`password` 是 **RSA 加密**的（`GET /publicKey` 取公钥，PKCS#1 v1.5），这部分前端已经实现，本页面不涉及。
- **绝对不要把登录 accessToken 当 MCP 令牌用**：网关会返回 `MCP_TOKEN_TYPE_MISMATCH`（登录令牌 `type=access`，MCP 令牌 `type=mcp`）。这条已在真机验证过。
- 网关只认平台公钥签发的令牌：外部的 Keycloak/OneAccess 令牌直接打网关会得到 `MCP_TOKEN_INVALID`（签名校验未通过）。所以"公司统一账号登录"和"MCP 令牌"是两条链，不要把两者混淆写进引导页。

---

## 11. 部署侧需要配合的两件事（提醒运维，不属前端任务）

1. **nginx/APIG 增加 §1 的 rewrite**，否则本页面全部 404。
2. **iam 配置 `PLATFORM_MCP_TOKEN_GATEWAY_URL`**（= 对外 MCP 地址，如 `https://kb.example.com/hisilicon-knowledge-mcp/mcp`），否则签发响应里的 `gatewayUrl` 和 `mcpServersJson` 会带 `localhost:8080`，用户复制走不通。

另：网关必须注入 `RSA_PUBLIC_KEY`（iam 的 `publicKey.pem` 单行 base64），缺失时所有 MCP 调用返回 `503 MCP_TOKEN_BACKEND_UNAVAILABLE`——这条由后端/运维保证，前端不用处理。

---

## 12. 待产品确认（我先按上面的方案写了，改起来都是文案级改动）

1. 令牌明文是否需要"再展示一次"（当前后端不存明文，做不到；要做得加字段）。
2. 引导页是否要顺带给"每日额度用完"的自助申请入口（当前只能管理员改）。
3. 普通用户是否允许看到自己的历史令牌（当前允许），还是只显示当前那一个。

---

## 附：验证情况

本文档所有请求/响应均在本机真实环境抓取（iam + 网关 + podman postgres/redis + 4 个桩上游），18 条 e2e 断言全绿：
无令牌 401 / 乱码令牌 401 / 登录令牌冒充 401 / 合法令牌 initialize+tools/list+tools/call 通 / 非管理员 403 / 轮换后旧令牌 401 / 次数超限第 3 次被拦且计数不涨 / 字符超限首次放行二次被拦 / `lastUsedAt` 回写 / 额度复位。
验证脚本：[`scripts/mcp-e2e/test-mcp-auth-quota.ps1`](../scripts/mcp-e2e/test-mcp-auth-quota.ps1)。
（其中 503 后端不可用分支为代码级结论，未在本机单独复现。）