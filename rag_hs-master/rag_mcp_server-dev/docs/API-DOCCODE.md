# 文档↔代码映射 API（POST /doc_code）

> 版本：v1（2026-10-09，全部示例在 DEV 实测通过）
> 服务：dual-mcp `nl_query_server.py`（REST 9788，Service 9789）
> 数据：DEV `kbf1dc0cdb2ea844f7be38deca06843f52`（CV610ALL，668 文档 / 3,765 条映射边）

---

## 0. 一句话说明

输入**一个文件名**（文档名或代码文件名），自动判向返回双向映射：

- 命中文档 → 该文档引用的**代码符号清单**（文件/起止行号/引用锚点/源码段/文档块）
- 命中代码文件 → **引用它的文档清单**（按文档分组，含代码块与文档块）

```bash
curl -s http://haisi-dual-mcp.default.svc.cluster.local:9789/doc_code \
  -H 'Content-Type: application/json' -d '{
  "file": "osal_device.c",
  "project": "kbf1dc0cdb2ea844f7be38deca06843f52",
  "with_source": 3, "with_doc": 3
}'
```

---

## 1. 接入方式

| 环境 | 地址 |
|---|---|
| 集群内（推荐，页面后端代理） | `http://haisi-dual-mcp.default.svc.cluster.local:9789/doc_code` |
| pod 内直连 | `http://127.0.0.1:9788/doc_code` |
| 公网 | `http://139.9.104.64:9789/doc_code`（ELB VIP，2026-10-09 起暴露。**无认证且含 POST/DELETE /projects 管理端点，建议在 ELB 监听器加 IP 白名单**） |

- 方法 `POST`，`Content-Type: application/json`，无认证（内网部署）
- 性能：热查 ~60ms（pod 本地盘 db，1-2 条 SQL + 直读文件）
- CLI 工具：`python3 /data/doc_code_mapping_demo.py file2doc --file osal_device.c --kb <kbId> --with-source --with-doc`

---

## 2. 数据模型

```
Document 节点（name=文件名, file_path=项目内相对路径）
    │
    └── DOCUMENTS_CODE ──> Function / Method / Class / Module / File 节点
          边属性（原生 JSON，本 API 直接解析）：
          {"provenance":"doc-link","ref_name":"<文档中的写法>","line":<文档内行号>}
```

**映射语义**：索引时解析项目内 `*.md` 正文（代码块/反引号标识符/文件路径/标题，README/SKILL 优先），解析到图谱节点生成边——即"**该文档实际引用了哪些代码**"。

---

## 3. 请求参数

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `file` | string | **必填** | 文档名 / 代码文件名 / 路径片段（如 `README.md`、`osal_device.c`、`hi-aiot-build`） |
| `project` | string | `data-hunan-bms` | kbId 或已索引项目名（服务端 `resolve_project_name`：精确 → 规范化包含唯一命中） |
| `direction` | string | `auto` | `auto`=自动判向 \| `doc2code`=强制按文档查代码 \| `code2doc`=强制按代码查文档 |
| `limit` | int | 20 | 映射行数上限（1-200） |
| `with_source` | int | 0 | 附前 N 个符号的**源码段**（直读源文件，0=不附；0-20） |
| `with_doc` | int | 0 | 附前 N 个文档的**文档块**（按引用行号锚点取窗口，0=不附；0-20） |
| `exact` | bool | false | 文档名精确匹配（默认路径包含匹配） |

---

## 4. 响应结构

顶层信封：

```json
{
  "file": "osal_device.c",
  "project": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
  "direction": "code2doc",        // 实际方向：doc2code | code2doc | null(未命中)
  "matched": "code_file",         // 判向结果：document | code_file | null
  "count": 20,                    // doc2code=映射条数；code2doc=文档组数
  "results": [ ... ],             // 结构随 direction，见下
  "hint": null,                   // 空结果时给出说明（数据真值）
  "elapsed_ms": 56.0
}
```

### 4.1 doc2code（命中文档）— 平铺列表

