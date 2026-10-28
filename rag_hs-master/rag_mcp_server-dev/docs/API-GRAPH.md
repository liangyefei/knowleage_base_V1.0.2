# 知识图谱浏览 API 与页面文档（dual-mcp /graph）

> 服务：`haisi-dual-mcp`（DEV 集群，REST 9788 / Service 9789）
> 公网访问：`http://139.9.104.64:9789`
> 交互页面：`http://139.9.104.64:9789/graph`
> 认证：无（建议 ELB 监听器加 IP 白名单）

## 1. 端点总览

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/graph` | 知识图谱交互页面（单文件 HTML，双图谱视图） |
| GET | `/projects` | 已索引项目列表（含知识库名称 kb_name） |
| POST | `/graph_suggest` | 搜索框联想：实体候选（代码图谱 + 文档图谱双源） |
| POST | `/graph_search` | 代码图谱：按关键字查中心实体的网状子图 |
| POST | `/graph_node` | 代码图谱：节点详情（属性/邻边统计/源码段） |
| POST | `/graph_kg` | 文档知识图谱：mempalace KG 实体的网状子图 |
| GET | `/doc_view` | 原文查看页（图谱节点「查看原文」跳转目标） |
| POST | `/doc_code` | 文档↔代码双向映射（详见 API-DOCCODE.md，支持模糊/完整路径） |

页面交互模型：

```
搜索框输入 ──防抖250ms──> /graph_suggest ──> 候选下拉（↑↓选择/回车/点击）
搜索/选中候选 ──并行──> /graph_search（代码图谱）  ──> ECharts 力导图（视图一）
                 └────> /graph_kg（文档图谱，自动查询）──> ECharts 力导图（视图二）
节点单击 ──> 右侧属性面板（/graph_node 或 /graph_kg 详情）
节点双击 ──> 增量展开邻居（同上，candidates=0 合并显示）
「查看原文」──> 新标签页打开 /doc_view
```

自动切换规则：输入带文档扩展名（.md/.pdf 等）、或代码图谱命中的中心是
Document/文档名节点、且文档图谱有数据时，页面自动切到「文档图谱」视图；
代码符号类查询（如 `main`）始终留在代码图谱。

## 2. GET /projects — 已索引项目列表

```bash
curl http://139.9.104.64:9789/projects
```

```json
{
  "projects": [
    {"name": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
     "nodes": 81507, "edges": 163616,
     "root_path": "/projects/kbf1dc0cdb2ea844f7be38deca06843f52",
     "kb_name": "CV610ALL"}
  ]
}
```

| 字段 | 说明 |
|---|---|
| `name` | 项目名（kbId 项目带 `projects-` 前缀） |
| `nodes` / `edges` | 图谱规模 |
| `root_path` | 项目源码根目录 |
| `kb_name` | 知识库显示名（来自 `/projects/.kb-names.json` 映射，未注册项目为 null） |

> 所有接口的 `project` 参数接受 kbId 或项目名（服务端自动解析）。

## 3. POST /graph_search — 代码图谱网状子图查询

返回以命中实体为中心的**网状子图**：除中心的一跳边外，自动补**邻居之间的互连边**
（在册节点两两，上限 400 条），并对低相关实体剪枝（子图超 40 节点时，度数 1 的
悬挂点仅保留全局度数 top15）——避免星形展示。

**Body（application/json）**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `q` | string | 必填 | 实体关键字（符号名/文档名/文件名，支持部分匹配） |
| `project` | string | — | kbId 或项目名 |
| `limit` | int | 80 | 邻接边数上限（1–500） |
| `candidates` | int | 6 | 同名/近义其它匹配实体数（0–20） |

**调用举例**

```bash
curl -X POST http://139.9.104.64:9789/graph_search \
  -H 'Content-Type: application/json' \
  -d '{"q":"osal_dump_stack","project":"kbf1dc0cdb2ea844f7be38deca06843f52","limit":8}'
