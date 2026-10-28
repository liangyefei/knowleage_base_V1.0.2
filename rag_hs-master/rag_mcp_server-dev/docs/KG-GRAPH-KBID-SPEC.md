# /kg/graph-kg 知识库过滤接口变更规格（交付平台侧）

> 2026-10-22 ｜ 背景：知识图谱页选知识库「111」输入 "610" 仍返回其他库实体（跨库泄漏）。
> dual-mcp 与 mempalace 侧按库过滤已上线生效，缺口仅剩平台层两处：SPA 传参与 core-service 转发。

## 1. 链路现状（实测）

```
SPA GraphPage (VIP:80)
  ├─ POST /hisilicon-knowledge/kg/graph-search  {kbId, q}      → core-service → dual-mcp:9789 /graph_search   ✅ 已隔离
  ├─ POST /hisilicon-knowledge/kg/graph-node    {kbId, name}   → core-service → dual-mcp /graph_node          ✅ 已隔离
  ├─ POST /hisilicon-knowledge/kg/graph-suggest {kbId, q}      → core-service → dual-mcp /graph_suggest      ✅ dual-mcp 2026-10-22 起自动按库过滤（从 project 推导 palace），平台无需改
  └─ POST /hisilicon-knowledge/kg/graph-kg      {q}            → core-service → dual-mcp /graph_kg           ❌ 前端未传 kbId、接口未转发 → mempalace 全局 KG → 跨库
```

## 2. 变更需求：/kg/graph-kg 支持可选 kbId

**双模式语义**（与 graph-search 的 kbId 用法对齐）：

| 请求 | 行为 |
|---|---|
| `{"q": "610", "kbId": "kbca3c9491b67740b2b99200520dc81e49"}` | 仅查该知识库的图谱（按库文档集过滤三元组） |
| `{"q": "610"}`（kbId 缺省/null/空串） | 全库知识图谱（现状行为，向后兼容） |

### 2.1 core-service 转发规则

`/kg/graph-kg` 收到 kbId 后，转发 dual-mcp：

```
POST http://haisi-dual-mcp:9789/graph_kg
{"q": <q>, "palace": <kbId>}     # kbId 非空时
{"q": <q>}                        # kbId 缺省时（不带 palace 字段）
```

- **palace = 裸 kbId**（mempalace 宫殿名即平台库 id，勿加 `projects-` 前缀）
- kbId 缺省 / null / 空串 → 一律不传 palace 字段（不要传空串，空串等同缺省但走不同分支）

### 2.2 SPA GraphPage 传参

graph-kg 调用（现仅 `{q}`）加当前选中知识库，参照同文件 graph-suggest 的 `Pe` 函数模式：

```js
// 现：async function C(e){return(await y(`/kg/graph-kg`,{q:e})).data}
async function C(e,t){return(await y(`/kg/graph-kg`,{q:e,kbId:t??void 0})).data}
// 调用处传当前下拉选中 kbId；「全部/未选」传 null → 全库查询
```

### 2.3 行为约定（dual-mcp 侧已实现，直接依赖）

- 带 palace 且库内无匹配实体 → HTTP 200，`{count: 0, nodes: [], edges: [], hint: "..."}`（空结果=数据真值，非错误）
- palace 非法（不存在的库）→ dual-mcp 统一信封：HTTP 200 + `error: "mempalace /kg 不可达: HTTP Error 400..."`（mempalace 侧 400，dual-mcp 转错误字段，平台按 error 字段判错即可）
- 其余字段（nodes/edges/center/source/elapsed_ms）结构不变，仅多一个回显字段 `palace`

## 3. 验收用例（TEST 实测数据）

| 用例 | 请求 | 期望 |
|---|---|---|
| 111 库查 610 | `{"q":"610","kbId":"kbca3c9491b67740b2b99200520dc81e49"}` | count=0（111 库无 610 实体） |
| CV610-1 库查 610 | `{"q":"610","kbId":"kb33c11a4e73794826b9152313670c042d"}` | count=23（库内《离线语音硬件方案图.png》抽取的实体邻接） |
| 全库查 610 | `{"q":"610"}` | 与现状一致（全局 KG） |
| 页面 E2E | 选 111 输 610 搜索 | 图谱 0 边 + 联想下拉无跨库项 |

## 4. 附注

- `/kg/graph-suggest` 平台**无需改**：dual-mcp 已于 2026-10-22 部署从 project（=kbId）自动推导 palace；显式透传也支持（body 加 `palace` 字段，优先级高于推导）
- mempalace `GET /kg?palace=<kbId>` 按库过滤已于 2026-10-21 上线（按该库文档集 basename 过滤全局三元组，60s 缓存；已知限制：跨库同名文档会互相命中——粗粒度归属）
- 本规格对应 dual-mcp 提交：`hi_kb_base/rag_mcp_server dev`（graph_suggest palace 透传）；mempalace 提交：无（rest_adapter CM 热修）
