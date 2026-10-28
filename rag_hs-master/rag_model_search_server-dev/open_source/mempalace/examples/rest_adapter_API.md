# MemPalace REST Adapter — API 文档

`mempalace:rest` 镜像对外暴露的 RESTful 接口。把 MemPalace 的「存数据 / 查数据 / 建宫殿」封装成 HTTP API，支持**多宫殿**（按宫殿编号隔离存查）和**敏感字段裁剪**。

- **传输**：HTTP / JSON（明文；内网可信环境可直接用，公网请前置 TLS 反代）
- **鉴权**：Bearer Token（`MEMPALACE_REST_TOKEN`），可在可信内网用 `MEMPALACE_REST_NO_AUTH=1` 关闭
- **数据模型**：内容**原文存储**（verbatim，不摘要），按 Wing（大类）→ Room（分组）→ Drawer（原文块）组织
- **基础 URL**：`http://<主机IP>:8777`

---

## 目录

1. [鉴权](#1-鉴权)
2. [`GET /healthz` — 健康检查](#2-get-healthz--健康检查)
3. [`GET /` — 服务信息](#3-get---服务信息)
4. [`POST /palace` — 创建宫殿](#4-post-palace--创建宫殿)
5. [`POST /store` — 存数据](#5-post-store--存数据)
6. [`GET /search` — 查数据（语义检索）](#6-get-search--查数据语义检索)
7. [`GET /kg` — 知识图谱查询](#7-get-kg--知识图谱查询)
8. [宫殿（多库）机制](#8-宫殿多库机制)
9. [敏感字段裁剪（detail）](#9-敏感字段裁剪detail)
10. [错误码](#10-错误码)
11. [环境变量](#11-环境变量)
12. [典型场景示例](#12-典型场景示例)

---

## 1. 鉴权

除 `/healthz`、`/` 外，所有端点默认需要请求头：

```
Authorization: Bearer <MEMPALACE_REST_TOKEN>
```

- 未设 token 时启动**自动 fail-closed**（绑非 loopback + 无 token + 未开 `NO_AUTH` → 拒绝启动）。
- 可信内网免鉴权：启动时设 `MEMPALACE_REST_NO_AUTH=1`，则所有端点免鉴权直通（启动会打醒目 WARNING）。

> ⚠️ token 等同数据库凭证——任何拿到 token 的人都能读取宫殿全部原文。务必妥善保管。

---

## 2. `GET /healthz` — 健康检查

无鉴权，供反代/编排器探活。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /healthz` |
| 鉴权 | 否 |
| 成功响应 | `200`，body `"ok"`（text/plain） |

```bash
curl http://<IP>:8777/healthz
# ok
```

---

## 3. `GET /` — 服务信息

无鉴权，返回端点清单与当前鉴权状态。

```bash
curl http://<IP>:8777/
```

```json
{
  "service": "mempalace-rest-adapter",
  "endpoints": {
    "palace": "POST /palace  (create an empty palace, idempotent)",
    "store": "POST /store  {entry, agent_name, topic, wing}",
    "search": "GET /search?q=...&limit=5&detail=snippet",
    "kg": "GET /kg?entity=...&direction=both"
  },
  "detail_levels": ["meta", "snippet", "full"],
  "auth": "bearer token required"
}
```

---

## 4. `POST /palace` — 创建宫殿

显式创建一个空宫殿（仅初始化后端结构，**不下载嵌入模型**，秒回）。幂等：宫殿已存在时返回 `already_exists: true` 而不报错。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `POST /palace` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `201 Created` |

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `palace` | string | 否 | — | 宫殿编号；省略则创建默认宫殿 |

**请求示例**

```bash
# 建一个名为 kb-docs 的宫殿
curl -X POST "http://<IP>:8777/palace?palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应**

```json
{
  "success": true,
  "palace_path": "/data/palaces/kb-docs",
  "palace_id": "kb-docs",
  "already_exists": false
}
```

> 说明：宫殿也可在首次 `POST /store` 时**自动创建**（首次写会触发嵌入模型加载/下载）。用本端点可提前占位、避免首写延迟。

---

## 5. `POST /store` — 存数据

存入一条原文条目（timestamped，verbatim）。**首次写入会自动创建宫殿**并加载嵌入模型（minilm/embeddinggemma 首次需下载，~80MB/~300MB；bgem3 加载本地 2.3GB 模型，GPU 约 10s）——仅首次，之后复用缓存。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `POST /store` |
| Content-Type | `application/json` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `201 Created` |

**Body（JSON）**

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `entry` | string | 是 | — | 要存储的原文（≥1 字符） |
| `agent_name` | string | 否 | `"mcp"` | 归属 agent（会转小写） |
| `topic` | string | 否 | `"general"` | 主题标签 |
| `wing` | string | 否 | `""` | 翼；留空则按 `agent_name` 自动派生（`wing_<agent>`） |
| `palace` | string | 否 | `null` | 宫殿编号；省略则存入默认宫殿 |

**请求示例**

```bash
curl -X POST "http://<IP>:8777/store" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "entry": "Project Aurora 用 PostgreSQL 16 + pgvector 做向量检索，截止 Q3 2026。",
       "agent_name": "ops",
       "topic": "architecture",
       "palace": "kb-docs"
     }'
```

**响应**

```json
{
  "success": true,
  "entry_id": "diary_wing_ops_20260626_015158127145_befc0c2ef230",
  "agent": "ops",
  "topic": "architecture",
  "timestamp": "2026-06-26T01:51:58.127145",
  "chunks": 1
}
```

---

## 6. `GET /search` — 查数据（语义检索）

对宫殿内容做混合检索（向量 + BM25 关键词 + 时间/偏好加权），返回原文片段或元数据。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /search` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `200 OK` |

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 约束 | 说明 |
|---|---|---|---|---|---|
| `q` | string | 是 | — | 1–250 字符 | 搜索关键词或问题 |
| `limit` | int | 否 | `5` | 1–100 | 最多返回条数 |
| `wing` | string | 否 | — | — | 按翼过滤 |
| `room` | string | 否 | — | — | 按房间过滤 |
| `source_file` | string | 否 | — | — | 按来源文件**完整路径**精确过滤 |
| `max_distance` | float | 否 | `1.5` | 0–2 | 余弦距离阈值，越小越严（0=只召回几乎相同的） |
| `context` | string | 否 | — | — | 背景上下文（不参与嵌入，仅未来重排用） |
| `detail` | string | 否 | `snippet` | meta/snippet/full | 敏感字段裁剪档位（见 [§9](#9-敏感字段裁剪detail)） |
| `snippet_len` | int | 否 | `240` | 16–4000 | `detail=snippet` 时的截断长度 |
| `palace` | string | 否 | — | — | 宫殿编号；省略则查默认宫殿 |

**请求示例**

```bash
curl "http://<IP>:8777/search?q=pgvector&limit=5&detail=snippet&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应（`detail=snippet`）**

```json
{
  "query": "pgvector",
  "filters": { "wing": null, "room": null, "source_file": null },
  "total_before_filter": 1,
  "count": 1,
  "detail": "snippet",
  "results": [
    {
      "wing": "wing_ops",
      "room": "diary",
      "source_file": "?",
      "created_at": "2026-06-26T01:51:58.127145",
      "similarity": 0.438,
      "distance": 0.562,
      "matched_via": "drawer",
      "text": "Project Aurora 用 PostgreSQL 16 + pgvector 做向量检索，截止 Q3 2026。",
      "text_truncated": false,
      "text_total_len": 52
    }
  ]
}
```

> 💡 **召回偏松？** 默认 `max_distance=1.5` 较宽松，短文本 + minilm 可能弱匹配。需要更精确：调严到 `max_distance=0.6`，或换更强模型——`embeddinggemma`(多语言,384d) 或 `bgem3`(BGE-M3,1024d,中文/多语言最强,需 GPU)。

---

## 7. `GET /kg` — 知识图谱查询

查询某实体在知识图谱（时序三元组库）中的关系。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /kg` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `200 OK` |

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `entity` | string | 是 | — | 实体名（作为 subject 或 object） |
| `direction` | string | 否 | `both` | `outgoing` / `incoming` / `both` |
| `as_of` | string | 否 | — | 时间点过滤（`YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ`），只返回该时刻为真的事实 |
| `detail` | string | 否 | `snippet` | meta / snippet / full；`meta` 会把 subject/object 打码（见 [§9](#9-敏感字段裁剪detail)） |
| `palace` | string | 否 | — | 宫殿编号；省略则查默认宫殿 |

**请求示例**

```bash
curl "http://<IP>:8777/kg?entity=Project+Aurora&direction=both&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应**

```json
{
  "entity": "Project Aurora",
  "as_of": null,
  "direction": "both",
  "count": 2,
  "detail": "full",
  "facts": [
    { "subject": "Project Aurora", "predicate": "uses", "object": "PostgreSQL 16", "valid_from": "2026-01-01", "valid_to": null },
    { "subject": "Project Aurora", "predicate": "deadline", "object": "Q3 2026", "valid_from": "2026-01-01", "valid_to": null }
  ]
}
```

> 注：写入知识图谱事实目前需通过 MCP 工具 `mempalace_kg_add`（REST 适配层暂只暴露 KG 的**读**）。

---

## 8. 宫殿（多库）机制

- **宫殿编号** → 物理路径：`<id>` 经校验后映射到 `/data/palaces/<id>`。
- **安全**：编号会拦截 `..` / `/` / `\` / 空字符等，**防路径穿越**（如 `palace=../etc` 会被 400 拒绝）。
- **隔离**：每个宫殿是完全独立的 ChromaDB 后端，存/查互不干扰。
- **默认宫殿**：不传 `palace` 参数时，走 `MEMPALACE_PALACE_PATH`（镜像默认 `/data/.mempalace/palace`）。**向后兼容**，老调用不受影响。
- **并发安全**：多宫殿切换在进程级锁内串行执行，多请求并发不会串库。

**典型流程**

```bash
# 建多个独立知识库
curl -X POST "$B/palace?palace=kb-docs"
curl -X POST "$B/palace?palace=alice"

# 各自存数据
curl -X POST "$B/store" -H "Content-Type: application/json" \
     -d '{"entry":"文档库内容","palace":"kb-docs"}'

# 各自查询
curl "$B/search?q=关键词&palace=kb-docs"
```

---

## 9. 敏感字段裁剪（detail）

`/search` 和 `/kg` 的 `detail` 参数控制返回多少原文，便于把"是否下发原文"的决策握在自己手里：

| `detail` | `/search` 返回 | 适用场景 |
|---|---|---|
| `meta` | 翼/房间/相似度等元数据，**不含原文、不含来源路径** | 只想知道"有没有/在哪个房间" |
| `snippet`（默认） | meta + 原文截断（默认 240 字，可调 `snippet_len`） | 对接方看个上下文 |
| `full` | 完整原文 + 来源路径 | 完全信任的对接方 |

- 任何档位都会剥掉内部字段（`_*`、`effective_distance` 等）。
- `/kg` 的 `meta` 档会把实体值打码（如 `Alice` → `A***`），保留谓词和时间窗。

---

## 10. 错误码

| HTTP | 含义 | 触发 |
|---|---|---|
| `200` | 成功（查询类） | `/search`、`/kg` 命中 |
| `201` | 创建成功 | `/palace`、`/store` |
| `400` | 请求错误 | 参数非法、宫殿编号含非法字符、handler 报错（如空宫殿查询返回 `No palace found`） |
| `401` | 未授权 | 缺少或错误的 Bearer token |
| `422` | 参数校验失败 | FastAPI 层校验（如 `q` 超长、`limit` 越界、body 字段类型错） |

**`400` 示例（查一个不存在的宫殿，会自动建宫后返回空；查一个**已建但空**的宫殿）：**

```json
{ "detail": "No palace found" }
```

---

## 11. 环境变量

容器启动时配置（`docker run -e ...`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEMPALACE_REST_TOKEN` | — | Bearer token；绑非 loopback 时**必填**（除非开 `NO_AUTH`） |
| `MEMPALACE_REST_NO_AUTH` | — | 设为 `1` 关闭鉴权（仅可信网络）；默认 fail-closed |
| `MEMPALACE_REST_HOST` | `127.0.0.1` | 绑定地址；内网用 `0.0.0.0` |
| `MEMPALACE_REST_PORT` | `8777` | 监听端口 |
| `MEMPALACE_REST_DEFAULT_DETAIL` | `snippet` | `detail` 参数的默认档 |
| `MEMPALACE_PALACES_ROOT` | `/data/palaces` | 多宫殿根目录 |
| `MEMPALACE_PALACE_PATH` | `/data/.mempalace/palace` | 默认宫殿路径（不传 `palace` 时用） |
| `MEMPALACE_EMBEDDING_MODEL` | `minilm` | 嵌入模型：`minilm`(384d,英)/`embeddinggemma`(384d,多语言)/`bgem3`(1024d,BGE-M3,最强多语言,需 GPU 镜像) |
| `MEMPALACE_EMBEDDING_DEVICE` | `auto` | `cpu`/`cuda`/`coreml`/`dml`；bgem3 强烈建议 `cuda` |
| `MEMPALACE_BGE_M3_PATH` | `/disk/models/bge-m3` | 仅 `bgem3` 用：本地 BGE-M3 模型目录路径 |

---

## 12. 典型场景示例

### 场景 A：建一个文档知识库，存几篇，再查

```bash
B=http://172.16.20.31:8777
TOKEN=...

# 1. 建库（可选；store 也会自动建）
curl -X POST "$B/palace?palace=kb-docs" -H "Authorization: Bearer $TOKEN"

# 2. 存三篇
for entry in "Aurora 用 pgvector" "后端选 FastAPI" "部署在 K8s"; do
  curl -s -X POST "$B/store" -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       -d "{\"entry\":\"$entry\",\"topic\":\"notes\",\"palace\":\"kb-docs\"}" >/dev/null
done

# 3. 语义检索
curl "$B/search?q=向量数据库&palace=kb-docs&detail=snippet" \
     -H "Authorization: Bearer $TOKEN"
```

### 场景 B：多用户隔离（每人一个宫殿）

```bash
curl -X POST "$B/store" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"entry":"Alice 的私事","palace":"alice"}'
curl -X POST "$B/store" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"entry":"Bob 的私事","palace":"bob"}'
# 查 Alice 的，绝不会返回 Bob 的数据
curl "$B/search?q=私事&palace=alice" -H "Authorization: Bearer $TOKEN"
```

### 场景 C：只看元数据，不下发原文（最严）

```bash
curl "$B/search?q=关键词&palace=kb-docs&detail=meta" \
     -H "Authorization: Bearer $TOKEN"
# 返回: { count, results:[ {wing, room, similarity, ...} ] }  无 text
```

---

## 附录：部署

**镜像**：`mempalace:rest`（基于 `mempalace:latest` + FastAPI/uvicorn）

**内网免鉴权启动（推荐，可信网络）**：

```bash
docker run -d --name mempalace-rest \
  --restart unless-stopped \
  -p 8777:8777 \
  -v mempalace-data:/data \
  -e MEMPALACE_REST_HOST=0.0.0.0 \
  -e MEMPALACE_REST_NO_AUTH=1 \
  mempalace:rest
```

**带鉴权 / 公网（前置 TLS 反代）**：

```bash
docker run -d --name mempalace-rest \
  -p 127.0.0.1:8777:8777 \
  -v mempalace-data:/data \
  -e MEMPALACE_REST_TOKEN="$(openssl rand -hex 32)" \
  mempalace:rest
# 然后 Caddy/nginx 反代 127.0.0.1:8777 做 HTTPS + 访问控制
```

**BGE-M3 + GPU（中文/多语言最强，需 NVIDIA GPU）**：用 `mempalace:rest-bgem3` 镜像（含 GPU torch + CUDA 库，~8.75GB）。

```bash
docker run -d --name mempalace-bgem3 --restart unless-stopped \
  --gpus all -p 8777:8777 \
  -v mempalace-data:/data \
  -v /disk/models/bge-m3:/models/bge-m3:ro \      # 挂载本地模型(只读)
  -e MEMPALACE_REST_HOST=0.0.0.0 -e MEMPALACE_REST_NO_AUTH=1 \
  -e MEMPALACE_EMBEDDING_MODEL=bgem3 \             # 启用 BGE-M3
  -e MEMPALACE_BGE_M3_PATH=/models/bge-m3 \        # 指向挂载路径
  -e MEMPALACE_EMBEDDING_DEVICE=cuda \             # 用 GPU
  mempalace:rest-bgem3
```

> ⚠️ BGE-M3 注意事项：
> - 首次 `POST /store` 会加载 2.3GB 模型到 GPU（约 10s），之后复用缓存；`POST /palace` 建库不加载模型。
> - 强烈建议 GPU（CPU 上每次嵌入慢一个数量级）。RTX 5090(Blackwell/sm_120) 需 cu128 版 torch。
> - **维度变更**：bgem3 是 1024 维，与 minilm/embeddinggemma(384 维) 不兼容。已有宫殿切 bgem3 必须 `mempalace repair rebuild-index` 全量重嵌入；全新宫殿无此问题。

**交互式 API 文档**（FastAPI 自带）：启动后访问 `http://<IP>:8777/docs`（Swagger UI）或 `/redoc`。