```

**响应（截选）**

```json
{
  "query": "osal_dump_stack",
  "project": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
  "center": {"id": 40561, "label": "Function", "name": "osal_dump_stack",
             "file_path": "hi_aiot_solution/sdk/.../osal_debug.c", "start_line": 32},
  "candidates": [{"id": 71236, "label": "Macro", "name": "OSAL_LOG_STACK_SIZE", ...}],
  "nodes": [
    {"id": 40561, "label": "Function", "name": "osal_dump_stack",
     "qualified_name": "...osal_debug.osal_dump_stack",
     "file_path": "hi_aiot_solution/sdk/.../osal_debug.c",
     "start_line": 32, "end_line": 35, "matched": true, "deg_local": 8},
    ...
  ],
  "edges": [
    {"source": 60000, "target": 40561, "type": "CALLS", "ref_name": "", "line": 233},
    ...
  ],
  "hint": null,
  "elapsed_ms": 43.4
}
```

| 字段 | 说明 |
|---|---|
| `center` | 中心实体（同名中度数最大者；无命中为 null） |
| `candidates` | 其它匹配实体（点选后成为新中心） |
| `nodes[].matched` | 是否查询中心（页面高亮描边） |
| `nodes[].deg_local` | 本地子图内度数（控制节点大小/标签显隐） |
| `edges[].type` | 边类型：CALLS / DOCUMENTS_CODE / IMPORTS / … |
| `edges[].ref_name`/`line` | 文档引用代码的锚点（引用写法+行号） |
| `hint` | 无命中/孤立节点时的提示文案 |

## 4. POST /graph_node — 代码图谱节点详情

**Body**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `name` | string | 必填 | 实体名 |
| `id` | int | null | 节点 id（图上点击时携带，优先于 name） |
| `project` | string | — | kbId 或项目名 |
| `file_path` | string | "" | 同名消歧提示（后缀匹配） |
| `with_source` | bool | true | 附源码段（直读源文件） |

**调用举例**

```bash
curl -X POST http://139.9.104.64:9789/graph_node \
  -H 'Content-Type: application/json' \
  -d '{"name":"osal_dump_stack","project":"kbf1dc0cdb2ea844f7be38deca06843f52","with_source":false}'
```

**响应（截选）**

```json
{
  "node": {
    "id": 40561, "label": "Function", "name": "osal_dump_stack",
    "file_path": "hi_aiot_solution/sdk/.../osal_debug.c",
    "start_line": 32, "end_line": 35,
    "in_degree": 30, "out_degree": 0,
    "out_types": {}, "in_types": {"CALLS": 29, "DEFINES": 1}
  },
  "elapsed_ms": 12.9
}
```

`with_source=true` 时附 `source` 字段（函数完整源码，2600 字符上限）；
`file_version` 字段在项目配置了版本映射时出现。

> 页面上实体类型显示为中文（Function→函数、Method→方法、Class→类、
> Macro→宏、File→文件、Document→文档、Section→章节 等 21 类映射），
> API 返回的 `label` 字段仍为英文原值。

## 4.5 POST /graph_suggest — 搜索框联想（双源）

输入片段返回实体候选，供搜索框下拉。**双源聚合**：

- **代码图谱**：左边界词段匹配（`(?i)(^|[^0-9A-Za-z])词段`，name 与
  file_path 双列）——`LED` 命中 led/led_init/hi_led/sample_led.c，不命中
  enabled/knowledge（词内嵌子串）；前缀 `led_i` 可联想 led_init
- **文档图谱（mempalace KG）**：q 精确命中 KG 实体时返回其自身 + 高度数
  邻居（KG 无名称搜索 API，仅精确命中可联想）；**按库过滤**（palace 语义
  见下，库内无该实体则 KG 源不出候选——跨库不联想）
- **混排**：代码候选优先，KG 补 3 条；代码侧为空时 KG 填满（如 `LED`——
  代码图谱无 LED 实体，KG 有 222 条边）

**Body**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `q` | string | 必填 | 实体名片段 |
| `project` | string | — | kbId 或项目名（代码源作用域） |
| `limit` | int | 10 | 候选条数（1–30） |
| `palace` | string | null | KG 源按知识库过滤（=kbId）。**缺省自动推导**：project 为 `projects-kb…`/裸 kbId 形态时取 kbId，否则不过滤（全局）；显式传入优先 |

> 按库联想（2026-10-22）：平台 core-service 把 kbId 映射为 project 转发，
> 推导即生效，调用方无需额外传 palace。

**调用举例**（真实库 kbf1dc0cdb2ea844f7be38deca06843f52 = DEV 的 CV610 代码库）

```bash
curl -X POST http://139.9.104.64:9789/graph_suggest \
  -H 'Content-Type: application/json' \
  -d '{"q":"osal_dump_stack","project":"kbf1dc0cdb2ea844f7be38deca06843f52","limit":6}'
