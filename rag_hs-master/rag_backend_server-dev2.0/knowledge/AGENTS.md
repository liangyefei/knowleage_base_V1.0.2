# knowledge 模块（知识资产域 + 字典域）

知识库、文档、版本、分类、审核、摄取（上传/解压/解析/切片）、全局文档搜索。
部署单元为 `hisilicon-knowledge-core-service`（与 `llm` 一起）。

## 本模块现在包含两个域

| 域 | 包 | 说明 |
|----|----|------|
| 知识资产 | `knowledge.**`（除 `dict`） | 知识库/文档/版本/审核/摄取 |
| 字典 | `knowledge.dict.**`（31 个类，来自原 `system`） | 字典项/字典类型的命令、查询、门面 |

字典域的 PO、Panache 仓储与共享服务仍留在 `commons-dict`（它同时是"问答↔知识"接缝端口的宿主）。
后续若要让"域 = 服务"更纯粹，可以把这些持久化类下沉到本模块。

## 边界

- **不得依赖 `llm`**：解环后本模块对 `llm` 的编译期引用为 0，跨域协作只经 `commons-dict` 的端口。
  加代码时别把这个环重新接上（`knowledge → llm` 会立刻让 `hisilicon-knowledge-core-service` 内部的域边界失效）。
- 实现对 `commons-dict` 端口的实现类都在本模块，改端口签名要同步这四处：
  `KnowledgeBaseChatResolverImpl`、`KnowledgeBaseNameResolverImpl`、`ChatImageAssetUrlRewriterImpl`、
  `KnowledgeDictReferenceCheckerImpl`（另有 `CommonsAi` 侧的图/切片客户端由 `commons-ai` 提供）。
- 重依赖集中在本模块：POI、Tika、PDFBox、junrar、7-Zip、commons-compress。
  这也是"不按端拆服务"的主要原因——前台问答若单独成服务会被迫继承这套依赖。

## 配置

本模块读取 35 个配置键（`kb.archive.external-tool`、`kb.asset.*`、`kb.storage.*`、
`kb.import.*`、`kb.search.*`、`kb.kg.public-url`、`kb.outbox.*`、`kb.messaging.*` 等）。
服务级配置在 `hisilicon-knowledge-core-service/src/main/resources/application.yml`；
模块自己的测试没有 yml，靠 `src/test/resources/application.properties` 补齐。

**新增配置键必须同时加到 `hisilicon-knowledge-core-service` 的 yml**，否则只在本模块的测试里能跑、服务启动即失败。
