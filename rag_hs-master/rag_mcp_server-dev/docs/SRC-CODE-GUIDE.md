# src/ 生产代码详细说明

> `src/` 是代码审核目录，存放进镜像的全部自研生产代码（5 个服务文件共 3675 行 + `bin/` 4 个容器 CLI helper 共 316 行）。
> 构建/编译/部署脚本在仓库根 `scripts/`（local_deploy / package / serve），工具类脚本在 `tools/`（查询/检索工具），均不进镜像、不在服务调用链上。
> 镜像内布局：服务文件由 Dockerfile 分别 COPY 到 `/opt/...` 固定路径，helper 装到 `/usr/local/bin/`；DEV/TEST 生产上实际生效的是 ConfigMap `dual-mcp-hotfix` 挂载的同名热修版（本目录是母本）。

| 文件 | 行数 | 角色 | 容器内路径 |
|---|---|---|---|
| `nl_query_server.py` | 2898 | REST 主服务（FastAPI，9788） | `/opt/codebase-memory-mcp/scripts/nl_query_server.py` |
| `mcp_stdio_bridge.py` | 317 | MCP stdio→HTTP 桥（9800/9801） | `/opt/mcp_stdio_bridge.py` |
| `entrypoint.sh` | 189 | 容器入口（tini 下 PID 2） | `/entrypoint.sh` |
| `cbm_sync.py` | 101 | 本地盘→EFS 数据回传 | `/opt/cbm_sync.py` |
| `glossary_extract.py` | 170 | CN→EN 查询词表抽取（LLM） | `/opt/codebase-memory-mcp/scripts/glossary_extract.py` |
| `bin/`（4 个） | 316 | 容器 CLI helper | `/usr/local/bin/{add,list,query,remove}-project` |

调用关系总览：

```
entrypoint.sh ──启动──> nl_query_server.py（前台主进程）
     │                      │ subprocess 调 CBM 二进制（建索引/列项目）
     │                      │ sqlite 直连 CBM .db（查询/图谱/doc_code）
     │                      │ HTTP 代理 mempalace（/graph_kg）
     ├──守护启动──> mcp_stdio_bridge.py ×2（9800 桥 CBM、9801 桥 codegraph）
     ├──后台启动──> cbm_sync.py（本地盘方案时）
     └──可选后台──> glossary_extract.py（配了 DASHSCOPE_API_KEY 时）
```

---

## 1. nl_query_server.py — REST 主服务

FastAPI + uvicorn 单文件服务，容器 Phase 3 由 entrypoint `exec` 起为前台主进程。对外提供自然语言检索、知识图谱浏览、文档↔代码映射、项目生命周期管理四类能力。**不经过 CBM 的 MCP 协议**——查询直接读 CBM 的 SQLite 库文件（快路径），管理操作 subprocess 调 CBM CLI。

### 1.1 配置（环境变量）

| 变量 | 默认 | 用途 |
|---|---|---|
| `CACHE_DIR` | `/var/lib/codebase-memory-mcp` | CBM 索引根目录（`.db`/file_versions 都在这） |
| `CBM_BIN` | `/opt/codebase-memory-mcp/build/c/codebase-memory-mcp` | CBM 二进制路径 |
| `CODEGRAPH_BINARY` | `codegraph` | codegraph CLI |
| `KB_NAMES_PATH` | `/projects/.kb-names.json` | kbId→知识库显示名映射（mtime 自动重载，/projects 与图谱页面用） |
| `KB_FILES_ROOT` | `/projects` | `/doc_view` 文档定位根 |
| `CBM_PROJECTS_CACHE_TTL` | `600` | /projects 列表缓存秒数 |
| `CBM_INDEX_TIMEOUT` / `CODEGRAPH_INIT_TIMEOUT` | `1800` | 索引子进程超时（秒） |
| `MEMPALACE_KG_BASE_URL` | `http://haisi-mempalace-rest:8777` | /graph_kg 文档知识图谱代理地址 |
| `NL_PORT`（命令行 `--port`） | `9788` | 监听端口 |