```json
[{
  "doc": "hi_aiot_solution/.agents/skills/hi-aiot-build/README.md",
  "doc_name": "README.md",
  "symbol": "build",                    // 代码符号名
  "label": "Function",                  // Function | Method（只返回符号级）
  "file": "hi_aiot_solution/sdk/.../oem_main.py",
  "start_line": 29, "end_line": 96,
  "doc_ref": {"ref_name": "build", "line": 36},   // 文档内引用锚点
  "source": "def build():\n    ...",              // with_source 时：函数体源码
  "doc_fragment": {                                // with_doc 时：文档块
    "file": "hi_aiot_solution/.agents/skills/hi-aiot-build/README.md",
    "start_line": 31, "end_line": 56,
    "content": "## 构建\n\n使用 build 脚本..."
  },
  "file_version": "v1.0.2"              // 可选：版本化文件附当前版本
}]
```

### 4.2 code2doc（命中代码文件）— 按文档分组

```json
[{
  "doc": "hi_aiot_solution/.agents/skills/hi-aiot-cross-compile/README.md",
  "doc_name": "README.md",
  "symbols": [{
    "symbol": "osal_device_open",       // 文件名匹配会命中该文件内的符号，
    "label": "Function",                // 以及"文档提到该文件"的边（label=Module/Class/File）
    "file": "hi_aiot_solution/sdk/Hi3516CV610/soc/drivers/osal/src/linux/osal_device.c",
    "start_line": 3, "end_line": 9,
    "doc_ref": {"ref_name": "osal_device_open", "line": 8},
    "source": "int osal_device_open(...)"          // with_source 时（全局前 N 个符号）
  }],
  "doc_fragment": {...}                 // with_doc 时（前 N 个文档组）
}]
```

---

## 5. 判向与匹配规则

**direction=auto**：先试文档侧，命中即 `doc2code`；否则代码侧，命中即 `code2doc`。

| 侧 | 匹配层级（逐级尝试，首层命中即停） | label 过滤 |
|---|---|---|
| 文档 | ① `name = file`（文件名精确）→ ② 路径段边界 `file_path = file` 或 `LIKE '%/file'` → ③ `LIKE '%file%'` | 只取 Function/Method（符号级映射） |
| 代码 | ① 路径段边界 `LIKE '%/file'` → ② `LIKE '%file%'` | **不过滤**——"文档提到文件"的边目标常是 Module/Class/File 节点，同样算引用 |

- `exact=true`：文档侧只走第 ① 级（同名 README.md/SKILL.md 很多时配合使用）
- `direction=doc2code/code2doc`：强制对应侧，匹配层级同上

**代码块/文档块规则**：

| 块 | 规则 |
|---|---|
| `source` | 直读 `<项目根>/<file>` 的 `start_line..end_line`；超 2600 字符截断（尾附 `... [truncated]`）；全局取前 `with_source` 个符号 |
| `doc_fragment` | 优先 `doc_ref.line` 锚点窗口（**前 5 行后 20 行**）；无锚点找 `ref_name` 在正文中的首现行；再退文件头部 40 行；cap 2000 字符；按去重文档取前 `with_doc` 个 |
| `file_version` | 登记过版本管理的文件附带（`_lookup_files_version` 精确+双向后缀匹配） |

---

## 6. 错误与空结果

| HTTP | 场景 | body |
|---|---|---|
| 200 | 正常（含空结果） | `count=0` + `direction=null` + `hint:"未找到映射：…（数据真值）…"` |
| 400 | `direction` 非 `auto/doc2code/code2doc` | `{"detail":"direction 须为 auto \| doc2code \| code2doc"}` |
| 404 | project 无图谱（kbId 未索引） | `{"detail":"项目 'xxx' 图谱不存在: …"}` |

**空结果是数据真值**：映射边=文档正文实际引用的符号。未被任何文档提及的代码查出来为空不是故障。

---

## 7. 实测示例（DEV 真实响应节选）

**示例 1：代码文件名 → 文档清单（用户典型场景）**

