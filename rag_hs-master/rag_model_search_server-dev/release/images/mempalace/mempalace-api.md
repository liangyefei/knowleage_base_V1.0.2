# MemPalace REST 接口对接文档

> 面向 **用 Python 应用对接 MemPalace REST 服务** 的开发者编写的接口说明文档。
>
> MemPalace（Python 包 `mempalace` v3.5.0）是一个 local-first 的 AI 长期记忆系统。它本身以 MCP（JSON-RPC over stdio / HTTP at `/mcp`）与 CLI 形态对外服务，**默认不提供公共 REST API**——出于 privacy-by-architecture 原则，原文记忆不应轻易离开本机。REST 适配器（`haisi-mempalace:rest` 镜像 / GPU 版 `haisi-mempalace:rest-bgem3`）是一个 **opt-in 的逃生舱**：当你确实需要把"存数据 / 查数据 / 建宫殿"以干净的 RESTful 形式暴露给另一个系统时，它把这层封装好。
>
> 适配器**不重新实现检索**：它直接复用 MCP server 使用的 `tool_search` / `tool_kg_query` / `tool_diary_write` 处理器，因此输入消毒、查询去污染、瞬态索引重试、打分逻辑与 MCP 客户端完全一致。适配器只额外加了：RESTful 端点、Bearer Token 鉴权（非 loopback 绑定 fail-closed）、可配置的敏感字段裁剪、以及串行化 Chroma 访问的全局锁。

- **基础 URL**：`http://192.168.80.250:8777`
- **传输**：HTTP / JSON（**明文**；内网可信环境直连即可，公网请前置 TLS 反代）
- **数据模型**：内容**原文 verbatim 存储**（不摘要），按 Wing（大类）→ Room（分组）→ Drawer（原文块）组织
- **交互式 API 文档**：`http://192.168.80.250:8777/docs`（Swagger UI）、`http://192.168.80.250:8777/redoc`

---

## 目录