```

**响应（实测）**

```json
{
  "query": "osal_dump_stack", "project": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
  "suggestions": [
    {"name": "osal_dump_stack", "label": "Function", "source": "code",
     "file": "osal_debug.c", "deg": 30},
    {"name": "osal_dump_stack", "label": "实体", "source": "kg", "file": "", "deg": 18},
    {"name": "osal_atomic_inc", "label": "实体", "source": "kg", "file": "", "deg": 3},
    {"name": "osal_atomic_dec", "label": "实体", "source": "kg", "file": "", "deg": 3}
  ],
  "elapsed_ms": 41.2
}
```

> project 传裸 kbId 即可（自动解析+推导 palace，KG 候选仅来自该库）；上述
> kg 候选均属 kbf1dc0 库——其他库的同名实体不会出现。

| 字段 | 说明 |
|---|---|
| `suggestions[].source` | `code`（代码图谱）或 `kg`（文档图谱）——前端按此分流查询 |
| `suggestions[].label` | code=英文类型（页面显示中文）；kg=7 类中文类型 |
| `suggestions[].deg` | code=全局度数；kg=该实体在 q 邻域内的度数 |

查询均带 LIKE 预筛（防 GIL 饥饿），典型耗时 <100ms。

## 5. POST /graph_kg — 文档知识图谱（mempalace KG）

代理 mempalace REST `GET /kg?entity=<q>&direction=both&format=graph`
（mempalace 仅集群内可达，公网访问必须走本端点）。
KG 为 mempalace 全局单图；**实体名精确匹配**（文档全名/kbId/普通实体）。
带 `palace` 时按库过滤（全局三元组 ∩ 该库文档集，mempalace 侧 60s 缓存，
实测冷查 ~0.2s）；库内无匹配实体返回 `count=0` + hint（空结果=数据真值）。

返回**网状子图**：一跳边之外，对高度数的前 6 个邻居并行再取一跳
（6 路 × 2s 超时），仅合并已在图中的节点间的互连边；子图超 40 节点时
对度数 1 的悬挂点剪枝（保留 top15）。实测 LED：52 节点 119 边 / ~250ms。

**Body**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `q` | string | ✓ | KG 实体名（如 `"BSP FAQ.md"`、kbId、`"孙悟空"`） |
| `palace` | string | — | 知识库 id（kbId）。带=仅查该库图谱；缺省=全库（向后兼容） |

**调用举例**（真实库 kbf1dc0cdb2ea844f7be38deca06843f52 = DEV 的 CV610 代码库）

```bash
# 全库模式
curl -X POST http://139.9.104.64:9789/graph_kg \
  -H 'Content-Type: application/json' -d '{"q":"osal_dump_stack"}'
# → count=48（全局 KG，22 节点）

# 按库模式（palace=kbId）
curl -X POST http://139.9.104.64:9789/graph_kg \
  -H 'Content-Type: application/json' \
  -d '{"q":"osal_dump_stack","palace":"kbf1dc0cdb2ea844f7be38deca06843f52"}'
# → count=36（仅该库文档抽取的事实，13 节点）
```

**响应（实测，按库模式截选）**

```json
{
  "query": "osal_dump_stack",
  "palace": "kbf1dc0cdb2ea844f7be38deca06843f52",
  "source": "mempalace-kg",
  "center": {"id": "osal_dump_stack", "name": "osal_dump_stack"},
  "count": 36,
  "nodes": [
    {"id": "osal_dump_stack", "name": "osal_dump_stack", "full_len": 15,
     "type": "实体", "matched": true},
    {"id": "hi3516cv610芯片", "name": "hi3516cv610芯片", "full_len": 13,
     "type": "芯片", "matched": false}
  ],
  "edges": [
    {"source": "osal_dump_stack", "target": "dump_stack", "type": "uses",
     "label": "uses", "confidence": 1.0, "valid_from": null, "valid_to": null,
     "current": true},
    {"source": "osal_dump_stack", "target": "hi3516cv610芯片", "type": "located_in",
     "label": "located_in", "confidence": 1.0, "valid_from": null, "valid_to": null,
     "current": true}
  ],
  "hint": null,
  "elapsed_ms": 551.5
}
```

> 对照：同一 q 不带 palace 返回 48 边（含其他库抽取的事实）；带 palace 后
> 36 边全部来自该库文档集。库内无该实体时 count=0 + hint（如 TEST 环境
> 111 库查 "610"——该库无此实体，而 CV610-1 库有 23 边）。

**实体类型（7 类，规则分类）**

| 类型 | 判定示例 | 页面配色 |
|---|---|---|
| 芯片 | Hi3516CV610、WS63、SoC、MCU、名含“芯片/处理器” | 红 #f87171 |
| 传感器 | sensor、os04d10_cmos、imx335、镜头 | 黄 #facc15 |
| 外设接口 | 摄像头/屏幕/emmc/DDR/开发板 + I2C/SPI/UART/GPIO/GND | 橙 #fb923c |
| 模块软件 | VI/VPSS/VENC/ISP/NPU/MPP + u-boot/linux/burntool | 紫 #a78bfa |
| 文档 | 带文件扩展名（.md/.pdf/.c/.h…） | 绿 #34d399 |
| 知识库 | kbId 形态（kb+16进制） | 青 #2dd4bf |
| 实体 | 兜底（含图表噪声词） | 灰 #cbd5e1 |

> 扩展新类型：后端 `_KG_KIND_RULES` 加一行 `("类型名", 正则)` + 前端
> `KG_COLORS` 加同名配色即可，图例/着色/面板自动生效。

**字段说明**

| 字段 | 说明 |
|---|---|
| `palace` | 请求回显（null=全库模式；2026-10-21 起随按库过滤上线） |
| `nodes[].type` | 上表 7 类之一 |
| `nodes[].full_len` | 实体全名长度（>80 为 has_content 长文本值，name 截断展示） |
| `edges[].confidence` | 事实置信度 0–1 |
| `edges[].valid_from`/`valid_to`/`current` | 时间有效性（null/null/true=当前有效） |
| `error` | mempalace 不可达/非法 palace 时的错误信息（HTTP 仍 200，按 error 字段判错；正常响应无此字段） |

单实体边数上限 300；多跳展开 = 对邻居实体再调本端点合并节点集。

## 6. GET /doc_view — 原文查看页

两种入口（二选一）：

| 参数组合 | 场景 | 说明 |
|---|---|---|
| `project` + `file` (+`line`) | 代码图谱节点 | `file` 为项目内相对路径；`line` 定位行 |
| `doc` + `kb` | KG 文档实体 | 按文件名在 `/projects/<kbId>/` 与 `/projects/kb/<库名>/` 定位 |

**行为**

- 文本/代码/Markdown：行号渲染页（2MB 上限），URL 加 `#L<行号>` 锚点高亮该行
- PDF / 图片：浏览器原生预览（FileResponse inline）
- Word/Excel/PPT/压缩包：触发下载
- 路径 realpath 校验防越界（`../` 拦截 404）；`doc` 定位失败 404 并提示

