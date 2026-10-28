# API：按知识图谱关系查询文档片段（/graph_fragment）

> 版本：2026-10-23 ｜ 适用环境：TEST / DEV（dual-mcp 9789 + mempalace 8777）
> 使用场景：http://139.9.104.64:9789/graph（DEV 公网）文档知识图谱右侧实体关系面板，每条关系边的「片段」按钮即调用本接口。

## 1. 概述

在文档知识图谱（mempalace KG）中，给定一条实体关系（主体 → 谓词 → 客体），返回该关系**来源文档中的对应片段**：片段文本、片段所在文档的全路径、片段序号。

定位链路：

```
三元组 (subject, predicate, object)
  → 查 KG triples 表的 source_file（关系抽取来源文档）
  → 在知识库 palace 的 chroma 存储中按文档名（room/topic）匹配 chunk
  → 筛选文本同时包含主体与客体的 chunk（退化为含其一）
  → 返回片段文本 + 全路径来源 + chunk 序号
```

## 2. 接口：`POST /graph_fragment`

### 2.1 请求

- 方法：`POST`
- 路径：`/graph_fragment`
- 地址：集群内 `http://haisi-dual-mcp:9789/graph_fragment`；DEV 公网 `http://139.9.104.64:9789/graph_fragment`
- Content-Type：`application/json`

### 2.2 参数说明

| 参数 | 类型 | 必填 | 说明 | 来源 |
|---|---|---|---|---|
| `subject` | string | 是 | 关系主体实体名 | `POST /graph_kg` 响应 `edges[].source` |
| `predicate` | string | 否 | 关系谓词；缺省/空串时按「主体+客体」匹配 | `POST /graph_kg` 响应 `edges[].type` |
| `object` | string | 否 | 关系客体实体名 | `POST /graph_kg` 响应 `edges[].target` |
| `palace` | string | 是 | 知识库 id（kbId），片段只在该库范围内查找 | 图谱页「选择知识库」下拉选中项的 **kbId** |

### 2.3 入参来源详解

#### 2.3.1 `subject` / `predicate` / `object` —— 来自文档知识图谱接口 `/graph_kg` 的关系边

在文档知识图谱页选中实体节点后，前端调用 `/graph_kg` 获取该实体的一跳关系边，每条边的三个字段与本接口一一对应：

| /graph_kg 响应边字段 | 含义 | 作为本接口入参 |
|---|---|---|
| `edges[].source` | 边起点（主体实体名） | `subject` |
| `edges[].type` | 边类型（关系谓词） | `predicate` |
| `edges[].target` | 边终点（客体实体名） | `object` |

`/graph_kg` 调用示例（DEV 公网，CV610 库查实体 `hi3516cv610`）：

```bash
curl -X POST "http://139.9.104.64:9789/graph_kg" \
  -H "Content-Type: application/json" \
  -d '{"q":"hi3516cv610","palace":"kbfccb654a43f241cbb520174f30951e81"}'
```

**返回节选**（响应中 `edges[]` 的一条）：

```json
{
  "query": "hi3516cv610",
  "palace": "kbfccb654a43f241cbb520174f30951e81",
  "count": 67,
  "edges": [
    {
      "source": "hi3516cv610",
      "target": "sample_vie",
      "type": "has_sample",
      "label": "has_sample",
      "confidence": 1.0,
      "valid_from": null,
      "valid_to": null,
      "current": true
    }
  ]
}
```

取这条边后，本接口的入参即为：

```json
{
  "subject": "hi3516cv610",
  "predicate": "has_sample",
  "object": "sample_vie",
  "palace": "kbfccb654a43f241cbb520174f30951e81"
}
```

#### 2.3.2 `palace` —— 图谱页「选择知识库」下拉选中项的 kbId

palace 即图谱页顶部「选择知识库」下拉框当前选中项的 kbId（形如 `kb` + 32 位十六进制，如 `kbfccb654a43f241cbb520174f30951e81`）。该值在选中知识库时页面已持有，直接作为本参数传入即可，无需额外调用接口获取。

### 2.4 响应说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `subject` / `predicate` / `object` | string | 回显请求参数 |
| `count` | int | 命中的片段数 |
| `fragments` | array | 片段列表 |
| `fragments[].text` | string | 片段文本（超 2000 字符截断） |
| `fragments[].source_file` | string | 片段来源文件**全路径**（如 `/kb-files/kb/CV610ALL/.../sensor.md`） |
| `fragments[].room` | string | 来源文档名（basename，用于「查看原文」跳转） |
| `fragments[].chunk_index` | string | 片段在文档中的分块序号（可能为空） |
| `elapsed_ms` | float | 接口耗时（毫秒） |
| `error` | string | 仅异常时存在（mempalace 不可达等） |