### 1.2 KBStore 类（L61-636）——SQLite 直连查询层

每个项目懒加载一个实例（`get_store()`，注册表复用），`check_same_thread=False` + 配合 async 侧 `asyncio.to_thread` 使用（历史坑：async 处理器直接调阻塞 SQLite 会把事件循环占死，9789 公网失联事故的修复）。

| 方法 | 功能 |
|---|---|
| `search()` | BM25 快路径（FTS5，查询词按 camel/snake/数字边界零宽切分）+ REGEXP 慢路径兜底；REGEXP 前置 LIKE 预筛防 GIL 饿死（e6ceb01 修复） |
| `search_entities()` / `search_entities_boundary()` | 图谱实体检索（无 label 限制 / 词边界首层匹配，供 graph_suggest） |
| `entity_graph()` | 中心实体 + 任意边类型邻接子图（nodes 以 id 关联防同名混淆，带 deg_local/candidates/边锚点；邻居二跳网络化 + 悬挂点剪枝 + 并行抓取） |
| `node_detail()` | 节点详情：in/out 边类型、复杂度 props、源码段 |
| `snippet()` / `snippets_batch()` | 按 nodes.start_line..end_line 直读源文件拼函数体（2600 字符上限，附 file_version；批接口合并 SQL 防查邻居 N+1） |
| `neighbors()` / `neighbors_batch()` | 邻居查询（批量版 5 条 SQL 替代 31 条） |
| `doc_code_edges()` | 文档↔代码边单条参数化 SQL（nodes⋈edges DOCUMENTS_CODE⋈nodes，properties 原生 JSON 取锚点） |
| `counts()` | 节点/边计数（/projects 列表用） |

### 1.3 REST 端点全表（15 个）

**检索/查询**
- `POST /query`（L2437）——自然语言检索主入口。`extract_keywords()` 做 CN→EN 词表展开（静态表 + 项目 `.cbm-glossary.yaml`）后走 KBStore.search；`include_source` 参数给 top N 命中附源码段
- `POST /doc_code`（L2502）——文档↔代码双向映射。请求 `{file, project(kbId), direction: auto|doc2code|code2doc, limit, with_source, with_doc, exact}`；`_file_input_candidates()` 兼容平台完整路径（剥 `kb/<库名>/` 前缀 + basename 兜底），`_fuzzy_tier()` 分段 AND LIKE 模糊兜底；doc/code 两侧匹配分级（精确→后缀→整串包含→模糊）；doc2code 平铺、code2doc 按文档分组

**知识图谱（代码图谱 + 文档 KG 双源）**
- `POST /graph_search`（L2535）——关键字→中心实体邻接子图
- `POST /graph_node`（L2559）——节点详情 + 增量展开
- `POST /graph_suggest`——搜索框实体联想：代码候选优先 + KG 精确命中邻域补位（代码空时 KG 填满）；KG 源按库过滤——palace 显式传入或从 project 推导 kbId（projects-kb* 剥前缀 / 裸 kbId），非 kbId 项目保持全局（2026-10-22）
- `POST /graph_kg`——代理 mempalace 文档知识图谱（实体事实/邻域，边数上限 300 防爆图，超长实体名截断）；可选 palace=kbId 按库过滤（mempalace 侧按库文档集过滤全局三元组，响应回显 palace，2026-10-21）
- `GET /doc_view`（L2657）——文档原文页（按 doc_ref.line 锚点定位）
- `GET /graph`（L2708）——单文件 ECharts 力导图页面（服务端内嵌 HTML `_GRAPH_HTML`，无独立前端工程；实体类型中文化映射 21 类；CDN 三级回退）

