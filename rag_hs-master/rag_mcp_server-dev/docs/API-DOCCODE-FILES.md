# 文档↔代码映射文件清单 API（POST /doc_code/files）

> 版本：v1（2026-10-22 上线，示例均在 DEV/TEST 实测通过）
> 服务：dual-mcp `nl_query_server.py`（REST 9788，Service 9789）
> 用途：按知识库返回**已建立映射关系**的文件清单（供 codemap 页浏览映射全貌，无需先猜文件名）

---

## 1. 一句话说明

输入**知识库 id**（kbId），按类型返回存在 `DOCUMENTS_CODE` 映射边的文件列表：

- `type=code` → 被文档引用过的**代码文件列表**（File 级与符号级映射合并、按文件去重）
- `type=doc` → 引用过代码符号的**文档文件列表**

---

## 2. 接入方式

| 环境 | 地址 |
|---|---|
| DEV 公网 | `http://139.9.104.64:9789/doc_code/files` |
| TEST 集群内（页面后端代理用） | `http://haisi-dual-mcp.default.svc.cluster.local:9789/doc_code/files` |
| pod 内直连 | `http://127.0.0.1:9788/doc_code/files` |

- 方法 `POST`，`Content-Type: application/json`，无认证（内网部署）
- 性能：单条 GROUP BY SQL，CV610 级库（23 万边）热查 <30ms

---

## 3. 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `project` | string | ✓ | kbId 或已索引项目名（裸 kbId 与 `projects-kb…` 前缀名均可，自动解析） |
| `type` | string | — | `code`=代码文件列表（默认）；`doc`=文档文件列表；其他值 → HTTP 400 |

---

## 4. 响应结构

```json
{
  "project": "projects-kb49ab80cec16f42c6b0da8229384c098d",
  "type": "code",
  "count": 799,
  "files": [
    {"file": "AIComponent/.../sample_aidetect.c", "name": "sample_aidetect.c", "refs": 5,
     "url": "http://139.9.104.64:9789/doc_view?project=projects-kb49ab80cec16f42c6b0da8229384c098d&file=AIComponent/.../sample_aidetect.c"}
  ],
  "elapsed_ms": 18.6
}
```

| 字段 | 说明 |
|---|---|
| `files[].file` | 项目内相对路径（code 侧=符号所属文件；doc 侧=文档路径） |
| `files[].name` | 文件 basename（doc 侧=文档名） |
| `files[].refs` | 该文件的 DOCUMENTS_CODE 映射边数（code 侧同文件多符号时累加） |
| `files[].url` | 「查看原文」跳转链接（GET /doc_view）：code 项=`/doc_view?project=&file=`；doc 项=`/doc_view?doc=&kb=`。基址优先级：环境变量 `DOC_VIEW_BASE_URL` > 请求 Host 推导（含 X-Forwarded 代理头，DEV 公网即返回公网地址）> 相对路径 |
| `count` | 去重后的文件数；**0=该库尚无映射（数据真值，非错误）** |

- 排序：按 `file` 路径升序
- code 侧已过滤 `<python-builtins>` 等伪路径（文档引用内建符号产生的映射不列为文件）
- 错误语义：kbId 不存在 → HTTP 404；`type` 非法 → HTTP 400

---

## 5. curl 调用示例（实测）

### 5.1 DEV 公网 —— CV610ALL 库（kbf1dc0…）

```bash
# 已建立映射的代码文件列表
curl -s -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kbf1dc0cdb2ea844f7be38deca06843f52","type":"code"}'
```

实测响应（截选，count=765）：

```json
{
  "project": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
  "type": "code",
  "count": 765,
  "files": [
    {"file": "cv610_lampbot_board/references/ai_toy_car/source/toy_service/device/odas/integration/src/module/toy_planar_ssl.c", "name": "toy_planar_ssl.c", "refs": 1},
    {"file": "cv610_lampbot_board/references/ai_toy_car/source/toy_service/device/odas/odas-master/demo/odaslive/configs.h", "name": "configs.h", "refs": 28},
    {"file": "cv610_lampbot_board/references/ai_toy_car/source/toy_service/device/odas/odas-master/demo/odaslive/main.c", "name": "main.c", "refs": 3}
  ],
  "elapsed_ms": 16.9
}
```