1. [概述](#1-概述)
2. [鉴权](#2-鉴权)
3. [端点详情](#3-端点详情)
   - 3.1 [`GET /healthz` — 健康检查](#31-get-healthz--健康检查)
   - 3.2 [`GET /` — 服务信息](#32-get---服务信息)
   - 3.3 [`POST /palace` — 创建宫殿](#33-post-palace--创建宫殿)
   - 3.4 [`POST /store` — 存数据](#34-post-store--存数据)
   - 3.5 [`GET /search` — 语义检索](#35-get-search--语义检索)
   - 3.6 [`GET /kg` — 知识图谱查询](#36-get-kg--知识图谱查询)
4. [多宫殿（多库）机制](#4-多宫殿多库机制)
5. [敏感字段裁剪 detail](#5-敏感字段裁剪-detail)
6. [错误码](#6-错误码)
7. [环境变量](#7-环境变量)
8. [Python 应用对接](#8-python-应用对接)
9. [与 RAG 流水线衔接](#9-与-rag-流水线衔接)
10. [典型场景示例](#10-典型场景示例)
11. [参考资源](#11-参考资源)

---

## 1. 概述

| 项 | 值 |
|---|---|
| 基础 URL | `http://192.168.80.250:8777` |
| 传输协议 | HTTP / JSON（明文） |
| 鉴权 | Bearer Token（可选，可信内网可关） |
| 数据组织 | Wing（大类）→ Room（分组）→ Drawer（原文块） |
| 存储原则 | 原文 verbatim 存储，不摘要 |
| 交互式文档 | `/docs`（Swagger UI）、`/redoc` |
| 检索模型 | 向量 + BM25 关键词 + 时间/偏好加权（混合检索） |

REST 适配器跑在**与宫殿同一台机器**上——它是一次库调用，而非到 MemPalace 的网络跳转。暴露给局域网或公网前，应前置 Caddy/nginx 做 TLS 与访问控制。任何能触达已鉴权 `/search` 的人都能读到宫殿原文，因此 **token 必须像数据库凭证一样保管**。

端点总览：

| 方法 | 路径 | 鉴权 | 成功码 | 用途 |
|---|---|---|---|---|
| `GET` | `/healthz` | 否 | 200 | 健康检查（探活） |
| `GET` | `/` | 否 | 200 | 服务信息（端点清单 + 鉴权状态） |
| `POST` | `/palace` | 是 | 201 | 创建空宫殿（幂等） |
| `POST` | `/store` | 是 | 201 | 存入原文条目 |
| `GET` | `/search` | 是 | 200 | 语义检索 |
| `GET` | `/kg` | 是 | 200 | 知识图谱查询（只读） |

---

## 2. 鉴权

除 `/healthz` 与 `/` 外，所有端点默认需要请求头：

```
Authorization: Bearer <MEMPALACE_REST_TOKEN>
```

**关键行为**：

- **fail-closed**：当服务绑定到非 loopback 地址（`MEMPALACE_REST_HOST` 非 `127.0.0.1` / `localhost` / `::1`）且未设 token、又未显式开启 `MEMPALACE_REST_NO_AUTH` 时，**服务直接拒绝启动**。这是为了避免宫殿原文被任何能触达端口的人读取。
- **可信内网免鉴权**：在完全可信的网络（每个主机都是自己的）上，可设 `MEMPALACE_REST_NO_AUTH=1` 关闭鉴权，启动时会打醒目 WARNING。
- **loopback 免鉴权**：仅本机访问（`127.0.0.1`）时可不设 token。
- token 校验采用常量时间比较（`secrets.compare_digest`），不通过时序泄露 token 长度。
- 缺少或错误的 token → `401 Unauthorized`，响应头带 `WWW-Authenticate: Bearer`。

> ⚠️ **token 等同数据库凭证**——任何拿到 token 的人都能读取宫殿全部原文。务必妥善保管，不要硬编码进前端代码或提交进版本库。

---

## 3. 端点详情

### 3.1 `GET /healthz` — 健康检查

无鉴权，供反向代理 / 编排器探活。该端点 `include_in_schema=False`，不出现在 `/docs` 中。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /healthz` |
| 鉴权 | 否 |
| 成功响应 | `200`，body `"ok"`（text/plain） |

**请求示例**

```bash
curl http://192.168.80.250:8777/healthz
# ok
```

---

### 3.2 `GET /` — 服务信息

无鉴权，返回端点清单与当前鉴权状态。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /` |
| 鉴权 | 否 |
| 成功响应 | `200 OK`，JSON |

**请求示例**

```bash
curl http://192.168.80.250:8777/
```

**响应示例**

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

> `auth` 字段反映当前鉴权状态：`bearer token required`（已配 token）或 `OPEN (no auth)`（免鉴权）。

---

### 3.3 `POST /palace` — 创建宫殿

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
curl -X POST "http://192.168.80.250:8777/palace?palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应示例**

```json
{
  "success": true,
  "palace_path": "/data/palaces/kb-docs",
  "palace_id": "kb-docs",
  "already_exists": false
}
```

> 说明：宫殿也可在首次 `POST /store` 时**自动创建**（首写会触发嵌入模型加载/下载）。用本端点可提前占位、避免首写延迟；也适合给多租户场景预分配空库。

---

### 3.4 `POST /store` — 存数据

存入一条原文条目（timestamped，verbatim）。**首次写入会自动创建宫殿**并加载嵌入模型——仅首次，之后复用缓存。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `POST /store` |
| Content-Type | `application/json` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `201 Created` |

**Body（JSON）字段**

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `entry` | string | 是 | — | 要存储的原文（≥1 字符） |
| `agent_name` | string | 否 | `"mcp"` | 归属 agent（会转小写） |
| `topic` | string | 否 | `"general"` | 主题标签 |
| `wing` | string | 否 | `""` | 翼；留空则按 `agent_name` 自动派生 `wing_<agent>` |
| `palace` | string | 否 | `null` | 宫殿编号；省略则存入默认宫殿 |

**请求示例**

```bash
curl -X POST "http://192.168.80.250:8777/store" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "entry": "Project Aurora 用 PostgreSQL 16 + pgvector 做向量检索，截止 Q3 2026。",
       "agent_name": "ops",
       "topic": "architecture",
       "palace": "kb-docs"
     }'
```

**响应示例**

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

> **首次写入耗时**：嵌入模型首次加载/下载——`minilm` ~80MB、`embeddinggemma` ~300MB 首次需下载；`bgem3` 加载本地 2.3GB 模型到 GPU 约 10s。建议首写请求给长超时（如 300s），见 [§8](#8-python-应用对接)。

---

### 3.5 `GET /search` — 语义检索

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
| `max_distance` | float | 否 | `1.5` | 0.0–2.0 | 余弦距离阈值，越小越严（0 = 只召回几乎相同的） |
| `context` | string | 否 | — | — | 背景上下文（不参与嵌入，仅未来重排用） |
| `detail` | string | 否 | `snippet` | meta / snippet / full | 敏感字段裁剪档位，见 [§5](#5-敏感字段裁剪-detail) |
| `snippet_len` | int | 否 | `240` | 16–4000 | `detail=snippet` 时的截断长度 |
| `palace` | string | 否 | — | — | 宫殿编号；省略则查默认宫殿 |

**请求示例**

```bash
curl "http://192.168.80.250:8777/search?q=pgvector&limit=5&detail=snippet&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应示例（`detail=snippet`）**

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

> 💡 **召回偏松？** 默认 `max_distance=1.5` 较宽松，短文本 + `minilm` 可能弱匹配。需要更精确：调严到 `max_distance=0.6`，或换更强模型——`embeddinggemma`（多语言, 384d）或 `bgem3`（BGE-M3, 1024d, 中文/多语言最强, 需 GPU）。

---

### 3.6 `GET /kg` — 知识图谱查询

查询某实体在知识图谱（时序三元组库）中的关系。REST 适配层**只暴露 KG 的读**；写入事实需通过 MCP 工具 `mempalace_kg_add`。

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /kg` |
| 鉴权 | 是（除非 `NO_AUTH`） |
| 成功响应 | `200 OK` |

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 约束 | 说明 |
|---|---|---|---|---|---|
| `entity` | string | 是 | — | ≥1 字符 | 实体名（作为 subject 或 object） |
| `direction` | string | 否 | `both` | `outgoing` / `incoming` / `both` | 方向过滤 |
| `as_of` | string | 否 | — | `YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ` | 时间点过滤，只返回该时刻为真的事实 |
| `detail` | string | 否 | `snippet` | meta / snippet / full | `meta` 会把 subject/object 打码，见 [§5](#5-敏感字段裁剪-detail) |
| `palace` | string | 否 | — | — | 宫殿编号；省略则查默认宫殿 |

**请求示例**

```bash
curl "http://192.168.80.250:8777/kg?entity=Project+Aurora&direction=both&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

**响应示例**

```json
{
  "entity": "Project Aurora",
  "as_of": null,
  "direction": "both",
  "count": 2,
  "detail": "snippet",
  "facts": [
    { "subject": "Project Aurora", "predicate": "uses", "object": "PostgreSQL 16", "valid_from": "2026-01-01", "valid_to": null },
    { "subject": "Project Aurora", "predicate": "deadline", "object": "Q3 2026", "valid_from": "2026-01-01", "valid_to": null }
  ]
}
```

> 注：写入知识图谱事实需通过 MCP 工具 `mempalace_kg_add`（REST 适配层只暴露 KG 的**读**）。

---

## 4. 多宫殿（多库）机制

REST 适配器支持多宫殿隔离——调用方通过一个不透明的 `palace` 编号（如 `kb-docs` / `alice` / `team-2026`）选择宫殿，映射到 `MEMPALACE_PALACES_ROOT` 下的子目录。

- **物理映射**：`palace` 编号 → `/data/palaces/<id>`（根目录由 `MEMPALACE_PALACES_ROOT` 配置）。
- **安全校验**：编号经 MemPalace 自带的 `sanitize_name(palace_id, "palace")` 校验，拦截 `..` / `/` / `\` / 空字符等不安全字符，**防路径穿越**。如 `palace=../etc` 会被 `400` 拒绝。
- **完全隔离**：每个宫殿是完全独立的 ChromaDB 后端，存/查互不干扰。
- **默认宫殿**：不传 `palace` 参数时，走 `MEMPALACE_PALACE_PATH`（镜像默认 `/data/.mempalace/palace`）。**向后兼容**，老调用不受影响。
- **并发安全**：多宫殿切换在进程级锁 `_MEMPALACE_LOCK` 内串行执行——`MempalaceConfig.palace_path` 是每次访问都重读环境变量的属性，切换发生在序列化锁内，故并发请求定不同宫殿**不会串库**。即便 handler 失败，先前值（或其缺失）也会在 `finally` 中恢复。

**典型流程**

```bash
B=http://192.168.80.250:8777
TOKEN=...

# 建多个独立知识库
curl -X POST "$B/palace?palace=kb-docs"   -H "Authorization: Bearer $TOKEN"
curl -X POST "$B/palace?palace=alice"     -H "Authorization: Bearer $TOKEN"

# 各自存数据
curl -X POST "$B/store" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"entry":"文档库内容","palace":"kb-docs"}'

# 各自查询（互不干扰）
curl "$B/search?q=关键词&palace=kb-docs" -H "Authorization: Bearer $TOKEN"
```

---

## 5. 敏感字段裁剪 detail

`/search` 与 `/kg` 的 `detail` 参数控制返回多少原文，便于把"是否下发原文"的决策握在自己手里。默认档由 `MEMPALACE_REST_DEFAULT_DETAIL`（默认 `snippet`）决定。

### 5.1 `/search` 三档对照

| `detail` | `/search` 返回 | 适用场景 |
|---|---|---|
| `meta` | 翼/房间/相似度等元数据，**不含原文、不含来源路径** | 只想知道"有没有 / 在哪个房间" |
| `snippet`（默认） | meta + 原文截断（默认 240 字，可调 `snippet_len`） | 对接方看个上下文 |
| `full` | 完整原文 + 来源路径 | 完全信任的对接方 |

`/search` 的 `meta` 档保留字段（白名单）：`wing`、`room`、`source_file`、`created_at`、`similarity`、`distance`、`matched_via`。
`snippet` 档额外加：`text`（截断 + `…`）、`text_truncated`、`text_total_len`。
`full` 档保留 handler 返回的全部字段（含完整 `text` 与来源路径）。

### 5.2 内部字段剥离

任何档位都会剥掉这些内部簿记字段，避免泄漏到 REST 响应：

- `_sort_key`、`_source_file_full`、`_chunk_index`、`_parent_drawer_id`
- `closet_boost`、`effective_distance`

### 5.3 `/kg` 的 `meta` 档实体打码

`/kg` 的事实是三元组（subject + predicate + object + 时间窗）。`meta` 档会把 subject/object 打码、保留谓词与时间窗，调用方只能看到关系的"形状"：

- 打码规则：长度 >1 时取首字符 + `***`（如 `Alice` → `A***`）；长度为 1 时返回 `***`；空值返回空串。
- `meta` / `snippet` 档额外加 `value_redacted: true` 标记。
- `full` 档返回完整 dict（含原始 subject/object）。

---

## 6. 错误码

| HTTP | 含义 | 触发 |
|---|---|---|
| `200` | 成功（查询类） | `/search`、`/kg` 命中 |
| `201` | 创建成功 | `/palace`、`/store` |
| `400` | 请求错误 | 参数非法、宫殿编号含非法字符（`sanitize_name` 抛 `ValueError`）、handler 报错（如空宫殿查询返回 `No palace found`）、`detail` 取值非法、宫殿创建失败 |
| `401` | 未授权 | 缺少或错误的 Bearer token |
| `422` | 参数校验失败 | FastAPI 层校验（如 `q` 超长 >250 字符、`limit` 越界、`max_distance` 超出 0–2、`snippet_len` 越界、`direction` 不匹配 `^(outgoing\|incoming\|both)$`、body 字段类型错） |

**`400` 示例（查一个已建但空的宫殿）**

```json
{ "detail": "No palace found" }
```

**`401` 示例**

```json
{ "detail": "missing or invalid bearer token" }
```

**`422` 示例（FastAPI 校验失败，body 描述具体字段）**

```json
{
  "detail": [
    { "type": "value_error", "loc": ["query", "q"], "msg": "...", "input": "..." }
  ]
}
```

> 区分 `400` 与 `422`：`400` 来自适配器 handler 层（业务逻辑，如空宫殿、编号非法、`detail` 取值非法），`422` 来自 FastAPI/Pydantic 层（参数类型/约束校验）。

---

## 7. 环境变量

下表是**服务端启动配置**（容器启动时 `docker run -e ...` 或进程环境变量），**客户端只需 `base_url` + `token` 即可调用**，无需关心这些。

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEMPALACE_REST_TOKEN` | — | Bearer token；绑非 loopback 时**必填**（除非开 `NO_AUTH`） |
| `MEMPALACE_REST_NO_AUTH` | — | 设为 `1` 关闭鉴权（仅可信网络）；默认 fail-closed |
| `MEMPALACE_REST_HOST` | `127.0.0.1` | 绑定地址；内网用 `0.0.0.0` |
| `MEMPALACE_REST_PORT` | `8777` | 监听端口 |
| `MEMPALACE_REST_DEFAULT_DETAIL` | `snippet` | `detail` 参数的默认档（meta/snippet/full） |
| `MEMPALACE_PALACES_ROOT` | `/data/palaces` | 多宫殿根目录 |
| `MEMPALACE_PALACE_PATH` | `/data/.mempalace/palace` | 默认宫殿路径（不传 `palace` 时用） |
| `MEMPALACE_EMBEDDING_MODEL` | `minilm` | 嵌入模型：`minilm`(384d, 英) / `embeddinggemma`(384d, 多语言) / `bgem3`(1024d, BGE-M3, 最强多语言, 需 GPU 镜像) |
| `MEMPALACE_EMBEDDING_DEVICE` | `auto` | `cpu` / `cuda` / `coreml` / `dml`；`bgem3` 强烈建议 `cuda` |
| `MEMPALACE_BGE_M3_PATH` | `/disk/models/bge-m3` | 仅 `bgem3` 用：本地 BGE-M3 模型目录路径 |

> 客户端对接 `192.168.80.250:8777` 部署时，只需向服务端运维确认：(1) 是否开了鉴权（决定要不要传 token）；(2) 用的是哪个嵌入模型（影响首次写入耗时与召回质量）。其余服务端环境变量与客户端调用无关。

---

## 8. Python 应用对接

本节给出基于 `requests.Session` 的完整客户端封装，面向 `http://192.168.80.250:8777` 部署形态，含超时策略、错误处理与 detail 档位选择建议。代码风格与仓库内 `examples/rest_api_demo.py` 一致。

### 8.1 客户端封装 `MempalaceClient`

```python
#!/usr/bin/env python3
"""MemPalace REST 客户端封装 —— 面向 192.168.80.250:8777 部署形态。"""

from __future__ import annotations

import sys
import requests


class MempalaceClient:
    """MemPalace REST 适配层的薄封装。

    首次写入（POST /store）会触发嵌入模型加载/下载，可能耗时 10s~分钟级，
    故 store() 使用构造器传入的长超时（默认 300s）；其余短操作用 60s。
    """

    def __init__(self, base_url: str, token: str = "", timeout: int = 300):
        self.base = base_url.rstrip("/")
        self.timeout = timeout            # 首次写入长超时
        self.session = requests.Session()
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    # ---- /healthz ----
    def health(self) -> bool:
        """健康检查（/healthz 免鉴权）。返回是否在线。"""
        r = self.session.get(f"{self.base}/healthz", timeout=5)
        return r.status_code == 200

    # ---- /palace ----
    def create_palace(self, palace: str | None = None) -> dict:
        """创建宫殿（幂等；不下载嵌入模型，秒回）。"""
        params = {"palace": palace} if palace else {}
        r = self.session.post(f"{self.base}/palace", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    # ---- /store ----
    def store(self, entry: str, palace: str | None = None,
              topic: str = "general", agent_name: str = "demo") -> dict:
        """存入一条原文条目。首次写入会自动建宫 + 加载嵌入模型，用长超时。"""
        payload = {"entry": entry, "topic": topic, "agent_name": agent_name}
        if palace:
            payload["palace"] = palace
        # 首次写入会加载嵌入模型（GPU BGE-M3 ~10s，minilm/embeddinggemma 首次需下载）
        r = self.session.post(f"{self.base}/store", json=payload, timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    # ---- /search ----
    def search(self, query: str, palace: str | None = None,
               limit: int = 5, detail: str = "snippet",
               max_distance: float | None = None,
               snippet_len: int | None = None,
               wing: str | None = None,
               room: str | None = None) -> dict:
        """语义检索。detail 控制是否下发原文（meta/snippet/full）。"""
        params: dict = {"q": query, "limit": limit, "detail": detail}
        if palace:
            params["palace"] = palace
        if max_distance is not None:
            params["max_distance"] = max_distance
        if snippet_len is not None:
            params["snippet_len"] = snippet_len
        if wing:
            params["wing"] = wing
        if room:
            params["room"] = room
        r = self.session.get(f"{self.base}/search", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    # ---- /kg ----
    def kg(self, entity: str, palace: str | None = None,
           direction: str = "both", detail: str = "snippet",
           as_of: str | None = None) -> dict:
        """知识图谱查询。meta 档会把 subject/object 打码。"""
        params: dict = {"entity": entity, "direction": direction, "detail": detail}
        if palace:
            params["palace"] = palace
        if as_of:
            params["as_of"] = as_of
        r = self.session.get(f"{self.base}/kg", params=params, timeout=60)
        r.raise_for_status()
        return r.json()
```

### 8.2 端到端可运行示例

```python
BASE_URL = "http://192.168.80.250:8777"
TOKEN = ""            # 服务端开了 NO_AUTH=1 就留空；否则填 MEMPALACE_REST_TOKEN
PALACE = "demo-kb"


def main() -> int:
    client = MempalaceClient(BASE_URL, TOKEN, timeout=300)
    print(f"服务: {BASE_URL}  |  宫殿: {PALACE}  |  鉴权: {'token' if TOKEN else 'NO_AUTH(免)'}")

    # 0) 健康检查
    if not client.health():
        print(f"✗ 服务不可达：{BASE_URL}/healthz", file=sys.stderr)
        return 1
    print("✓ 服务在线\n")

    # 1) 建库（幂等；不加载模型，秒回）
    print(f"[1] 创建知识库 "{PALACE}"")
    res = client.create_palace(PALACE)
    print(f"    success={res['success']}  already_exists={res['already_exists']}")
    print(f"    palace_path={res['palace_path']}\n")

    # 2) 存数据（首次写入会加载嵌入模型，可能 10s~分钟级）
    entries = [
        ("MemPalace 用 ChromaDB 做向量存储，支持向量 + BM25 混合检索", "架构"),
        ("项目 Phoenix 部署在 Kubernetes 集群，上线截止 2026 年 Q3",     "进度"),
        ("BGE-M3 是 1024 维多语言嵌入模型，中文检索效果最好",              "模型"),
        ("Alice 负责后端，Bob 负责前端",                                   "团队"),
    ]
    print(f"[2] 存入 {len(entries)} 条数据")
    for entry, topic in entries:
        res = client.store(entry, palace=PALACE, topic=topic)
        print(f"    [{topic:4}] entry_id={res['entry_id'][:32]}…")
    print()

    # 3) 语义检索（用不同问法，验证语义而非关键词匹配）
    print("[3] 语义检索")
    for q in ["向量数据库的方案", "什么时候上线", "中文搜索用什么模型", "谁做前端"]:
        res = client.search(q, palace=PALACE, limit=1, detail="snippet")
        hits = res.get("results", [])
        if hits:
            h = hits[0]
            text = h.get("text", "")
            text = (text[:42] + "…") if len(text) > 42 else text
            print(f"    q="{q}"")
            print(f"      → sim={h.get('similarity')}  |  {text}")
        else:
            print(f"    q="{q}" → 无结果")
    print()

    # 4) KG 查询
    print("[4] KG 查询")
    kg_res = client.kg("Alice", palace=PALACE, direction="both", detail="snippet")
    print(f"    Alice 的关系数: {kg_res.get('count', 0)}")
    for f in kg_res.get("facts", []):
        print(f"    {f.get('subject')} --{f.get('predicate')}--> {f.get('object')}")
    print()

    # 5) 多宫殿隔离验证：建一个空库，查同一关键词，应为 0 命中
    other_palace = f"{PALACE}-other"
    print(f"[5] 隔离验证（建空库 "{other_palace}"，查同一关键词，应为 0 命中）")
    client.create_palace(other_palace)
    other = client.search("向量数据库", palace=other_palace, limit=5)
    back = client.search("向量数据库", palace=PALACE, limit=1)
    print(f"    "{other_palace}" 命中数: {other.get('count', 0)}  (应为 0 —— 与 "{PALACE}" 完全隔离)")
    print(f"    "{PALACE}"      命中数: {back.get('count', 0)}  (对照：原库正常召回)")
    print()

    print("✓ 完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

### 8.3 detail 档位选择建议

| 场景 | 推荐档位 | 理由 |
|---|---|---|
| 前端探活"有没有命中"、UI 角标计数 | `meta` | 不下发原文，零泄漏风险 |
| 给 LLM 做 RAG 拼 prompt、给对接方看上下文 | `snippet`（默认） | 截断原文足够拼上下文，又控制 token 量 |
| 完全信任的内部服务做全量导出 / 调试 | `full` | 拿到完整原文 + 来源路径，但需确保链路可信 |

`snippet` 档默认 240 字截断；需要更长上下文可传 `snippet_len=2000`（上限 4000）。

### 8.4 超时与错误处理建议

```python
import requests

try:
    res = client.store("某条原文", palace=PALACE)
except requests.HTTPError as e:
    status = e.response.status_code
    if status == 401:
        # token 错或过期——检查 TOKEN 配置
        print("鉴权失败：检查 token 是否正确")
    elif status == 400:
        # 参数错 / handler 业务错（如空宫殿查询返回 No palace found）
        print(f"请求错误：{e.response.json().get('detail')}")
    elif status == 422:
        # FastAPI 校验错——看 detail 里具体哪个字段
        print(f"参数校验失败：{e.response.json().get('detail')}")
    else:
        raise
except requests.Timeout:
    # 首次写入慢：可重试一次（模型已加载后第二次会很快）
    print("超时，重试一次…")
    res = client.store("某条原文", palace=PALACE)
except requests.ConnectionError:
    print("服务不可达，检查网络或服务是否在 192.168.80.250:8777")
```

要点：

- **401**：token 配置错——确认服务端是否开了 `NO_AUTH`，token 是否与服务端 `MEMPALACE_REST_TOKEN` 一致。
- **400**：业务层错误（如查一个已建但空的宫殿返回 `No palace found`、`palace` 编号含 `..` 等非法字符）。看 `detail` 字段。
- **422**：FastAPI/Pydantic 校验错（如 `q` 超过 250 字符、`limit` 越界）。`detail` 是数组，列出每个出错字段。
- **首次写入慢**：`store()` 第一次调用可能要加载嵌入模型（`minilm`/`embeddinggemma` 首次需下载，`bgem3` 加载 2.3GB 到 GPU ~10s）。构造器默认 `timeout=300`；若仍超时可重试一次（模型已加载后第二次会很快）。

---

## 9. 与 RAG 流水线衔接

推荐链路：

```
原文片段 ──POST /store──▶ 宫殿（verbatim + 向量索引）
                              │
                              ▼
                       GET /search?detail=snippet
                              │
                              ▼
                    召回片段拼入 LLM prompt
                              │
                              ▼
                    GET /kg?entity=...&as_of=...
                              │
                              ▼
                  时序事实增强 prompt（补"谁/何时/是否仍为真"）
```

具体建议：

1. **写入阶段**：把文档切片成段落级原文片段，逐条 `POST /store`。`agent_name` 标来源（如 `docs` / `slack`），`topic` 标主题。原文 verbatim 存储，不摘要——保证召回时 LLM 能看到一手材料。
2. **检索阶段**：`GET /search?detail=snippet` 取回片段拼入 LLM prompt。`snippet` 档默认 240 字截断，长上下文可调 `snippet_len`。
3. **事实增强**：`GET /kg?entity=...&as_of=YYYY-MM-DD` 取时序事实（如"Alice 在 2026-06 后还负责后端吗"），用 `as_of` 过滤到当时为真的事实。KG 写入需通过 MCP 工具 `mempalace_kg_add`。
4. **多用户/多租户隔离**：每个用户或租户一个 `palace` 编号，存查时都带 `palace` 参数。宫殿间完全独立，绝无串库风险。
5. **中文/多语言**：`minilm`（384d, 英文为主）对中文偏弱；中文或多语言场景用 `embeddinggemma`（384d）或 `bgem3`（1024d, BGE-M3, 中文/多语言最强, 需 GPU）。召回偏松时调严 `max_distance`（如 `0.6`）。
6. **维度变更**：`bgem3` 是 1024 维，与 `minilm`/`embeddinggemma`（384 维）不兼容。已有宫殿切 `bgem3` 必须执行 `mempalace repair rebuild-index` 全量重嵌入；全新宫殿无此问题。

---

## 10. 典型场景示例

### 场景 A：建一个文档知识库，存几篇，再查

```bash
B=http://192.168.80.250:8777
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

### 场景 C：只看元数据，不下发原文（最严，`detail=meta`）

```bash
curl "$B/search?q=关键词&palace=kb-docs&detail=meta" \
     -H "Authorization: Bearer $TOKEN"
# 返回: { count, results:[ {wing, room, similarity, ...} ] }  无 text
```

---

## 11. 参考资源

**仓库内源码（相对路径，位于 `d:\Code\haisi\mempalace`）**

- `examples/rest_adapter.py` — REST 适配器实现：全部端点定义、参数约束、fail-closed、多宫殿、detail 裁剪、KG 打码逻辑
- `examples/rest_adapter_API.md` — 官方 API 文档：端点说明、参数表、curl 示例、响应示例、错误码、环境变量表
- `examples/rest_api_demo.py` — Python `requests` 客户端示例：`MempalaceClient` 类、`health()`、`create_palace()`、`store()`、`search()`，首次写入 `timeout=300`
- `mempalace/mcp_server.py` — MCP server（适配器复用其 `tool_search` / `tool_kg_query` / `tool_diary_write` 处理器）

**交互式 API 文档（已部署服务）**

- Swagger UI：`http://192.168.80.250:8777/docs`
- ReDoc：`http://192.168.80.250:8777/redoc`
- 健康检查：`http://192.168.80.250:8777/healthz`
- 服务信息：`http://192.168.80.250:8777/`
