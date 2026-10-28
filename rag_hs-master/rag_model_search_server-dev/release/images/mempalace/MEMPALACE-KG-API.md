# MemPalace 知识图谱 API 接口文档

> 服务：`haisi-mempalace-rest`（CCE Autopilot 集群 `default` namespace，端口 8777）
> 数据：多宫殿目录 `/data/palaces/<palace_id>/`（store/search 按宫殿隔离）；
> **知识图谱为全局一张图**（`/data/.mempalace/knowledge_graph.sqlite3`，palace 参数对 /kg 无效）
> 认证：当前部署 `MEMPALACE_REST_NO_AUTH=1`，无 token；若关闭则所有接口需 `Authorization: Bearer <token>` 头

---

## 1. 服务地址

```bash
# 集群内（同 namespace pod）
BASE=http://haisi-mempalace-rest:8777
# 集群外调试（port-forward 后）
BASE=http://localhost:8777
```

健康检查（无需认证）：

```bash
curl http://haisi-mempalace-rest:8777/healthz
# → ok
```

---

## 2. 端点总览

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/healthz` | 存活探针 |
| GET | `/` | 服务信息（端点清单、detail 级别） |
| POST | `/palace` | 建宫殿（幂等，提前初始化存储） |
| POST | `/store` | 存记忆（verbatim 原文，不摘要、**不自动抽取实体**） |
| GET | `/search` | 语义搜索（向量检索已存文本） |
| GET | `/kg` | **知识图谱查询**：实体的关系边（三元组） |
| POST | `/kg` | **知识图谱写入**：新增一条 subject → predicate → object 事实 |

状态码约定：成功 `200`；创建类 `201`；参数错误 `422`（FastAPI 校验）/ `400`（业务错误）；认证失败 `401`。

---

## 3. POST /palace — 建宫殿（幂等）

提前创建一个空宫殿（存储目录 + ChromaDB collection），避免首次 store 触发模型下载卡顿。重复创建同名宫殿不报错。

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `palace` | string | 否 | 默认宫殿 | 宫殿 id，落在 `/data/palaces/<id>/`；省略用默认宫殿目录 |

**调用举例**

```bash
curl -X POST "http://haisi-mempalace-rest:8777/palace?palace=mydoc"
```

```json
{"success": true, "palace_path": "/data/palaces/mydoc",
 "palace_id": "mydoc", "already_exists": false}
```

再次调用同名：`"already_exists": true`。

---

## 4. POST /store — 存记忆

存入一条原文记忆（verbatim，不做摘要）。按 `agent_name` 自动归档到 diary 房间；内容不做实体抽取——**图谱数据需另行用 POST /kg 写入**。

**Body 参数（application/json）**

| 参数 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `entry` | string | ✓ | — | 要存的原文（≥1 字符） |
| `agent_name` | string | 否 | `mcp` | 归档来源（写入者标识），决定 wing 派生 |
| `topic` | string | 否 | `general` | 自由主题标签 |
| `wing` | string | 否 | 空 | 宫翼（分区）；为空时从 agent_name 派生 |
| `palace` | string | 否 | 默认宫殿 | 写入的宫殿 id |

**调用举例**

```bash
curl -X POST http://haisi-mempalace-rest:8777/store \
  -H 'Content-Type: application/json' \
  -d '{"entry": "dual-mcp REST 端口是 9789", "agent_name": "agent-1",
       "topic": "config", "palace": "mydoc"}'
```

```json
{"success": true,
 "entry_id": "diary_wing_agent-1_20260816_053834815303_6b35d5b1353c",
 "agent": "agent-1", "topic": "config", ...}
