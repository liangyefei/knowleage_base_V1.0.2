# Dual-MCP K8s API 调用文档

> 适用环境：CCE Autopilot 集群 `default` namespace，Service `haisi-dual-mcp`
> 版本：自写轻量 MCP 桥（mcp_stdio_bridge.py，替代 supergateway）

---

## 1. 服务地址

### 集群内访问（RAG 服务等 Pod 使用）

| 服务 | 地址 |
|---|---|
| REST API | `http://haisi-dual-mcp.default.svc.cluster.local:9789` |
| CBM MCP（15 工具） | `http://haisi-dual-mcp.default.svc.cluster.local:9800/mcp` |
| codegraph MCP | `http://haisi-dual-mcp.default.svc.cluster.local:9801/mcp` |

短名 `http://haisi-dual-mcp:9789`（同 namespace 内可用）。

### 集群外访问（本机调试）

```bash
kubectl port-forward svc/haisi-dual-mcp 9789:9789 9800:9800 9801:9801
# 然后访问 http://localhost:9789 / :9800/mcp / :9801/mcp
```

### 端口映射

| Service 端口 | 容器端口 | 用途 |
|---|---|---|
| 9789 | 9788 | REST（自然语言查询/项目管理） |
| 9800 | 9800 | CBM MCP（代码知识图谱，15 工具） |
| 9801 | 9801 | codegraph MCP（codegraph_explore） |

---

## 2. REST API

Base URL：`http://haisi-dual-mcp:9789`

### 2.1 健康检查

```bash
curl http://haisi-dual-mcp:9789/health
```

```json
{"ok": true, "service": "nl-query", "cache_dir": "/var/lib/codebase-memory-mcp", "cached_projects": []}
```

### 2.2 列出已索引项目

```bash
curl http://haisi-dual-mcp:9789/projects
```

```json
{
  "projects": [
    {"name": "opt-codebase-memory-mcp", "nodes": 19677, "edges": 108754,
     "root_path": "/opt/codebase-memory-mcp"},
    {"name": "testchip", "nodes": 7, "edges": 7, "root_path": "/projects/testchip/code"}
  ]
}
```

### 2.3 知识图谱查询（核心接口）

```bash
curl -X POST http://haisi-dual-mcp:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q": "memset_s", "project": "opt-codebase-memory-mcp", "top": 5}'
```

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `q` | string | ✓ | 查询关键词（支持中英文、驼峰拆分） |
| `project` | string | ✓ | 项目名（见 `/projects` 返回） |
| `top` | int | 否 | 返回条数，默认 6，范围 1–50 |
| `expand` | bool | 否 | 附带调用子图，默认 true |
| `format` | string | 否 | `json`（默认）/ `text` / **`graph`**（nodes+edges 图结构，供可视化插件直接使用） |

**响应（format=json，默认）**

```json
{
  "question": "memset_s",
  "project": "opt-codebase-memory-mcp",
  "search_terms": ["memset_s"],
  "matches": [
    {"name": "memset_s", "label": "Function",
     "file_path": "soc/platform/securec/src/memset_s.c",
     "start_line": 469, "end_line": 477,
     "in_degree": 368, "out_degree": 1,
     "qualified_name": "…securec.src.memset_s.memset_s"}
  ],
  "subgraph": {"nodes": [], "edges": []},
  "elapsed_ms": 242.5
}
```

**响应（format=graph，可视化插件专用）**

返回标准图结构：节点按符号名去重（`id` 即符号名，边以此连接），命中节点带 `matched: true` 及文件位置、调用度数；邻居节点仅含名称/类型/文件。**nodes/edges 结构与 MemPalace 的 graph 格式一致**（见 MEMPALACE-KG-API.md），同一套图谱插件可同时渲染两个数据源；区别仅在节点 `type` 默认值（本接口为 `"代码"`，MemPalace 为 `"文档"`）与额外的代码专属字段（label/matched/file_path 等）。