```json
{"file": "osal_device.c", "project": "kbf1dc0cdb2ea844f7be38deca06843f52",
 "with_source": 3, "with_doc": 3}
```
→ `direction=code2doc, count=20`：20 个 README/SKILL 引用了 osal 相关代码，首个文档组带源码段与文档块（锚点窗口），热查 56-62ms。

**示例 2：文档名精确 → 代码符号清单**

```json
{"file": "SKILL.md", "direction": "doc2code", "exact": true, "limit": 6}
```
→ `matched=document`，符号：`ok`(Function) / `build`(Function) / `run`(Function) / `capture`(Function) / `issues`(Method)…

**示例 3：路径片段（同文名文档多时用）**

```json
{"file": "hi-aiot-build"}
```
→ 命中 `…/skills/hi-aiot-build/README.md` 与 `…/skills/hi-aiot-build/SKILL.md` 两个文档的映射。

---

## 8. Python 测试

自动化测试脚本：`test_doc_code_api.py`（assertion 型，exit 0=全过）：

```bash
# 默认走公网 http://139.9.104.64:9789
python3 test_doc_code_api.py

# 集群内 / pod 内（9788 直连）
DOCCODE_BASE=http://haisi-dual-mcp.default.svc.cluster.local:9789 python3 test_doc_code_api.py
DOCCODE_BASE=http://127.0.0.1:9788 python3 test_doc_code_api.py
```

覆盖 11 项：health / auto 双向判向（code2doc+doc2code）/ with_source 源码段 / with_doc 文档块 / exact / 路径片段 / 显式方向 / 空结果 hint / 404 / 400 / 回归 /query+/projects。**每个用例打印请求与响应的完整 JSON**（默认截断 12000 字符，`DOCCODE_JSON_MAX=0` 显示全量），便于人工核验输入/输出。

最小客户端片段：

```python
import json, urllib.request

def doc_code(file, kb, **kw):
    body = {"file": file, "project": kb, **kw}
    r = urllib.request.Request(
        "http://haisi-dual-mcp.default.svc.cluster.local:9789/doc_code",
        data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=60).read())

print(doc_code("osal_device.c", "kbf1dc0cdb2ea844f7be38deca06843f52",
               with_source=3, with_doc=3)["count"])
```

---

## 9. 注意事项

1. **映射生成时机**：项目**首次索引/全量重建**时自动生成（README/SKILL 优先，上限 1000 文档/项目）。日常增量更新文件**不重跑**映射——改文档后需全量 reindex 才更新边。
2. **覆盖范围**=被项目内 `*.md` 正文实际引用的符号。`client_auth` 这类无文档代码查出来为空是数据真值。
3. 同名文档（README.md/SKILL.md）大量存在：传参优先用完整 `file_path` 或路径片段，或 `exact=true`。
4. `source`/`doc_fragment` 直读项目树文件（容器内 `/projects/<kbId>/`）；文件不在树内时对应字段静默省略。
5. 旧 MCP 路径（`query_graph` Cypher + `get_code_snippet`，文本表解析）仍可用，语法限制与示例见附录。

---

## 10. 知识图谱浏览 API + 交互页面（2026-10-09 新增）

配套的图谱浏览器（参考 AIForce 图谱页风格，ECharts 力导向 + 深色主题）：

| 资源 | 说明 |
|---|---|
| `GET /graph` | 交互页面（单文件 HTML，公网 `http://139.9.104.64:9789/graph`）。搜索框输入实体关键字 → 渲染邻接子图（按 label 着色/图例）；**点击节点** → 右侧面板展示属性（label/文件/行号/度数/邻边类型/源码段）；**双击节点** → 增量展开其邻居 |
| `POST /graph_search` | `{q, project, limit=80, candidates=6}` → `{center, candidates[], nodes[], edges[], hint, elapsed_ms}`。按关键字定位中心实体（度数最大者），返回任意边类型的邻接子图（CALLS/DOCUMENTS_CODE/DEFINES/…，边含 `ref_name`/`line` 锚点），nodes 用 `id` 关联、带 `deg_local` |
| `POST /graph_node` | `{name, id?, project, file_path?, with_source=true}` → `{node: {label, file_path, start_line, end_line, in/out_degree, in_types, out_types, props(复杂度/签名等), source, file_version}}` |