### 2.5 调用示例：按库查询（DEV 公网，CV610ALL 库，带 palace）

```bash
curl -X POST "http://139.9.104.64:9789/graph_fragment" \
  -H "Content-Type: application/json" \
  -d '{"subject":"single","predicate":"includes","object":"lcd_实时预览","palace":"kbf1dc0cdb2ea844f7be38deca06843f52"}'
```

**返回结果**（实测，2026-10-23）：

```json
{
  "subject": "single",
  "predicate": "includes",
  "object": "lcd_实时预览",
  "count": 1,
  "fragments": [
    {
      "text": "sensor 案例索引\n\n基于 Hi3516CV610 EV 开发板的 sensor 媒体通路示例集合：单 sensor 出图 / 编码录像 / RTSP 推流 / LCD 预览，与双目双路编码预览。\n\n| 案例 | 说明 |\n|------|------|\n| [single](https://gitcode.com/HiSpark/hi_aiot_solution_cv610_ev_board/blob/master/samples/sensor/single/README.md) | 单 sensor 出图 + H.265 编码录像 + RTSP 点播 + LCD 实时预览，支持 SC4336P / OS04D10 / CS5186 / SC450AI / SC500AI 五款 sensor。 |\n| [dual](https://gitcode.com/HiSpark/hi_aiot_solution_cv610_ev_board/blob/master/samples/sensor/dual/README.md) | 双目（双 sensor）双路 VI 全离线媒体通路，两路同时编码各自落盘，LCD 可在两路之间切换显示。 |\n\n---\n\n> 详细说明（目录结构、编译、烧写、上板运行、故障排查等）请参见仓库原文：(https://gitcode.com/HiSpark/hi_aiot_solution_cv610_ev_board/blob/master/samples/sensor/single/README.md)",
      "source_file": "/kb-files/kb/CV610ALL/hi_aiot_solution/docs/zh-CN/samples/cv610_dev_board/samples/sensor.md",
      "room": "sensor.md",
      "chunk_index": ""
    }
  ],
  "elapsed_ms": 38.4
}
```

### 2.6 异常场景

- mempalace 不可达：HTTP 200，返回 `{"count":0,"fragments":[],"error":"mempalace 服务不可达: ..."}`（与 /graph_kg 信封模式一致）
- 无匹配三元组或无对应片段：HTTP 200，`{"count":0,"fragments":[]}`（数据真值，非错误——如关系来自未入 mempalace 的文档）

## 3. 页面使用说明

1. 打开图谱页（DEV 公网 http://139.9.104.64:9789/graph）
2. 文档知识图谱中点击实体节点 → 右侧面板显示关系列表
3. 每条关系边右侧的「**片段**」按钮 → 点击即调用 `/graph_fragment`，就地展开：
   - 片段文本（对应关系的来源上下文）
   - 来源全路径 + 分块序号 + 「查看原文 ↗」链接（跳转 /doc_view）
4. 再次点击「收起」折叠

## 4. 片段定位规则（实现语义）

1. **三元组定位**：KG triples 表按 `subject + object` 查 `source_file`（走 subject 索引，毫秒级）；谓词非空时优先精确匹配谓词的三元组，无命中退化为忽略谓词
2. **文档匹配**：`source_file` 取 basename（兼容全路径/文档名两种存储形态）作为文档名；客体/主体若为文档名形态（带 .md/.pdf/.c 等扩展名）一并作为候选
3. **chunk 匹配**：在目标 palace 的 chroma 存储中按 `room`（文档名，忽略大小写）匹配 chunk；旧版 diary 块（room=diary）回退按 `topic` 键匹配
4. **片段筛选**：优先返回**同时包含主体与客体**的 chunk 文本；无则退化为含其一；仍无则返回该文档前 N 个 chunk。匹配词超 100 字符时截断比较（防超长实体名全扫）
5. **排序与去重**：按「同时包含 > 含其一 > 无命中」排序，按文本前缀+来源去重，cap limit

## 5. 已知限制

- 三元组表 `source_drawer_id` 全表为空（历史数据未回填），片段定位依赖文档名 + 文本包含匹配，**无法精确定位到抽取该关系的原始 chunk**；同名文档跨库时按 palace 隔离
- 关系来源文档从未入库 mempalace 时（如早期 upload_project 的 contains_doc 事实），返回 `count: 0`，属数据真值
- 文本匹配为字面包含（LIKE），分词变体（如「芯片结温」vs「结温」）不互相命中
- 冷查询首次较慢（NFS 冷页，数秒级），热查询毫秒级
