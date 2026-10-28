# 文件级权限与灰度发布（状态机+审计）——可行性评估与实施方案

> 版本：评审稿 v1（2026-10-19）
> 范围：RAG（qa/ingestion）+ Milvus 多版本共存 + 平台角色对接；关联已有基座见 `/data/FILE-VERSIONING-DESIGN.md`

## Context

需求：同一知识库下，新增/修改的文件走 **草稿→初审→灰度→终审→发布** 状态机（含驳回带原因/归档/废弃/删除）+ 全程审计。灰度期间仅内测角色可在问答中命中新版本（引用须标注【测试文档】），普通用户完全无感；终审通过后全员可见。约束：**三路检索+RRF+rerank+LLM 核心链路不改造**，只在 reranker 前按（文件版本 × 用户角色）过滤。

已确认决策：① Milvus 多版本共存+检索后过滤；② 角色对接平台用户体系（hisilicon-knowledge-platform-service）；③ 一期仅文档灰度（代码文件走状态机但问答代码路灰度不可见）。

## 可行性结论：✅ 可行

| 依据 | 事实（已验证） |
|---|---|
| 版本基座已存在 | 文件级多版本管理已在 DEV 上线（`/data/FILE-VERSIONING-DESIGN.md`，gitcode dev 已合入）：`file_version_history` 表 + 版本区 blob（内容寻址）+ 版本 API；qa/ingestion 已挂载 `file_version.py`（CM rag-code-hotfix keys 齐全） |
| 过滤插入点干净 | 运行时 `multi_knowledge_base_chat.py`（81KB 版）：三路候选统一为 Document 且带 `source`/`doc_type`/`file_version`（代码路 ：577-597 已透出）；RRF 融合 :1542 → 去重 :1548 → reranker :1570——**过滤插在各路候选收集后、RRF 融合前**，检索算法零改动 |
| Milvus 扩展点现成 | chunk schema 含 `metadata JSON` 字段（pk/text/vector/source/metadata），加 `{file_version, release_status}` **无需改 collection schema** |
| user_id 已入链路 | 问答请求 Body 参数（:178），过滤函数可直接取 |
| 状态机/审计纯新增 | 新表+新路由，照 file_versions 的守卫式注册模式 |

**对原方案的一处修正**：不建议"版本号创建物理版本目录"。现有版本区 blob（内容寻址、同内容去重、零拷贝回滚）优于物理目录；上传带的版本号改作 **release 批次标签**（如灰度批次 `v2.0`），"同 KB 同路径同版本唯一"由 `release_item` 唯一约束保证。与现有版本体系完全兼容。

## 架构设计

### 状态机（9 态 × 动作）

```
draft ─提交初审→ initial_review ─通过→ gray ─提交终审→ final_review ─通过→ published
  ▲                │切片/图谱失败            │驳回                │驳回
  └────────────────┴────────────────────────┴────────────────────┘（均回 draft）
published ─归档→ archived；─废弃→ deprecated；draft ─删除→ deleted
初审中/灰度/终审中：文档不可删除、元数据不可修改（API 层拒绝）
```

问答可见性：`published`=全员；`gray/final_review`=仅内测角色（标注测试）；其余状态=问答不可见（仅管理后台）。

### 数据模型（RAG PG，3 张新表，自愈建表照 `_ensure_table` 模式）

- `doc_release`：id, kb_name, **version_label**(批次版本号), status, created_by, created_at
- `doc_release_item`：id, release_id, file_name(逻辑名), file_path, file_version_seq(关联 history), item_status；**唯一约束 (kb, file_name, version_label)** ← 规则一.1
- `doc_release_audit`：id, release_id, from_status, to_status, action, actor, **reason**(驳回必填), created_at

### 角色解析（对接平台，新增 `role_resolver.py`）

- 平台 HTTP API `GET /api/users/{user_id}/roles → [admin|reviewer_initial|reviewer_final|beta_tester|user]`（**契约需与平台团队确认，是本方案唯一外部依赖**）
- 进程内缓存 60s；**失败 fail-closed**（按普通用户处理，灰度内容不可见——安全默认）
- 兜底：env `GRAY_BETA_USER_IDS` 白名单（平台接口未就绪时先用）；备选直读平台 PG（qa pod 有 psycopg2 先例）

### Milvus 多版本共存（改动最小的关键机制）