```

缺 `entry` → `422`（参数校验，字段 `detail` 说明原因）。

---

## 5. GET /search — 语义搜索

向量检索已存文本（ChromaDB），返回命中及相似度。中文查询必须 URL 编码（见下）。

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 范围 | 含义 |
|---|---|---|---|---|---|
| `q` | string | ✓ | — | ≤250 字符 | 查询文本（支持中文） |
| `limit` | int | 否 | 5 | 1–100 | 返回条数 |
| `wing` | string | 否 | — | | 按宫翼过滤 |
| `room` | string | 否 | — | | 按房间过滤 |
| `source_file` | string | 否 | — | | 按存储源路径精确过滤 |
| `max_distance` | float | 否 | 1.5 | 0–2 | 最大向量距离（越小越严格） |
| `context` | string | 否 | — | | 背景说明（不参与向量化） |
| `detail` | string | 否 | `snippet` | meta/snippet/full | `meta`=仅元数据无正文；`snippet`=元数据+正文前 240 字符；`full`=完整原文 |
| `snippet_len` | int | 否 | 240 | 16–4000 | detail=snippet 时的截断长度 |
| `palace` | string | 否 | 默认宫殿 | | 搜索的宫殿 id |

**响应字段说明**

| 字段 | 类型 | 含义 |
|---|---|---|
| `hits` | array | 命中列表，按相似度降序 |
| `hits[].wing` | string | 宫翼（写入时派生/指定） |
| `hits[].room` | string | 房间（diary_write 固定为 diary） |
| `hits[].source_file` | string | 源文件路径（verbatim 存储为 `?`） |
| `hits[].created_at` | string | 写入时间（ISO 8601） |
| `hits[].similarity` | float | 余弦相似度，0–1，越大越相关 |
| `hits[].distance` | float | 向量距离，越小越相关（与 similarity 互补） |
| `hits[].matched_via` | string | 命中来源（当前为 `diary`） |
| `hits[].text` | string | 命中原文（detail=snippet 时截断 `snippet_len` 字符） |
| `hits[].text_truncated` | bool | 原文是否被截断 |
| `hits[].text_total_len` | int | 原文总长度（判断截断程度） |
| `count` | int | 命中总数 |

> detail=meta 时无 `text`/`text_truncated`/`text_total_len`；detail=full 时 `text` 为完整原文。

**调用举例**

```bash
# 中文查询：curl 用 --get --data-urlencode 自动编码
curl --get http://haisi-mempalace-rest:8777/search \
  --data-urlencode "q=dual-mcp 部署在哪里" \
  --data-urlencode "palace=mydoc" \
  --data-urlencode "limit=3" \
  --data-urlencode "detail=snippet"