```bash
# 已建立映射的文档文件列表
curl -s -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kbf1dc0cdb2ea844f7be38deca06843f52","type":"doc"}'
```

实测响应（截选，count=460）：

```json
{
  "project": "projects-kbf1dc0cdb2ea844f7be38deca06843f52",
  "type": "doc",
  "count": 460,
  "files": [
    {"file": "hi_aiot_solution/.agents/hi-super-dev/skills/board-access/SKILL.md", "name": "SKILL.md", "refs": 1},
    {"file": "hi_aiot_solution/.agents/hi-super-dev/skills/board-access/references/dropbear-upgrade.md", "name": "dropbear-upgrade.md", "refs": 4},
    {"file": "hi_aiot_solution/.agents/hi-super-dev/skills/board-access/references/serial-exec.md", "name": "serial-exec.md", "refs": 3}
  ],
  "elapsed_ms": 20.2
}
```

### 5.2 TEST 集群内 —— CV610 库（kb49ab80…）

```bash
# 集群内调用（TEST 无公网 REST，经页面后端或 pod 内直连）
curl -s -X POST http://haisi-dual-mcp.default.svc.cluster.local:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kb49ab80cec16f42c6b0da8229384c098d","type":"code"}'
```

实测响应（截选，count=799，含 sample_aidetect.c）：

```json
{
  "project": "projects-kb49ab80cec16f42c6b0da8229384c098d",
  "type": "code",
  "count": 799,
  "files": [
    {"file": "AIComponent/Hi3516CV610_AIComponent_SDK_V3.0.1.0_B003/smp/a7_linux/source/mpp/sample/svp/ai_component/aidetect/sample_aidetect.c", "name": "sample_aidetect.c", "refs": 5}
  ],
  "elapsed_ms": 18.6
}
```

### 5.3 TEST —— 小库 test3（kb6767d10…，数据真值示例）

```bash
curl -s -X POST http://127.0.0.1:9788/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kb6767d10392224abcabf226f5897cd25f","type":"code"}'
```

实测响应（该库仅 2 条映射边，如实列出）：

```json
{
  "project": "projects-kb6767d10392224abcabf226f5897cd25f",
  "type": "code",
  "count": 2,
  "files": [
    {"file": "README.md", "name": "README.md", "refs": 1},
    {"file": "t3/sample_1.ts", "name": "sample_1.ts", "refs": 1}
  ],
  "elapsed_ms": 8.4
}
```

```bash
curl -s -X POST http://127.0.0.1:9788/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kb6767d10392224abcabf226f5897cd25f","type":"doc"}'
```

实测响应：

```json
{
  "project": "projects-kb6767d10392224abcabf226f5897cd25f",
  "type": "doc",
  "count": 1,
  "files": [
    {"file": "README.md", "name": "README.md", "refs": 2}
  ],
  "elapsed_ms": 6.9
}
```

---

## 6. 错误示例

```bash
# 非法 type → 400 {"detail":"type 须为 code | doc"}
curl -s -i -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"x","type":"bad"}'

# 不存在的 kbId → 404 {"detail":"项目 'kb-nonexistent0000000000000000' 图谱不存在: ..."}
curl -s -i -X POST http://139.9.104.64:9789/doc_code/files \
  -H 'Content-Type: application/json' \
  -d '{"project":"kb-nonexistent0000000000000000","type":"code"}'
```

---

## 7. 注意事项

- 映射边（DOCUMENTS_CODE）在**建图/重索引时**由文档正文实际引用的代码符号生成——清单只反映索引时点的数据；文档改版后需重建索引（`POST /projects/{name}/reindex`）
- `count=0` 不是错误：该库文档正文未引用任何代码符号名时即为空（数据真值）
- code 侧 `refs` 按文件累加：同一文件内多个符号被引用只出一条
- 与单文件查询接口的关系：本接口列出**有哪些**文件有映射；`POST /doc_code` 查**某个**文件的映射明细
- `files[].url`（2026-10-22 新增）可直接作为列表项跳转链接；平台代理场景若 Host 不可达（如集群内 svc 名），在 dual-mcp 部署上设 `DOC_VIEW_BASE_URL` 或用平台自己的 doc_view 包装地址