```bash
curl -X POST http://haisi-dual-mcp:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q": "pipeline", "project": "opt-codebase-memory-mcp", "top": 3, "format": "graph"}'
```

```json
{
  "question": "pipeline",
  "project": "opt-codebase-memory-mcp",
  "search_terms": ["pipeline"],
  "count": 3,
  "nodes": [
    {"id": "run_pipeline", "name": "run_pipeline", "type": "代码", "label": "Function",
     "matched": true, "file_path": "src/pipeline/pipeline.c",
     "start_line": 120, "in_degree": 12, "out_degree": 34,
     "qualified_name": "…pipeline.pipeline.run_pipeline"},
    {"id": "pass_structure", "name": "pass_structure", "type": "代码", "label": "Function",
     "matched": false, "file_path": "src/pipeline/pass_structure.c"}
  ],
  "edges": [
    {"source": "run_pipeline", "target": "pass_structure",
     "type": "CALLS", "label": "CALLS", "direction": "outgoing",
     "valid_from": null, "valid_to": null,
     "confidence": 1.0, "current": true, "source_closet": null}
  ],
  "elapsed_ms": 195.6
}
```

**graph 模式字段说明**

| 字段 | 类型 | 含义 |
|---|---|---|
| `question` / `project` / `search_terms` | string/array | 查询原文、项目名、提取的关键词 |
| `count` | int | 命中符号数（匹配节点数） |
| `nodes[].id` | string | 节点唯一标识（=符号名），边通过它连接 |
| `nodes[].name` | string | 显示名（=符号名） |
| `nodes[].type` | string | 节点类型，供插件分组着色；默认 `"代码"`（与 MemPalace 默认 `"文档"` 对应） |
| `nodes[].label` | string | 符号类型（Function/Method/Class/Variable） |
| `nodes[].matched` | bool | true=查询直接命中；false=调用关系邻居 |
| `nodes[].file_path` | string | 所在文件（命中节点才有；邻居节点同键名） |
| `nodes[].start_line` | int | 定义行号（命中节点才有） |
| `nodes[].in_degree` / `out_degree` | int | 被调用/调用次数（命中节点才有） |
| `nodes[].qualified_name` | string | 全限定名（命中节点才有） |
| `edges[].source` / `edges[].target` | string | 调用方向：source 调用 target（对应节点 id） |
| `edges[].type` / `edges[].label` | string | 边类型（当前为 `CALLS` 调用关系） |
| `edges[].direction` | string | 相对命中节点的方向（当前统一 `outgoing`，与 MemPalace 键名一致） |
| `edges[].valid_from` / `edges[].valid_to` | string/null | 事实生效/失效时间（代码图谱无时间维度，恒为 null） |
| `edges[].confidence` | float | 置信度 0–1（代码调用边恒为 1.0） |
| `edges[].current` | bool | 是否当前有效（恒为 true） |
| `edges[].source_closet` | string/null | 来源溯源（恒为 null，与 MemPalace 键名一致） |
| `elapsed_ms` | float | 查询耗时（毫秒） |

**插件对接（ECharts graph）**

```javascript
const d = await fetch(`${BASE}/query`, {
  method: 'POST', headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({q: 'pipeline', project: 'opt-codebase-memory-mcp', format: 'graph'}),
}).then(r => r.json());
option = {
  series: [{
    type: 'graph', layout: 'force',
    data: d.nodes.map(n => ({
      id: n.id, name: n.name,
      itemStyle: {color: n.matched ? '#f56c6c' : '#67c23a'},  // 命中/邻居不同色
    })),
    links: d.edges.map(e => ({source: e.source, target: e.target,
                              label: {show: true, formatter: e.label}})),
  }]
};
```

### 2.4 导入项目（异步）

```bash
curl -X POST http://haisi-dual-mcp:9789/projects \
  -H 'Content-Type: application/json' \
  -d '{"path": "/projects/mychip/code", "name": "mychip"}'
```