```

```json
{"hits": [
  {"wing": "wing_agent-1", "room": "diary", "source_file": "?",
   "created_at": "2026-08-16T05:38:34.264672",
   "similarity": 0.657, "distance": 0.3428, "matched_via": "diary",
   "text": "dual-mcp deployed on Huawei CCE ...", "text_truncated": false}
], "count": 3}
```

---

## 6. GET /kg — 知识图谱查询（核心）

查询一个实体在知识图谱中的所有关系边（三元组）。返回的事实支持方向过滤与时间过滤。

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `entity` | string | ✓ | — | 实体名（主语或宾语，按 direction 匹配） |
| `direction` | string | 否 | `both` | `outgoing`=实体作主语（A → ?）；`incoming`=实体作宾语（? → A）；`both`=双向 |
| `as_of` | string | 否 | — | 时间过滤：`YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ`，只看该时刻有效的事实 |
| `detail` | string | 否 | `snippet` | `full`=完整事实（含实体全名、置信度、来源）；`meta`/`snippet`=实体名脱敏（`d***`），只保留谓词+时间窗。**format=graph 时忽略，始终完整** |
| `format` | string | 否 | `facts` | `facts`=三元组列表；**`graph`=nodes+edges 图结构**，供知识图谱可视化插件直接消费（无需前端转换） |
| `palace` | string | 否 | 默认宫殿 | **当前对 /kg 无效**（图谱为全局单图，见文档开头） |

**响应字段说明（format=facts，默认）**

| 字段 | 类型 | 含义 |
|---|---|---|
| `entity` | string | 查询的实体名 |
| `as_of` | string/null | 时间过滤点（未过滤为 null） |
| `direction` | string | 查询方向（outgoing/incoming/both） |
| `count` | int | 事实条数 |
| `detail` | string | 生效的 detail 级别 |
| `facts[]` | array | 事实（三元组）列表 |
| `facts[].direction` | string | 查询实体在该事实中的位置：`outgoing`=实体是主语；`incoming`=实体是宾语（仅 detail=full 有） |
| `facts[].subject` | string | 主语实体名（detail≠full 时脱敏为 `首字母***`） |
| `facts[].predicate` | string | 关系名（如 deployed_on / has_port / connects_to） |
| `facts[].object` | string | 宾语实体名或值（detail≠full 时脱敏） |
| `facts[].valid_from` | string/null | 事实生效起点（ISO 日期/时间） |
| `facts[].valid_to` | string/null | 事实失效终点（null=当前仍有效） |
| `facts[].confidence` | float | 置信度 0–1（显式写入默认 1.0，仅 detail=full 有） |
| `facts[].source_closet` | string/null | 来源溯源 closet id（仅 detail=full 有） |
| `facts[].current` | bool | 是否当前有效（valid_to 为 null 即 true，仅 detail=full 有） |
| `facts[].value_redacted` | bool | 实体名是否已脱敏（detail≠full 时为 true） |

**响应字段说明（format=graph，可视化插件专用）**

| 字段 | 类型 | 含义 |
|---|---|---|
| `entity` | string | 查询的中心实体 |
| `as_of` | string/null | 时间过滤点 |
| `direction` | string | 查询方向 |
| `count` | int | 边条数 |
| `nodes[]` | array | 节点列表（已按实体名去重） |
| `nodes[].id` | string | 节点唯一标识（=实体名），插件用它做边连接 |
| `nodes[].name` | string | 显示名（=实体名） |
| `nodes[].type` | string | 节点类型，供插件分组着色；目前统一默认 `"文档"` |
| `edges[]` | array | 边列表 |
| `edges[].source` | string | 源节点 id（主语） |
| `edges[].target` | string | 目标节点 id（宾语） |
| `edges[].type` | string | 边类型（=关系名 predicate） |
| `edges[].label` | string | 边显示文字（=关系名） |
| `edges[].direction` | string | 查询实体在该边的位置（outgoing/incoming），可用于高亮 |
| `edges[].valid_from` | string/null | 事实生效起点 |
| `edges[].valid_to` | string/null | 事实失效终点（null=当前有效） |
| `edges[].confidence` | float | 置信度 0–1 |
| `edges[].current` | bool | 是否当前有效 |
| `edges[].source_closet` | string/null | 来源溯源 |

> graph 模式始终返回完整实体名（不受 detail 脱敏影响），节点为查询实体的**一跳邻居**。要展示多跳关系，可对每个邻居实体再查一次并合并节点集。

**调用举例**

```bash
# 查 dual-mcp 的所有关系（双向）
curl --get http://haisi-mempalace-rest:8777/kg \
  --data-urlencode "entity=dual-mcp" \
  --data-urlencode "direction=both"

# 只查"谁连到了 dual-mcp"（incoming）
curl --get http://haisi-mempalace-rest:8777/kg \
  --data-urlencode "entity=dual-mcp" \
  --data-urlencode "direction=incoming" \
  --data-urlencode "detail=full"

# 时间过滤：只看 2026-08-16 当天有效的事实
curl --get http://haisi-mempalace-rest:8777/kg \
  --data-urlencode "entity=dual-mcp" \
  --data-urlencode "as_of=2026-08-16"
```

响应（detail=full）：

```json
{
  "entity": "dual-mcp",
  "as_of": null,
  "direction": "both",
  "count": 2,
  "detail": "full",
  "facts": [
    {"direction": "outgoing", "subject": "dual-mcp",
     "predicate": "deployed_on", "object": "huawei-cce-autopilot",
     "valid_from": null, "valid_to": null,
     "confidence": 1.0, "source_closet": null, "current": true},
    {"direction": "outgoing", "subject": "dual-mcp",
     "predicate": "has_port", "object": "9789",
     "valid_from": null, "valid_to": null,
     "confidence": 1.0, "source_closet": null, "current": true}
  ]
}
```

响应（detail=snippet，默认，实体名脱敏）：

```json
{"entity": "dual-mcp", "count": 2, "facts": [
  {"predicate": "deployed_on", "subject": "d***", "object": "h***",
   "valid_from": null, "valid_to": null, "value_redacted": true}
]}
```

查询不存在的实体：`200` + `count: 0`（不报错）。

缺 `entity` 参数 → `422`。

### 6.1 format=graph 调用举例（可视化插件专用）

```bash
curl --get http://haisi-mempalace-rest:8777/kg \
  --data-urlencode "entity=dual-mcp" \
  --data-urlencode "direction=both" \
  --data-urlencode "format=graph"