- `milvus_kb_service.py`：`do_add_doc` 写入 metadata JSON `{file_version, release_status}`；新增 skip_delete 变体（灰度入库不删旧 chunk）；新增按 `source+release_status` / `source+file_version` 删除
- 初审节点（生成切片=触发）：灰度文件入库 → 旧 published chunk **不删**，新 chunk `release_status=gray`
- 终审通过：删该文件 published 旧 chunk → 灰度 chunk 翻转为 `published` → mempalace 写入（retag/active 机制现成）+ KG 抽取（推迟到这里 → 驳回零污染，规避"KG 无版本管理"已知限制）
- 驳回：删灰度 chunk——**published chunk 从未被动过，回滚零成本**（切片/问答自动"回滚上一版"）
- 图谱：一期文档不涉图谱；代码文件终审通过才 `trigger_reindex_async`

### QA 过滤（单点插入，`multi_knowledge_base_chat.py`）

```
各路候选收集完 →【新增 _filter_by_release(docs, user_id)】→ RRF 融合(:1542) → 去重 → reranker
```
- 角色解析 → 可见性规则；**同文件多版本命中只保留该用户可见的最高版本**（内测=灰度版，普通=发布版）——必须在 `_dedupe_docs` **之前**完成版本选择（否则新版未变段落与旧版相同文本被跨路去重误删）
- 灰度块 `metadata.is_gray=true` → 返回 docs 标注【测试文档】+ prompt 注入"引用测试文档须标注【测试】"指令（:405 context 组装处）
- 异常兜底：过滤异常→只放行 `release_status=published`（服务可用性优先）

### 状态机 API（RAG 侧新增，照 api.py 守卫式注册）

`POST /kw/release/create|submit_initial|approve_initial|reject|submit_final|approve_final|archive|deprecate|delete` + `GET /kw/release/list|detail|pending`
- submit_initial：触发切分（Milvus 灰度入库），成功→initial_review+审计+待办（GET pending?role=），失败→draft+审计（=规则二.3）
- 通知：一期待办列表；站内/IM 推送二期
- 元数据修改/删除：draft 才允许（状态机 API 层校验=规则一.2/二.4）

## 分期

- **P0（本次）**：3 表+状态机 API+审计、Milvus 多版本共存、QA reranker 前过滤+测试标注、role_resolver（平台 API+env 兜底）、E2E
- **P1**：mempalace 灰度查询（patch FV3 放开非 active+角色参数）、代码文件灰度（dual-mcp 影子 project）、通知集成、平台前端对接
- **P2**：按版本检索历史、发布批次 diff 预览

## 改动文件（gitcode haisi_rag_models_server，分支 feat/gray-release；部署=CM rag-code-hotfix 加 key+qa/ingestion 挂载+rollout）

| 文件 | 改动 |
|---|---|
| `server/knowledge_base/release.py` | 新增：状态机+审计+Milvus 共存编排 |
| `server/db/models/release_models.py` | 新增 3 表 |
| `server/knowledge_base/milvus_kb_service.py` | metadata 扩展+skip_delete+按版本删除 |
| `server/chat/multi_knowledge_base_chat.py` | RRF 前过滤+灰度标注+prompt 指令 |
| `server/knowledge_base/kb_doc_api.py` | 灰度入库挂钩+search_docs/list_files status 参数（管理后台可见性） |
| `server/knowledge_base/role_resolver.py` | 新增 |
| `server/api.py` | 守卫式注册 release 路由 |

## 风险与开放问题

1. **平台角色 API 契约未定**（唯一外部依赖）——先 env 白名单兜底，契约确认后切换
2. RRF topk 池可能被同文件双版本占位——过滤在 RRF 前完成版本选择后已消解；rerank top_n 名额不受影响
3. `upload_docs_use_path` 流版本行 blob 懒创建（现有已知限制，rollbackable=false 窗口）——release 流要求走 blob 齐全的入库路径
4. delete_knowledge_base 需级联清 release/audit（照 remove_kb_history 模式）
5. RAG 无认证，actor 由请求参数传+平台侧鉴权，内网信任模型（现状沿用）
6. 普通用户回归风险：无灰度文件时过滤为直通（metadata 无 release_status 视为 published 存量兼容）

## 验证（DEV E2E）

1. 全流转：create→submit_initial(切片成功)→approve_initial→reject(普通/内测都只见旧版)→再 approve→submit_final→approve_final
2. 权限隔离：内测 user_id 问灰度内容命中且 docs 标【测试】；普通 user_id 同问只回旧版；LLM 回答带测试标注
3. 驳回零残留：reject 后 Milvus 按 source 查无 gray chunk
4. 审计完整：detail 含全部 transitions+驳回 reason
5. 失败注入：切片失败→回 draft；角色接口挂→fail-closed
6. 回归：无灰度文件的普通问答 docs 输出与改前一致
