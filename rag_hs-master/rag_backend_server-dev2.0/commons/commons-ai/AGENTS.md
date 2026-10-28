# 模型服务访问适配层

从 `llm` 抽出的模型侧访问适配层，用于解开 `llm` ↔ `knowledge` 的编译期环：两个模块原先各自
约定「实现放在对方模块」，抽出本模块后，双方都只依赖 commons 层。

## 职责

- **REST Client 接口**（`api`）：
  - `ModelServiceApi` — HaiSI RAG Server `/kw`（`/chat/chat`、`/chat/file_chat`、`/chat/multi_knowledge_base_chat`、`/knowledge_base/upload_temp_docs`）
  - `ModelDocApi` — `/knowledge_base/get_doc_by_filename`
  - `KgServiceApi` — dual-mcp 知识图谱（`/projects`、`/graph_search`、`/graph_node`、`/graph_kg`、`/graph_suggest`、`/graph_fragment`、`/doc_code`）
- **端口实现**（`client`）：`DocCodeMappingClientImpl`、`DocumentChunkClientImpl`、`KnowledgeGraphClientImpl`
  （实现 `commons-dict` 中定义的 `DocCodeMappingClient` / `DocumentChunkClient` / `KnowledgeGraphClient` 端口），
  以及 `ModelServiceRestClient`。
- **wire DTO**（`dto`）：与模型服务协议一一对应的 snake_case 请求/响应对象。
- **异常**（`exception`）：`ModelServiceException`（模型调用失败）、`ModelSideRejectionException`（模型侧拒答）。
- **排查日志**：`RestClientRequestLogger` 记录实际出站 URI，`LogTruncate`（包私有）截断请求/响应体。

## 边界与约定

- **不依赖** `llm` / `knowledge` / `system`，只依赖 `commons-*`（`commons-dict` 的端口与响应类型、
  `commons-errorcode`、`commons-i18n`）。
- **纯库模块**：不应用 `io.quarkus` 插件（无 `@QuarkusTest` 需求），靠
  `src/main/resources/META-INF/beans.xml` 声明为 bean archive 并被 Quarkus 索引，使
  `@ApplicationScoped` 实现与 `@RegisterRestClient` 接口在依赖方模块中可被发现。
- 模型服务的 URL 由**使用方**的配置提供（`quarkus.rest-client.model-service.url` /
  `quarkus.rest-client.kg-service.url`），本模块不持有配置。
- 后续「平台对接模型侧 mcp 服务」的协议客户端也落在此模块——**不要**放进 `hisilicon-knowledge-mcp-gateway`
  （后者是面向 agent 的入站 MCP 服务，方向相反）。

## 契约测试（真实模型，默认跳过）

`ModelContractTest` 直接调用真实 dual-mcp，覆盖文档声明的全部端点（`/projects`、`/graph_search`、
`/graph_node`、`/graph_kg`、`/graph_suggest`、`/graph_fragment`、`/doc_code`、`/doc_view`）。默认被环境变量门禁跳过，
**联调前 / 模型侧升级后手动跑一次**：

```powershell
$env:MODEL_CONTRACT_BASE_URL='http://139.9.104.64:9789'
.\gradlew.bat :commons:commons-ai:test --tests "*ModelContractTest" --console=plain
```

- 断言用的字段集合就是生产侧那几个包私有常量（`KnowledgeGraphClientImpl.*_FIELDS`、
  `DocCodeMappingClientImpl.DOC_CODE_FIELDS`），判定逻辑与运行期 WARN 共用 `ModelResponseDrift.findings`
  ——**改契约只改这两处**，测试与运行期告警自然同步，不要在测试里另写一份字段清单。
- 模型新增/改名字段 → 该测试红（附"未声明字段 xxx"），运行期同时留一条 WARN；先红后修，别等前端空白。
- 用例不绑定具体项目/符号：项目取 `/projects` 首个、KG 实体取 `/graph_suggest` 的 kg 候选、
  `doc_view` 取节点最多的 `projects-<kbId>` 项目（`/opt/...` 类项目服务端定位不到存储根，会 404）。
  palace（按库过滤 kbId，`/graph_kg` 按库模式 + `/graph_suggest` palace，见《API-GRAPH3.md》§4.5/§5）
  取首个 `projects-<kbId>` 项目剥离前缀。
  环境确实没数据时用 `assumeTrue` 跳过并给原因，不产生假绿。