```

```json
{
  "entity": "dual-mcp", "as_of": null, "direction": "both", "count": 3,
  "nodes": [
    {"id": "dual-mcp", "name": "dual-mcp", "type": "文档"},
    {"id": "huawei-cce-autopilot", "name": "huawei-cce-autopilot", "type": "文档"},
    {"id": "9789", "name": "9789", "type": "文档"},
    {"id": "rag-service", "name": "rag-service", "type": "文档"}
  ],
  "edges": [
    {"source": "dual-mcp", "target": "huawei-cce-autopilot",
     "type": "deployed_on", "label": "deployed_on", "direction": "outgoing",
     "valid_from": null, "valid_to": null, "confidence": 1.0,
     "current": true, "source_closet": null},
    {"source": "dual-mcp", "target": "9789",
     "type": "has_port", "label": "has_port", "direction": "outgoing",
     "valid_from": null, "valid_to": null, "confidence": 1.0,
     "current": true, "source_closet": null},
    {"source": "rag-service", "target": "dual-mcp",
     "type": "connects_to", "label": "connects_to", "direction": "incoming",
     "valid_from": null, "valid_to": null, "confidence": 1.0,
     "current": true, "source_closet": null}
  ]
}
```

### 6.2 常见可视化插件对接

**ECharts graph 系列**

```javascript
const d = await fetch(`${BASE}/kg?entity=dual-mcp&format=graph`).then(r => r.json());
option = {
  series: [{
    type: 'graph', layout: 'force',
    data: d.nodes.map(n => ({id: n.id, name: n.name})),
    links: d.edges.map(e => ({source: e.source, target: e.target, label: {show: true, formatter: e.label}})),
  }]
};
```

**Cytoscape.js**

```javascript
const d = await fetch(`${BASE}/kg?entity=dual-mcp&format=graph`).then(r => r.json());
const elements = [
  ...d.nodes.map(n => ({data: {id: n.id, label: n.name}})),
  ...d.edges.map(e => ({data: {id: `${e.source}-${e.type}-${e.target}`,
                                source: e.source, target: e.target, label: e.label}})),
];
```

**多跳图**：对 `nodes` 中感兴趣的非中心节点再调一次 `/kg?entity=<邻居>&format=graph`，把新的 nodes/edges 合并进已收集的集合（id 相同即同一节点，天然去重）。

---

## 7. POST /kg — 知识图谱写入

显式写入一条事实（三元组）。`/store` 只存文本不建边，**图谱数据全部经由本端点写入**。写入后立即可被 GET /kg 查询。

**Body 参数（application/json）**

| 参数 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `subject` | string | ✓ | — | 主语实体名（如服务名、组件名）；≤100,000 字符 |
| `predicate` | string | ✓ | — | 关系名（如 `deployed_on`、`has_port`、`connects_to`、`has_content`） |
| `object` | string | ✓ | — | 宾语实体名或值（如 IP、端口、环境名）；≤100,000 字符，可承载文档全文（如 `has_content` 边） |
| `valid_from` | string | 否 | null | 事实生效起点：`YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ` |
| `valid_to` | string | 否 | null | 事实失效终点（可回填历史事实的结束时间） |
| `source_closet` | string | 否 | null | 来源溯源：closet id |
| `source_file` | string | 否 | null | 来源溯源：源文件路径 |
| `source_drawer_id` | string | 否 | null | 来源溯源：抽屉 id |
| `palace` | string | 否 | 默认宫殿 | **当前对 /kg 无效**（图谱为全局单图） |

**调用举例**

```bash
curl -X POST http://haisi-mempalace-rest:8777/kg \
  -H 'Content-Type: application/json' \
  -d '{
    "subject": "rag-service",
    "predicate": "connects_to",
    "object": "dual-mcp",
    "valid_from": "2026-08-16",
    "source_file": "release/haisi-rag.yaml"
  }'
