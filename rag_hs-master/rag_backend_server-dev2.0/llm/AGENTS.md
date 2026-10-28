# llm 模块（检索问答域 + 敏感词域）

会话、消息、SSE 流式问答、命中统计。部署单元为 `hisilicon-knowledge-core-service`（与 `knowledge` 一起）。

## 模型访问已下沉到 commons-ai

**本模块不再自己调模型服务**：原来在这里的 rest-client 适配层（25 个类：`api`/`client`/`dto`/`exception`）
已下沉为 `commons/commons-ai`，`knowledge` 也依赖它——这是"解环"那一步做的事，
用来打断 `llm ↔ knowledge` 的双向依赖。改模型接口请改 `commons-ai`，不要在本模块重建一版。

## 本模块现在包含两个域

| 域 | 包 | 说明 |
|----|----|------|
| 检索问答 | `llm.**`（除 `sensitive`） | 会话、消息、SSE、命中统计 |
| 敏感词 | `llm.sensitive.**`（57 个类，来自原 `system`） | 敏感词/敏感标签/敏感问句、字典树匹配、Redis 缓存 |

敏感词端口与纯匹配器（`SensitiveWordChecker`、`WordTreeMatcher`、`SensitiveWordMatch` 等）留在
`commons-sensitive`；实现、PO、门面都在本模块的 `llm.sensitive` 下。

## 边界与注意

- 本模块**不得依赖 `knowledge`**：解环后编译期引用为 0，跨域只经 `commons-dict` 的端口与本模块提供的实现。
- **敏感词只有一份实现**（在本模块），前后台问答共用同一份。任何"给前台单独部署一份问答服务"的方案
  都会导致两份敏感词、失效窗口翻倍——这是拒绝"按端拆服务"的硬约束之一。
- 测试替身统一用 `@io.quarkus.test.Mock`（`@Alternative @Priority(1)`）；原来测试里并存的两个
  `MockSensitiveWordChecker` / `MockSensitiveWordCacheService` 已合并为后者一个，改敏感词测试别再新加替身。

## 配置

只读两个键：`kb.storage.local-path`、`kb.upload.chat-max-file-size`。
模型服务的 URL 不在本模块（在 `commons-ai` 的 `quarkus.rest-client.model-service.url` / `kg-service.url`）。
