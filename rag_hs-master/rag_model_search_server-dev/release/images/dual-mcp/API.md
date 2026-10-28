# Dual-MCP HTTP API Reference

**Base URL**: `http://<host>:<port>` （容器默认 `-p 9789:9788`，所以宿主机访问 `http://localhost:9789`）

**Content-Type**: `application/json`（POST/PUT 带 body 时）

**认证**: 无（假设内网/本地部署。需要鉴权时再加 token middleware）

**OpenAPI/Swagger**: `GET /docs`（交互式）、`GET /openapi.json`（schema）

---

## 目录

- [概览](#概览)
- [1. GET /health](#1-get-health)
- [2. GET /projects](#2-get-projects)
- [3. POST /query](#3-post-query)
- [4. POST /projects](#4-post-projects)
- [5. GET /jobs/{job_id}](#5-get-jobsjob_id)
- [6. GET /jobs](#6-get-jobs)
- [7. DELETE /projects/{name}](#7-delete-projectsname)
- [8. POST /projects/{name}/reindex](#8-post-projectsnamereindex)
- [Job 模型](#job-模型)
- [典型工作流](#典型工作流)
- [错误码](#错误码)
- [环境变量](#环境变量)

---

## 概览

容器对外暴露 **8 个 endpoint**，分两类：

| 类别 | Endpoint | 同步/异步 | 作用 |
|---|---|---|---|
| 只读 | `GET /health` | 同步 | 服务健康 |
| 只读 | `GET /projects` | 同步 | 列出已索引项目 |
| 只读 | `POST /query` | 同步 | CBM NL 查询 |
| 写入 | `POST /projects` | **异步** | 导入并索引新项目 |
| 写入 | `DELETE /projects/{name}` | 同步 | 删除项目 |
| 写入 | `POST /projects/{name}/reindex` | **异步** | 强制重索引 |
| Job | `GET /jobs/{id}` | 同步 | 查 job 状态 |
| Job | `GET /jobs` | 同步 | 列 jobs |

**关键概念**：所有"导入 / 索引"类操作走 **Job 异步模式**——POST 立即返 `job_id`，客户端轮询 `GET /jobs/{id}` 直到 `status` 变成 `done` 或 `failed`。原因：CBM + codegraph 索引大项目可能要几分钟到几十分钟。

---

## 1. GET /health

服务健康检查。

### 请求

```http
GET /health
```

无参数。

### 响应 · 200

```json
{
  "ok": true,
  "service": "nl-query",
  "cache_dir": "/var/lib/codebase-memory-mcp",
  "cached_projects": ["projects-Hi3516CV610", "opt-codegraph"]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | bool | 永远 true（不 true 就 5xx） |
| `service` | string | 固定 `"nl-query"` |
| `cache_dir` | string | CBM SQLite 索引存储目录 |
| `cached_projects` | string[] | 进程级 KBStore 缓存里的项目名（首次查过的才会出现，区别于 `/projects` 返回的"全部已索引项目"） |

### 示例

```bash
curl http://localhost:9789/health
```

---

## 2. GET /projects

列出所有已索引项目（CBM SQLite 里的全部）。

### 请求

```http
GET /projects
```

无参数。

### 响应 · 200

```json
{
  "projects": [
    {
      "name": "projects-Hi3516CV610",
      "nodes": 49739,
      "edges": 104901,
      "root_path": "/projects/Hi3516CV610"
    },
    {
      "name": "opt-codegraph",
      "nodes": 4772,
      "edges": 14100,
      "root_path": "/opt/codegraph"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `projects` | array | 按项目名字典序 |
| `projects[].name` | string | CBM 派生规则：`<父目录名>-<项目目录名>` |
| `projects[].nodes` | int | 节点总数（Function / Method / Class / File 等） |
| `projects[].edges` | int | 边总数（CALLS / IMPORTS / DEFINES 等） |
| `projects[].root_path` | string | 项目在容器内的绝对路径 |

### 示例

```bash
curl http://localhost:9789/projects | python3 -m json.tool
```

---

## 3. POST /query

CBM 自然语言查询：从中文/英文问题检索匹配的代码符号 + 调用关系子图。

### 请求

```http
POST /query
Content-Type: application/json

{
  "q": "memset_s init",
  "project": "projects-Hi3516CV610",
  "top": 6,
  "expand": true,
  "format": "json"
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `q` | string | ✅ | — | 自然语言问题（中文自动翻英文关键词） |
| `project` | string | ✅ | — | 项目名（来自 `/projects`） |
| `top` | int | ❌ | `6` | 返回节点数上限，1–50 |
| `expand` | bool | ❌ | `true` | 是否展开调用关系子图 |
| `format` | string | ❌ | `"json"` | `json` 或 `text`（text 适合 CLI 直接看） |

### 响应 · 200（format=json）

```json
{
  "question": "memset_s init",
  "project": "projects-Hi3516CV610",
  "search_terms": ["memset_s", "init"],
  "matches": [
    {
      "id": 8424,
      "label": "Function",
      "name": "memset_s",
      "qualified_name": "projects-Hi3516CV610.soc.platform.securec.src.memset_s.memset_s",
      "file_path": "soc/platform/securec/src/memset_s.c",
      "start_line": 469,
      "in_degree": 368,
      "out_degree": 1
    }
  ],
  "subgraph": {
    "nodes": [...],
    "edges": [
      {"src": "init_engine", "dst": "memset_s", "type": "CALLS"}
    ]
  },
  "elapsed_ms": 242.5
}
```

### 响应 · 200（format=text）

纯文本，人类友好，带 emoji 和缩进。适合 `curl` 直接看。

### 错误

| 状态码 | 含义 |
|---|---|
| 404 | 项目不存在（`project` 字段对应的 `.db` 文件找不到） |

### 示例

```bash
# JSON
curl -X POST http://localhost:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q":"memset_s","project":"projects-Hi3516CV610","top":5}'

# Text（CLI 友好）
curl -X POST http://localhost:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q":"配置加载","project":"projects-Hi3516CV610","format":"text"}'
```

---

## 4. POST /projects

导入新项目并异步建立 CBM + codegraph 双引擎索引。**立即返回 job_id**，索引在后台跑。

### 请求

```http
POST /projects
Content-Type: application/json

{
  "path": "/projects/my-new-project",
  "name": null,
  "force_reindex": false
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `path` | string | ✅ | — | 容器内项目根目录绝对路径（必须已存在） |
| `name` | string \| null | ❌ | `null` | 自定义项目名；不传则按 `<父目录>-<basename>` 自动派生 |
| `force_reindex` | bool | ❌ | `false` | true 时即使已索引也强制重建 |

⚠️ **路径必须是容器内可见的**。客户端需先把项目放到宿主机 `/data/`（默认挂载到容器 `/projects/`），再传 `/projects/<name>`。

### 响应 · 200（已排队）

```json
{
  "job_id": "b9bb7a25fea8",
  "project_name": "projects-my-new-project",
  "project_path": "/projects/my-new-project",
  "status": "queued",
  "log_path": "/var/log/job-b9bb7a25fea8.log",
  "poll": "/jobs/b9bb7a25fea8"
}
```

### 响应 · 400

```json
{"detail": "path is not a directory: /projects/nonexistent"}
```

### 响应 · 409

```json
{"detail": "project 'projects-X' already has a running job"}
```

同一项目已有 running/queued job 时拒绝，避免重复索引。

### 示例

```bash
# 导入已挂载项目
curl -X POST http://localhost:9789/projects \
  -H 'Content-Type: application/json' \
  -d '{"path":"/projects/Hi3516CV610"}'
# → {"job_id":"b9bb...", "poll":"/jobs/b9bb..."}

# 轮询
curl http://localhost:9789/jobs/b9bb7a25fea8
```

---

## 5. GET /jobs/{job_id}

查 job 详细状态。

### 请求

```http
GET /jobs/{job_id}
```

路径参数：`job_id`（POST /projects 或 POST /projects/{name}/reindex 返回的 12 字符 hex）

### 响应 · 200

```json
{
  "job_id": "b9bb7a25fea8",
  "type": "add_project",
  "project_name": "projects-Hi3516CV610",
  "project_path": "/projects/Hi3516CV610",
  "status": "done",
  "created_at": "2026-08-14T09:30:42",
  "started_at": "2026-08-14T09:30:42",
  "completed_at": "2026-08-14T09:30:52",
  "error": null,
  "log_path": "/var/log/job-b9bb7a25fea8.log",
  "stages": {
    "cbm_index": "done",
    "codegraph_init": "done",
    "codegraph_daemon": "done"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | string | `queued` / `running` / `done` / `failed` |
| `type` | string | `add_project`（POST /projects 触发）或 `reindex`（reindex 触发） |
| `stages.cbm_index` | string | CBM 索引阶段：`pending` / `running` / `done` / `failed` |
| `stages.codegraph_init` | string | codegraph init 阶段 |
| `stages.codegraph_daemon` | string | codegraph daemon 启动阶段 |
| `error` | string \| null | 失败时填，含原因（超时 / 子进程 rc / etc） |
| `log_path` | string | 该 job 的 stdout/stderr 日志文件（容器内路径） |

### 错误

| 状态码 | 含义 |
|---|---|
| 404 | job_id 不存在 |

### 示例

```bash
curl http://localhost:9789/jobs/b9bb7a25fea8 | python3 -m json.tool
```

---

## 6. GET /jobs

列出 jobs（默认按创建时间倒序）。

### 请求

```http
GET /jobs?status=running&limit=20
```

| Query 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `status` | string | (不过滤) | 可选值：`queued` / `running` / `done` / `failed` |
| `limit` | int | `50` | 1–500 |

### 响应 · 200

```json
{
  "jobs": [
    {
      "job_id": "b9bb7a25fea8",
      "type": "add_project",
      ...
    }
  ]
}
```

每个 job 字段同 [GET /jobs/{job_id}](#5-get-jobsjob_id)。

### 示例

```bash
# 看正在跑的
curl 'http://localhost:9789/jobs?status=running'

# 看最近 10 个
curl 'http://localhost:9789/jobs?limit=10'
```

---

## 7. DELETE /projects/{name}

删除项目。**同步执行**（< 5s）。

副作用按顺序：
1. 杀该项目对应的 codegraph daemon（SIGTERM，5s 后 SIGKILL）
2. `rm -rf <root_path>/.codegraph/`
3. `cbm cli delete_project` 删 CBM SQLite 索引
4. 从进程级 KBStore 缓存中清掉

### 请求

```http
DELETE /projects/{name}
```

路径参数：`name`（CBM 项目名，如 `projects-Foo`）

### 响应 · 200

```json
{
  "deleted": true,
  "name": "tmp-test",
  "steps": [
    "killed 1 codegraph daemon(s)",
    "removed /tmp/test/.codegraph/",
    "CBM project 'tmp-test' deleted"
  ]
}
```

### 错误

| 状态码 | 含义 |
|---|---|
| 404 | 项目不存在 |
| 500 | CBM delete_project 失败（步骤 1-2 已执行，3 失败） |

### 示例

```bash
curl -X DELETE http://localhost:9789/projects/tmp-test
```

---

## 8. POST /projects/{name}/reindex

对已存在项目强制重索引（CBM + codegraph 都重建）。立即返 job_id。

### 请求

```http
POST /projects/{name}/reindex
```

无 body。`name` 是路径参数。

### 响应 · 200

```json
{
  "job_id": "c1cc8b36af9b",
  "project_name": "projects-Hi3516CV610",
  "project_path": "/projects/Hi3516CV610",
  "status": "queued",
  "log_path": "/var/log/job-c1cc8b36af9b.log",
  "poll": "/jobs/c1cc8b36af9b"
}
```

### 错误

| 状态码 | 含义 |
|---|---|
| 404 | 项目不存在 |
| 409 | 该项目已有 running job |
| 400 | 项目 root_path 无效或不复存在 |

### 示例

```bash
curl -X POST http://localhost:9789/projects/projects-Hi3516CV610/reindex
```

---

## Job 模型

所有耗时操作（POST /projects、POST /projects/{name}/reindex）走 Job 异步模式：

```
客户端                              服务端
  │                                   │
  │── POST /projects ────────────────▶│ 立即创建 job，submit 到 ThreadPool
  │◀──────── {job_id, status:queued} ─│
  │                                   │
  │   每 5s                            │ 后台 worker 跑三阶段：
  │── GET /jobs/{id} ────────────────▶│   1) cbm_index     (CBM index_repository)
  │◀──── {status:running, stage...}── │   2) codegraph_init(codegraph init .)
  │                                   │   3) codegraph_daemon(serve --mcp &)
  │── GET /jobs/{id} ────────────────▶│
  │◀──── {status:done} ────────────── │
  │                                   │
```

**Job 状态机**：

```
queued ──▶ running ──┬──▶ done
                     └──▶ failed
```

**Stage 状态**：每个 stage 独立跟踪 `pending → running → done/failed`。任一 stage failed 整个 job failed。

**并发控制**：`ThreadPoolExecutor(max_workers=2)`，同时最多 2 个 job 跑。同一项目同时只能有 1 个 queued/running job（409 拒绝）。

**持久化**：job 状态在进程内存。**容器重启丢历史**，但项目索引本身在 SQLite / `.codegraph/` 里持久化。

**超时**：
- CBM index_repository: 30 分钟（`CBM_INDEX_TIMEOUT` env 可调）
- codegraph init: 30 分钟（`CODEGRAPH_INIT_TIMEOUT` env 可调）
- daemon 启动: 立即返回（不等待客户端连接）

**失败排查**：每个 job 的 stdout/stderr 写到 `/var/log/job-<id>.log`（容器内），客户端 GET /jobs/{id} 能看到 `log_path` 字段：

```bash
docker exec dual-mcp cat /var/log/job-b9bb7a25fea8.log
```

---

## 典型工作流

### 工作流 1：导入新项目 → 查询 → 删除

```python
import requests, time

BASE = "http://localhost:9789"

# 1) 导入
r = requests.post(f"{BASE}/projects", json={"path": "/projects/my-app"})
job_id = r.json()["job_id"]

# 2) 轮询
while True:
    j = requests.get(f"{BASE}/jobs/{job_id}").json()
    if j["status"] in ("done", "failed"):
        break
    time.sleep(5)

# 3) 查询
q = requests.post(f"{BASE}/query", json={
    "q": "init database",
    "project": j["project_name"],
    "top": 5,
}).json()
for m in q["matches"]:
    print(m["name"], m["file_path"])

# 4) 删除
requests.delete(f"{BASE}/projects/{j['project_name']}")
```

### 工作流 2：批量导入多个项目

```bash
for path in /projects/{proj-a,proj-b,proj-c}; do
  curl -X POST http://localhost:9789/projects \
    -H 'Content-Type: application/json' \
    -d "{\"path\":\"$path\"}"
done

# 轮询所有 running jobs
curl 'http://localhost:9789/jobs?status=running'
```

### 工作流 3：项目源码变更后重建索引

```bash
# 项目源码更新了，让 CBM/codegraph 重新索引
curl -X POST http://localhost:9789/projects/projects-my-app/reindex
# → {job_id: ...}

# 等
curl http://localhost:9789/jobs/<job_id>
```

---

## 错误码

| 状态码 | 触发场景 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误（path 不存在、root_path 无效等） |
| 404 | 资源不存在（项目名 / job_id 找不到） |
| 409 | 冲突（同项目已有 running job） |
| 500 | 服务端错误（CBM CLI 失败、子进程超时等） |

错误响应体统一为：

```json
{"detail": "<error message>"}
```

---

## 环境变量

服务端行为可由以下 env var 调（容器启动时通过 `-e` 覆盖）：

| 变量 | 默认 | 作用 |
|---|---|---|
| `CBM_CACHE_DIR` | `/var/lib/codebase-memory-mcp` | CBM SQLite 索引目录 |
| `CBM_BIN` | `/opt/codebase-memory-mcp/build/c/codebase-memory-mcp` | CBM 二进制路径 |
| `CODEGRAPH_BINARY` | `codegraph`（PATH 里找） | codegraph 可执行 |
| `LOG_DIR` | `/var/log` | job 日志目录 |
| `CBM_INDEX_TIMEOUT` | `1800`（30 min） | CBM index_repository 超时秒数 |
| `CODEGRAPH_INIT_TIMEOUT` | `1800` | codegraph init 超时秒数 |
| `NL_PORT` | `9788` | 服务监听端口 |
| `CODEGRAPH_NO_WATCHDOG` | `1`（entrypoint 设） | 禁用 codegraph 60s 看门狗 |

---

## SDK / 客户端

- **Python 参考客户端**：容器内 `/opt/codebase-memory-mcp/scripts/`（来自仓库 `scripts/`），含 `e2e_test.py`、`code_search.py`、`codegraph_client.py`、`test_nl_server.py`
- **端到端测试客户端**（仅标准库）：仓库 root `/root/dual_mcp_client.py`，跑 7 步全链路：
  ```bash
  python3 /root/dual_mcp_client.py
  ```
- **OpenAPI codegen**：`GET /openapi.json` 拿到 schema，用 `openapi-generator` 生成任意语言 SDK

---

## 限制与边界

| 项 | 说明 |
|---|---|
| **无认证** | 假设内网/本地部署，任何能访问端口的人都能增删项目 |
| **Job 历史不持久化** | 容器重启后 job 内存清空，但项目索引数据保留 |
| **同一项目并发限制** | 最多 1 个 queued/running job；并发提交返回 409 |
| **全局并发** | ThreadPoolExecutor max_workers=2，同时最多 2 个 job |
| **路径必须容器内可见** | 客户端需先挂载项目到容器 `/projects/`（或其他挂载点），再 POST 路径 |
| **不支持 git URL 导入** | 服务端不 clone，由客户端预先准备好项目目录 |
| **DELETE 同步阻塞** | 大项目的 daemon kill + 删 `.codegraph/` 可能花 5–10s |

---

## 附：MCP HTTP Bridges（Streamable HTTP 传输）

容器除了上面 8 个 REST endpoint（端口 9788），**还启动了两个 MCP-over-HTTP 桥**（supergateway），让外部 MCP 客户端可以直接连接，不用 `docker exec` 桥接 stdio。

### 端点

| Port | Path | 桥接的 MCP server | 工具数 |
|---|---|---|---|
| `9800` | `/mcp` | CBM（`/opt/codebase-memory-mcp/build/c/codebase-memory-mcp`） | 15（`search_graph`、`trace_path`、`get_code_snippet` 等） |
| `9801` | `/mcp` | codegraph for `/projects/Hi3516CV610` | 1+（`codegraph_explore` 等） |

`CG_SSE_BRIDGES` env var 改成多个路径可以暴露更多 codegraph MCP 实例，端口从 `CG_SSE_PORT_START`（默认 9801）递增分配。

### 协议：Streamable HTTP（MCP 2025-06-18）

每次请求 = 一个 HTTP POST，响应可以是普通 JSON 也可以是 SSE 流（带进度）。**业务层仍是 JSON-RPC 2.0 req→resp**，单个 tool call 一次请求一次响应，不需要维护长连接。

### 协议握手流程

```bash
# 1) initialize —— 拿到 Mcp-Session-Id
curl -i -X POST http://localhost:9800/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2024-11-05","capabilities":{},
                 "clientInfo":{"name":"my-client","version":"1.0"}}}'
# 响应头里:  Mcp-Session-Id: <hex>

# 2) notifications/initialized（必须）
curl -X POST http://localhost:9800/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: <hex>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3) tools/list
curl -X POST http://localhost:9800/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: <hex>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 4) tools/call —— 实际调用工具
curl -X POST http://localhost:9800/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: <hex>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"list_projects","arguments":{}}}'
```

### 用 MCP 客户端 SDK 连

**Python**（`pip install mcp`）：
```python
from mcp.client.streamable_http import streamablehttp_client
from mcp.client.session import ClientSession

async with streamablehttp_client("http://localhost:9800/mcp") as (read, write, _):
    async with ClientSession(read, write) as session:
        await session.initialize()
        tools = await session.list_tools()
        result = await session.call_tool("search_graph",
                                         {"query": "memset_s",
                                          "project": "projects-Hi3516CV610"})
        print(result)
```

**Claude Code / Cursor / Windsurf** 配置（`mcp_servers.json`）：
```json
{
  "mcpServers": {
    "cbm-dual-mcp": {
      "url": "http://localhost:9800/mcp"
    },
    "codegraph-hi3516": {
      "url": "http://localhost:9801/mcp"
    }
  }
}
```

注意：上面这种"远程 URL"配置要求 MCP 客户端支持 streamable-http transport（Cursor ≥ 0.42、Claude Code ≥ 1.0、Windsurf 最新版都支持）。老版本客户端只能用 stdio 配置：

```json
{
  "mcpServers": {
    "cbm-dual-mcp": {
      "command": "docker",
      "args": ["exec", "-i", "dual-mcp",
               "/opt/codebase-memory-mcp/build/c/codebase-memory-mcp"]
    }
  }
}
```

### 选用 stdio 还是 streamable-http？

| 场景 | 推荐 |
|---|---|
| 客户端和容器**同一台机器** | stdio（`docker exec -i`），零延迟、零额外端口 |
| 客户端**远程访问**（不同机器） | streamable-http（端口 9800/9801），跨网络可用 |
| 同一 MCP server **多客户端并发** | streamable-http（stdio 模式 supergateway 单连接会 crash） |
| IDE 集成（Cursor / Claude Code） | 看客户端版本——新版用 URL，老版用 stdio |

### 限制

- **无认证**：任何能访问 9800/9801 的人都能调 MCP 工具，包括删项目（`delete_project`）
- **无 HTTPS**：明文 HTTP，跨公网需在前面加反代（nginx + TLS）
- **Job 与 MCP 解耦**：MCP 的 `index_repository` tool **不走** REST API 的 job 系统，是直接调 CBM CLI 阻塞返回（大项目会让单次 HTTP 请求挂很久）。要进度用 REST API 的 `POST /projects` + job 轮询

### 环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `CBM_SSE_PORT` | `9800` | CBM MCP HTTP 桥端口；空字符串跳过 |
| `CG_SSE_BRIDGES` | `/projects/Hi3516CV610` | 要桥接的 codegraph 项目路径（空格分隔）；空字符串跳过所有 |
| `CG_SSE_PORT_START` | `9801` | codegraph 桥端口起始，每个项目递增 1 |