```

```json
{"success": true,
 "triple_id": "t_rag-service_connects_to_dual-mcp_41b74434b333",
 "fact": "rag-service → connects_to → dual-mcp"}
```

重复写入同一三元组幂等（同 subject+predicate+object 更新同一事实，返回新 triple_id）。

非法 temporal 值（如 `2026/08/16`）→ `400` + 错误说明。

---

## 8. 完整流程示例（bash）

```bash
BASE=http://haisi-mempalace-rest:8777
PALACE=api-demo

# 1. 建宫殿
curl -X POST "$BASE/palace?palace=$PALACE"

# 2. 存记忆（文本）
curl -X POST "$BASE/store" -H 'Content-Type: application/json' \
  -d "{\"entry\": \"dual-mcp REST port is 9789\", \"topic\": \"config\", \"palace\": \"$PALACE\"}"

# 3. 写图谱事实
curl -X POST "$BASE/kg" -H 'Content-Type: application/json' \
  -d '{"subject": "dual-mcp", "predicate": "has_port", "object": "9789"}'

# 4. 查图谱（完整值）
curl --get "$BASE/kg" \
  --data-urlencode "entity=dual-mcp" \
  --data-urlencode "direction=both" \
  --data-urlencode "detail=full"

# 5. 语义搜索
curl --get "$BASE/search" \
  --data-urlencode "q=dual-mcp 端口" \
  --data-urlencode "palace=$PALACE"
```

---

## 9. Python 客户端示例（纯标准库）

```python
import json, urllib.parse, urllib.request

BASE = "http://haisi-mempalace-rest:8777"
PALACE = "api-demo"

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, json.loads(r.read())

# 写事实
call("POST", "/kg", {"subject": "dual-mcp", "predicate": "has_port", "object": "9789"})

# 查事实（中文/特殊字符必须 urlencode）
q = urllib.parse.urlencode({"entity": "dual-mcp", "direction": "both", "detail": "full"})
st, d = call("GET", f"/kg?{q}")
print(d["count"], d["facts"])
```

---

## 10. 注意事项

1. **图谱不自动建边**：`/store` 是 verbatim 文本存储，不抽取实体；图谱事实必须 `POST /kg` 显式写入。
2. **图展示用 format=graph**：默认 `facts` 是三元组列表；可视化插件（ECharts/Cytoscape/vis.js/G6）请用 `format=graph` 直接拿 nodes+edges，且该模式不受 detail 脱敏影响（完整实体名）。
3. **kg 是全局单图**：palace 参数目前对 GET/POST /kg 无效，所有宫殿共享 `/data/.mempalace/knowledge_graph.sqlite3`；store/search 正常按 palace 隔离。
4. **中文查询必须 URL 编码**：curl 用 `--get --data-urlencode`，Python 用 `urllib.parse.urlencode`，直接拼中文会报 ASCII 编码错误。
5. **脱敏默认开启**：/kg 默认 `detail=snippet`，subject/object 显示为首字母+`***`；要完整实体名需显式 `detail=full`。
6. **无删除端点**：REST 面只提供写入与查询，宫殿/记忆/事实均无 DELETE（测试用宫殿建议带时间戳命名）。
7. **时间语义**：`valid_from`/`valid_to`/`as_of` 均接受 `YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ`（UTC）。事实可回溯失效（写入时带 `valid_to` 即为历史事实）。
8. **RAG 上传自动写图**：知识库上传文档时自动写入 `kbId --contains_doc--> 文档名`、`kbId --has_content--> 文档全文`，并用 LLM 抽取实体关系（`实体A --关系--> 实体B` + `文档名 --has_topic--> 实体`）。谓词风格参考：`same_as`/`master_of`/`named`/`born_at`/`discovered`/`learned`/`part_of` 等。
9. **节点 type 约定**：graph 格式 nodes 统一带 `type` 字段，MemPalace 默认 `"文档"`；dual-mcp 代码图谱默认 `"代码"`（两接口结构对齐，见 API-K8S.md）。