示例：

```bash
curl -s http://139.9.104.64:9789/graph_search -H 'Content-Type: application/json' \
  -d '{"q":"osal_dump_stack","project":"kbf1dc0cdb2ea844f7be38deca06843f52"}'
```

DEV 实测：`osal_dump_stack` → 31 节点/30 边（CALLS 主导，288ms 首查）；`SKILL.md` → 23 节点/40 边（50ms 热查）。

---

## 附录：MCP query_graph 旧路径（v2，仍可用）

MCP 9800（`hisi-code-mcp`）可用 Cypher 实现同等查询，适合走 MCP 协议的 Agent：

| 用途 | Cypher |
|---|---|
| 文档 → 代码 | `MATCH (d:Document)-[:DOCUMENTS_CODE]->(f) WHERE d.name='README.md' AND (f.label='Function' OR f.label='Method') RETURN d.file_path, f.name, f.label, f.file_path, f.start_line, f.end_line LIMIT 20` |
| 代码 → 文档 | 同一条正向边，WHERE 写在代码侧：`WHERE f.file_path CONTAINS 'oem_main' AND f.label='Function' RETURN d.name, d.file_path, f.name LIMIT 20` |
| 引用锚点 | `MATCH (d:Document)-[e:DOCUMENTS_CODE]->(f) … RETURN d.file_path, f.name, e`（`e` 列返回边属性 JSON） |

语法限制（实测）：不支持 `IN` 列表（用 OR）；不支持从代码侧遍历 `DOCUMENTED_BY`（反向需求用正向边+WHERE f 侧）；`get_code_snippet` 入参是 `qualified_name`；`RETURN e.line` 输出 `-`（须 `RETURN e` 整条边）。完整文档历史版本见 git 记录。

---

## 10. 映射文件清单（POST /doc_code/files，2026-10-22 新增）

输入**知识库 id**，按类型返回**已建立映射关系的文件列表**（浏览映射全貌，无需先猜文件名）。

**请求**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `project` | string | — | kbId 或已索引项目名 |
| `type` | string | code | `code`=被文档引用过的代码文件列表；`doc`=引用过代码符号的文档文件列表（非法值 → 400） |

```bash
# 已建立映射的代码文件
curl -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kbf1dc0cdb2ea844f7be38deca06843f52","type":"code"}'
# 已建立映射的文档文件
curl -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kbf1dc0cdb2ea844f7be38deca06843f52","type":"doc"}'
```

**响应（实测，TEST test3 库 kb6767d10392224abcabf226f5897cd25f）**

```json
{
  "project": "projects-kb6767d10392224abcabf226f5897cd25f",
  "type": "code",
  "count": 1,
  "files": [
    {"file": "t3/sample_1.ts", "name": "sample_1.ts", "refs": 1}
  ],
  "elapsed_ms": 8.4
}
```

| 字段 | 说明 |
|---|---|
| `files[].file` | 项目内相对路径（code 侧为符号所属文件；doc 侧为文档路径） |
| `files[].name` | 文件 basename（doc 侧=文档名） |
| `files[].refs` | 该文件的 DOCUMENTS_CODE 映射边数 |
| `count` | 去重后的文件数；`0`=该库尚无映射（数据真值，非错误） |

- 排序：按 `file` 路径升序；code 侧合并 File 级与符号级 target 并按文件去重（同文件多符号只出一条，refs 累加）
- kbId 不存在 → 404；`type` 非法 → 400（HTTP 语义同 /doc_code）

TEST 实测：CV610 库（kb49ab80cec16f42c6b0da8229384c098d）type=code 含 `sample_aidetect.c`（refs=5）；type=doc 返回 3761 条映射边对应的文档清单。