- `path`：**容器内路径**（项目须在共享卷 `/projects/` 下可见）
- `name`：项目名，缺省从路径派生。**约定：与知识库 kbId 同名**
- 返回 `202`：`{"job_id": "abc123", "project_name": "mychip", "status": "queued"}`
- `409`：该项目已有进行中的任务（幂等，可轮询现有 job）
- `400`：路径不存在

### 2.5 查询索引进度

```bash
curl http://haisi-dual-mcp:9789/jobs/<job_id>

# 列出进行中的任务
curl 'http://haisi-dual-mcp:9789/jobs?status=running&limit=20'
```

```json
{
  "job_id": "abc123", "type": "add_project", "project_name": "mychip",
  "status": "done",
  "stages": {"cbm_index": "done", "codegraph_init": "done", "codegraph_daemon": "done"},
  "created_at": "...", "started_at": "...", "completed_at": "...", "error": null
}
```

### 2.6 重建索引

```bash
curl -X POST http://haisi-dual-mcp:9789/projects/mychip/reindex
```

返回新 `job_id`，流程同 2.4。

### 2.7 删除项目

```bash
curl -X DELETE http://haisi-dual-mcp:9789/projects/mychip
```

```json
{"deleted": true, "name": "mychip",
 "steps": ["killed 1 codegraph daemon(s)", "removed .codegraph/", "CBM project deleted"]}
```

---

## 3. MCP 接口（Streamable HTTP）

### 3.1 协议要点（自写桥实现）

| 项 | 说明 |
|---|---|
| 端点 | `POST/DELETE /mcp`（GET 返回 405，不做服务端推送） |
| 响应格式 | **`application/json`**（单 JSON 对象，非 SSE） |
| 会话 | initialize 的响应头返回 `Mcp-Session-Id`；后续请求必须携带 |
| 通知 | 无 id 的消息（如 `notifications/initialized`）返回 `202`，无响应体 |
| 结束会话 | `DELETE /mcp`（带会话头）→ `204` |
| 健康 | `GET /healthz` → `{"ok":true,"sessions":N}` |
| 性能 | initialize 2-4s（子进程启动，每会话一次）；后续调用 0.0-0.5s |
| 会话超时 | 空闲 30 分钟自动回收 |
| 并发 | 每会话串行处理；多会话并行安全（已实测 4 并发） |

### 3.2 调用三步曲

**① initialize（拿 session id）**

```bash
curl -X POST http://haisi-dual-mcp:9800/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2024-11-05","capabilities":{},
                 "clientInfo":{"name":"my-client","version":"1.0"}}}' \
  -D -   # -D - 打印响应头，里面有 Mcp-Session-Id
```

响应头：`Mcp-Session-Id: 2c761c40...`

**② 通知 + 工具调用**

```bash
SID=<上面拿到的会话id>

# initialized 通知（返回 202，无 body）
curl -X POST http://haisi-dual-mcp:9800/mcp \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 工具调用：以 search_graph 为例
curl -X POST http://haisi-dual-mcp:9800/mcp \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"search_graph",
                 "arguments":{"project":"opt-codebase-memory-mcp",
                              "query":"pipeline","limit":3}}}'
```

响应（application/json）：

```json
{"jsonrpc":"2.0","id":2,"result":{
  "content":[{"type":"text","text":"total: 1194\nsearch_mode: bm25\nresults: 3 ..."}],
  "isError":false}}
```

> 注意：`content[0].text` 可能带一条 "Update available" 横幅行，业务解析时跳过含该关键词的行。

**③ 结束会话**

```bash
curl -X DELETE http://haisi-dual-mcp:9800/mcp -H "Mcp-Session-Id: $SID"
```

### 3.3 CBM MCP 工具清单（:9800，共 15 个）