**项目生命周期（异步 Job 系统）**
- `POST /projects`（L2730）——建索引：`ThreadPoolExecutor` 后台 Job，立即返回 `job_id`；RAG 上传代码后调的就是它（name=kbId）
- `GET /jobs/{job_id}` / `GET /jobs`（L2759/2769）——Job 状态轮询（stage: index→codegraph→done，内存态，重启丢历史）
- `DELETE /projects/{name}`（L2780）——删 CBM 索引 + kill codegraph daemon + 删 `.codegraph/`
- `POST /projects/{name}/reindex`（L2828）——强制重建
- 同项目互斥：`_has_running_job_for()`；核心流水在 `_run_add_project_job()`（L783）

**运维**
- `GET /health`（L2409）、`GET /projects`（L2420，600s TTL 缓存，含 kb_name 显示名）

### 1.4 其他关键机制

- **项目名解析** `resolve_project_name()`（L702）：kbId / 注册名 / 显示名三向兜底；`derive_project_name()` 按 `<父目录>-<子目录>` 推导 CBM 自动命名
- **文件多版本** `files_version`（L1045-1138）：POST /projects 接受 files_version 映射，落 `CACHE_DIR/file_versions/<name>.json`；snippet 附 file_version（版本化链路配套）
- **CN→EN 静态词表** `CN_TO_EN`（L965）+ 项目词表（glossary_extract 产物）
- **doc fragment 定位**（L1305）：按边锚点窗口（前 5 后 20 行，2000 字符上限），无锚点找 ref_name 首现行再退头部 40 行

---

## 2. mcp_stdio_bridge.py — MCP stdio→HTTP 桥

替代 supergateway（其 streamableHttp 实现在慢调用响应乱序时抛 "No connection established" 崩溃）。设计原则：**一个 HTTP 请求 = 子进程一次完整 JSON-RPC 往返**，不做跨请求响应路由，乱序崩溃从结构上不可能。

- `Session` 类（L132）：每会话一个 stdio 子进程 + 会话锁；空闲 1800s 回收，单调用硬上限 900s
- HTTP 面：`POST /mcp`（JSON-RPC 透传）、`DELETE /mcp`（会话终结）、`GET /healthz`；无 GET /mcp（不做 SSE 推送）
- 上游双模式：`BRIDGE_CHILD_CMD` 起子进程，或 `BRIDGE_UPSTREAM_URL` 纯代理既有 HTTP MCP
- 工具面改写：`BRIDGE_TOOL_ALLOWLIST`（白名单，9800 桥只放 11 个代码工具）、`BRIDGE_TOOL_DESC_PREFIX`（统一加【代码/源码】前缀）、`TOOL_PREFIX_FROM/TOOL_DESC_OVERRIDES`（改名/描述覆写）、`BRIDGE_SERVER_NAME`（改写 serverInfo.name，两个桥共用 hisi-code-mcp 名）
- 崩溃自愈：子进程崩溃自动重拉；桥自身由 entrypoint 的 watchdog 循环 2 秒重启

---

## 3. entrypoint.sh — 容器入口

四阶段串行，全流程幂等（重启安全）：

- **Phase 0 项目发现**：内置 `/opt/codebase-memory-mcp`、`/opt/codegraph` + `/projects/*` 每个子目录（跳过含 `code/` 的 RAG 上传目录，避免与 POST /projects 重复索引）+ `EXTRA_PROJECTS` env；EFS 属主/权限修正（cache-private 安全校验要求 0700 + 属主匹配，失败静默）
- **Phase 1a CBM 索引**：逐项目 `cli --json list_projects` 幂等检查，未索引才 `index_repository`（日志 `/var/log/cbm-index-*.log`）
- **Phase 1b codegraph init**：`.codegraph/codegraph.db` 存在即跳过；`CODEGRAPH_NO_WATCHDOG=1` 防大项目索引被 60s 看门狗误杀
- **Phase 1c 词表抽取（可选）**：配了 `DASHSCOPE_API_KEY` 时，为缺 `.cbm-glossary.yaml` 的项目后台串行跑 glossary_extract.py
- **Phase 2 守护进程**：CBM daemon（共享索引协调）+ 每项目一个 codegraph MCP daemon + 两个 mcp_stdio_bridge（9800 CBM / 9801 codegraph，watchdog 循环守护）
- **Phase 3 前台主进程**：存在 `/opt/cbm_sync.py` 则后台起 cbm_sync（本地盘方案），随后 `exec` nl_query_server.py（成为 tini 下主进程）