```bash
# 代码图谱入口（定位到第 32 行）
curl "http://139.9.104.64:9789/doc_view?project=kbf1dc0cdb2ea844f7be38deca06843f52&file=hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/kernel/osal_debug.c&line=32"

# KG 文档入口（按名+知识库定位）
curl "http://139.9.104.64:9789/doc_view?doc=SKILL.md&kb=kbf1dc0cdb2ea844f7be38deca06843f52"
```

## 7. 页面功能清单（GET /graph）

- **双图谱视图**：代码图谱（CBM SQLite，符号/文档/文件）｜文档图谱（mempalace KG，
  文档→实体关系），顶栏按钮切换，按钮显示文档图谱实时命中边数
- **搜索联想**：输入 250ms 防抖弹下拉（§4.5），↑↓/回车/Esc/点击可选；
  候选带来源徽标（代码 / 文档图谱），点击按来源分流查询
- **网状子图**：两个图谱均返回网状结构（含邻居互连边）+ 低相关悬挂点剪枝
  （详见 §3 / §5），不再是单中心星形
- **实体类型中文显示**：代码图谱 21 类类型在图例/悬浮/面板/联想中显示中文
  （Function→函数、Macro→宏、Section→章节…），API 原值仍为英文
- **知识库下拉联动**：显示知识库名称（悬浮见 kbId），两视图共用；切换知识库按
  当前视图自动重载；切到文档图谱且无数据时自动以 kbId 查询其 KG 子图
- **自动视图切换**：见第 1 节规则；代码符号查询不误切
- **节点面板**：代码视图=属性+邻边统计+源码段+「查看原文」；
  文档视图=类型徽标+**来源文档/所属知识库追溯**（入边推导，chips 可点击跳转）
  +三元组清单（含置信度）+「查看原文」（文档类型）
- **双击展开**：增量合并邻居（多跳）
- **浅色主题**：白底（#f5f7fa），与主系统风格一致；ECharts CDN 三级回退
  （npmmirror → jsdelivr → unpkg）

## 8. 相关配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEMPALACE_KG_BASE_URL` | http://haisi-mempalace-rest:8777 | /graph_kg 代理的 mempalace 地址 |
| `KB_NAMES_PATH` | /projects/.kb-names.json | kbId→知识库名映射文件（mtime 自动重载） |
| `KB_FILES_ROOT` | /projects | /doc_view 文档定位的根目录 |
| `CBM_PROJECTS_CACHE_TTL` | 600 | /projects 列表缓存秒数 |

## 9. 部署形态

- 源码：gitcode `hi_kb_base/rag_mcp_server` dev 分支
  `src/nl_query_server.py`
- 热更链路：ConfigMap `dual-mcp-hotfix` key `nl_query_server.py` + deployment
  `redeploy-timestamp` 注解 rollout（Phase 1 数分钟后 9788 生效）
- 页面为服务端内嵌单文件 HTML（`_GRAPH_HTML`），无独立前端工程