**查询类（7 个）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `search_graph` | BM25 符号检索 | `project`✓, `query`, `label`, `name_pattern`(正则), `limit`, `min_degree`, `format:"json"` |
| `query_graph` | Cypher 图查询 | `project`✓, `query`✓（如 `MATCH (f:Function) RETURN f.name LIMIT 5`） |
| `trace_path` | 调用链追踪 | `project`✓, `function_name`✓, `direction: inbound/outbound/both`, `depth`, `mode: calls/data_flow` |
| `get_code_snippet` | 取符号完整源码 | `project`✓, `qualified_name`✓（search_graph 结果里有） |
| `get_architecture` | 架构概览 | `project`✓, `aspects:["overview"/"hotspots"/"clusters"/...]` |
| `search_code` | grep+图谱增强 | `project`✓, `pattern`✓, `mode:"compact"/"full"` |
| `detect_changes` | git diff 影响面 | `project`✓, `base_branch`, `since` |

**管理类（8 个）**：`list_projects`、`index_repository`、`index_status`、`check_index_coverage`、`get_graph_schema`、`delete_project`、`manage_adr`、`ingest_traces`

### 3.4 codegraph MCP（:981）

工具：`codegraph_explore`——按符号 bag 探索调用路径。

```json
{"name":"codegraph_explore",
 "arguments":{"query":"memset_s init", "projectPath":"/projects/mychip/code"}}
```

### 3.5 Python 完整示例

```python
import json, urllib.request

BASE = "http://haisi-dual-mcp:9800/mcp"

def post(payload, sid=None, timeout=300):
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    if sid:
        h["Mcp-Session-Id"] = sid
    req = urllib.request.Request(BASE, data=json.dumps(payload).encode(), headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.headers.get("Mcp-Session-Id"), json.loads(r.read())

sid, r = post({"jsonrpc":"2.0","id":1,"method":"initialize",
              "params":{"protocolVersion":"2024-11-05","capabilities":{},
                        "clientInfo":{"name":"demo","version":"1.0"}}})
post({"jsonrpc":"2.0","method":"notifications/initialized"}, sid)

_, r = post({"jsonrpc":"2.0","id":2,"method":"tools/call",
             "params":{"name":"search_graph",
                       "arguments":{"project":"opt-codebase-memory-mcp",
                                    "query":"pipeline","limit":3}}}, sid)
text = r["result"]["content"][0]["text"]      # 可能首行是横幅，注意过滤

req = urllib.request.Request(BASE, data=b"{}",
                             headers={"Mcp-Session-Id": sid}, method="DELETE")
urllib.request.urlopen(req, timeout=15).read()  # 结束会话
```

### 3.6 MCP 客户端配置（Claude Code 等）

支持 streamable HTTP 的客户端（响应为 JSON，无 SSE）：

```json
{
  "mcpServers": {
    "cbm":      {"type": "http", "url": "http://<集群可达地址>:9800/mcp"},
    "codegraph":{"type": "http", "url": "http://<集群可达地址>:9801/mcp"}
  }
}
```

集群内部署的 agent 直接用 Service DNS 地址即可。

---

## 4. 常用调用速查

| 我想要 | 调用 |
|---|---|
| 中文找代码 | REST `POST /query` |
| 谁调用了 X | MCP `trace_path` `direction:"inbound"` |
| X 调用了谁 | MCP `trace_path` `direction:"outbound"` |
| 拿函数源码 | MCP `get_code_snippet` |
| 自由图查询 | MCP `query_graph`（Cypher） |
| 项目全景 | MCP `get_architecture` |
| 导入新项目 | REST `POST /projects` + 轮询 `GET /jobs/{id}` |
| 排查桥状态 | `GET :9800/healthz` / `GET :9801/healthz` |

---

## 5. 项目命名约定

**kbId == dual-mcp 项目名**。RAG 侧上传项目 zip 后自动落 `/projects/<kbId>/code/` 并触发索引；检索时 kbId 直接作为 `project` 参数传入（REST `/query` 或 MCP 各工具的 `project` 参数）。