生产注记：DEV/TEST 实际生效的是 ConfigMap `dual-mcp-hotfix` 挂载版（subPath 覆盖镜像内同名文件）；DEV 额外用 deployment command 在此之前做 EFS→emptyDir 全量复制（本地盘方案）。

---

## 4. cbm_sync.py — 本地盘→EFS 回传

本地盘部署方案（DEV）配套：CBM db 放 emptyDir（快），EFS 降级为持久源。

- 每 `CBM_SYNC_INTERVAL`（默认 600s）一轮 `round_sync()`：
  - `*.db` → **sqlite3 backup API** 在线安全复制（防 WAL 撕裂），经 `.sync.tmp` + `os.replace` 原子落位
  - 其他文件 → mtime+size 变化才 copy2（未变跳过，防百库全量重传）
  - **不回删**（EFS 旧文件/备份保留）；跳过 `.db-wal/.db-shm/.sync.tmp`
- 路径：LOCAL=`CBM_CACHE_DIR`，EFS=`CBM_SYNC_DEST`（默认 `/var/lib/codebase-memory-mcp`）
- 自愈语义：pod 异常重启丢未回传写入（≤间隔窗口）时，Phase 1 检出 db 缺失/过期自动重索引

---

## 5. glossary_extract.py — CN→EN 查询词表抽取

解决中文问题查英文代码库（BM25 FTS 中文不命中）：采样项目标识符 → LLM 翻译 → 校验 → 写 YAML 词表，供 nl_query_server `extract_keywords()` 展开查询词。

流程（`main()`）：
1. `sample_vocab()`：扫源码文件（`CODE_EXTS`，跳过 SKIP_DIRS，上限 `MAX_FILES`=3000），camelCase/snake_case 切分 + 停用词过滤，取词频 top `MAX_VOCAB`=240
2. `call_llm()`：DashScope qwen-flash 批量翻译成 CN→EN 映射（key=API）
3. `validate()`：**防幻觉**——校验英文词确在项目词汇表内，不在则丢弃
4. `write_glossary()`：写 `<项目根>/.cbm-glossary.yaml`（含模型名溯源头），幂等可 `--force` 覆盖，可人工校订

产物随项目目录持久（NFS/PVC），一次性成本；C 端 bm25 检索合并静态表 + 项目词表（缺文件/坏文件静默回退）。

---

## 6. bin/ — 容器 CLI helper（4 个）

人工运维用 bash CLI，安装到容器 `/usr/local/bin/`，**服务调用链上无任何调用方**（entrypoint / REST / MCP 均不引用）。

| 命令 | 功能 | 实现 |
|---|---|---|
| `add-project <path>` | 索引新项目 + 启 codegraph daemon | CBM `cli --json index_repository` + `codegraph init`，双引擎幂等跳过 |
| `list-projects` | 列 CBM/codegraph 已索引项目 + 服务状态 | CBM `cli --json list_projects` + 进程/端口探测 |
| `query-project <name> <q...>` | NL 查询 | `curl` 打 REST `POST /query`（自动拼 JSON） |
| `remove-project <name_or_path>` | 删项目索引 + 停 codegraph daemon | CBM `cli --json` 删除 + pkill daemon |

---

## 附：部署速查

- 镜像构建：仓库根 `docker build`（三阶段：CBM C 编译 → codegraph TS 构建 → runtime）
- 热修链路：改本目录文件 → 更新 ConfigMap `dual-mcp-hotfix` 对应 key → PATCH deployment `redeploy-timestamp` 注解 rollout（数分钟生效，免镜像构建）
- 全量发布：构建新 SWR 镜像（vNext）→ PATCH deployment image → 数据经 EFS/本地盘方案自动延续