---

## 8. 查看原文链接（GET /doc_view）

映射清单中的每个文件，都可经「查看原文」链接跳转到原文件（浏览器内预览/下载）。codemap 页面用该链接作为列表项的跳转目标。

### 8.1 接口

`GET /doc_view?project=<kbId>&file=<项目内相对路径>&line=<行号>`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `project` | string | 与 file 配套 | kbId 或项目名（代码图谱节点入口） |
| `file` | string | 与 project 配套 | 项目内相对路径（即 `/doc_code/files` 返回的 `files[].file`） |
| `doc` | string | 与 kb 配套 | 文档文件名（KG 文档实体入口，按文件名在知识库目录树定位） |
| `kb` | string | 与 doc 配套 | kbId（定位知识库目录树；带显示名映射时两个候选树都会找） |
| `line` | int | — | 行号定位：文本类页面渲染行号并高亮该行（URL 加 `#L<行号>` 锚点同样生效） |

两种入口二选一：`project+file`（代码图谱）或 `doc+kb`（文档图谱）。

### 8.2 行为（按文件类型）

| 文件类型 | 行为 |
|---|---|
| pdf / 图片（png/jpg/jpeg/gif/svg）/ html | 浏览器原生预览（`FileResponse` 内联 MIME） |
| office 文档 / 压缩包（.doc/.docx/.xls/.xlsx/.ppt/.pptx/.zip/.bin/.img/.7z/.tar/.gz） | **触发下载**（`application/octet-stream`，Content-Disposition 带原始文件名） |
| 文本类（.md/.c/.h/.py/.txt…） | 渲染行号页（前 2MB），`line`/`#L` 锚点定位高亮 |

### 8.3 curl 示例（DEV 实测）

```bash
# 代码图谱节点原文（files[].file 直达，带行号高亮）
curl -s "http://139.9.104.64:9789/doc_view?project=kbf1dc0cdb2ea844f7be38deca06843f52&file=cv610_lampbot_board/references/ai_toy_car/source/toy_service/device/odas/odas-master/demo/odaslive/configs.h&line=10"
# → 200 text/html，行号页，第 10 行高亮（len=7893）

# 文档实体原文（doc+kb 按文件名定位）
curl -s "http://139.9.104.64:9789/doc_view?doc=SKILL.md&kb=kbf1dc0cdb2ea844f7be38deca06843f52"
# → 200 text/html，SKILL.md 行号页（len=5959）

# 下载型文件（office/压缩包 → 二进制下载）
curl -s -OJ "http://139.9.104.64:9789/doc_view?project=kbf1dc0cdb2ea844f7be38deca06843f52&file=docs/xxx.docx"
# → Content-Disposition: attachment; filename="xxx.docx"

# pdf 浏览器预览
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
  "http://139.9.104.64:9789/doc_view?doc=BSP%20FAQ.pdf&kb=kbf1dc0cdb2ea844f7be38deca06843f52"
# → 200 application/pdf
```

### 8.4 错误

| 场景 | 返回 |
|---|---|
| 参数缺失（无 project+file 也无 doc+kb） | 400 提示页 |
| project 不存在 / 文件不存在或越界 | 404 提示页（越界防御：realpath 校验必须在项目 root 内） |
| doc 在知识库目录树未找到 | 404 提示页 |
| 文本读取失败 | 500 提示页 |

### 8.5 页面跳转用法（codemap 集成）

映射清单返回 `{file, name, refs}` 后，前端按行拼接跳转链接：

```js
// code 列表项：project=kbId + file=files[].file
`/doc_view?project=${kbId}&file=${encodeURIComponent(f.file)}`
// doc 列表项：doc=files[].name + kb=kbId
`/doc_view?doc=${encodeURIComponent(f.name)}&kb=${kbId}`
```

配合单文件查询 `/doc_code` 的 `doc_ref.line`（文档内引用行号）可进一步加 `&line=` 直达引用处。
