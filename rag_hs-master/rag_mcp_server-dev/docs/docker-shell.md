# Dual-MCP 容器操作手册

> 一站式代码知识图谱容器：[codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp) (CBM) + codegraph + supergateway，提供 REST API 与 streamable-http MCP 双协议。
>
> 镜像是**自包含构建**——CBM C 源码与 codegraph TypeScript 源码已 vendored 在本目录内，构建时无需 git clone 外部仓库、无需预编译二进制。

源码目录：<https://gitcode.com/hi_kb_base/rag_mcp_server>

---

## 1. 前置条件

| 项 | 要求 |
|----|------|
| Docker | 20.10+ |
| 构建内存 | ≥ 4 GB（CBM 编译 tree-sitter 语法树较吃内存） |
| 宿主机端口 | 9789 / 9800 / 9801 可用 |
| 代码项目 | 宿主机一个目录，其下每个子目录视为一个待索引项目 |

---

## 2. 获取代码

```bash
# SSH
git clone git@gitcode.com:hi_kb_base/rag_mcp_server.git
cd rag_mcp_server

# 或 HTTPS
git clone https://gitcode.com/hi_kb_base/rag_mcp_server.git
cd rag_mcp_server
```

---

## 3. 构建镜像

```bash
# 推荐：禁用 BuildKit provenance，输出标准 Docker v2 manifest
# 否则推送到华为云 SWR 会报：Invalid image, fail to parse 'manifest.json'
docker buildx build \
  --provenance=false \
  --output type=docker \
  -t haisi-dual-mcp:v1.0 \
  .

# 兼容老版 Docker（无 buildx 插件时）：
# DOCKER_BUILDKIT=0 docker build -t haisi-dual-mcp:v1.0 .
```

**三阶段多阶段构建**（总耗时约 12–17 分钟，4 核机器）：

| 阶段 | 基础镜像 | 产物 | 耗时 |
|------|----------|------|------|
| 1. cbm-builder | debian:bookworm-slim | CBM 二进制（~280 MB，glibc 链接） | 10–15 min |
| 2. codegraph-builder | node:22-bookworm-slim | codegraph dist/ | 1–2 min |
| 3. runtime | node:22-bookworm-slim | 最终镜像（Python + Node + supergateway） | ~30 s |

构建产物：镜像名 `haisi-dual-mcp`，标签 `v1.0`。

---

## 4. 启动容器

```bash
docker run -d --name haisi-dual-mcp \
  -p 9789:9788 \
  -p 9800:9800 \
  -p 9801:9801 \
  -v /path/to/projects:/projects:rw \
  -v cbm-cache:/var/lib/codebase-memory-mcp \
  --restart unless-stopped \
  haisi-dual-mcp:v1.0
```

### 4.1 端口映射

| 宿主机 → 容器 | 协议 | 用途 |
|---------------|------|------|
| `9789` → `9788` | HTTP | CBM NL HTTP 服务（REST API + `/docs` Swagger） |
| `9800` → `9800` | HTTP | CBM MCP streamable-http 桥（path `/mcp`） |
| `9801` → `9801` | HTTP | codegraph MCP streamable-http 桥（path `/mcp`） |

> 仅用 REST API 时可只映射 `9789`；需要 MCP 远程客户端时再映射 9800/9801。

### 4.2 卷挂载

| 挂载 | 类型 | 作用 |
|------|------|------|
| `/path/to/projects:/projects:rw` | bind mount | 待索引代码根目录；其下每个子目录会被自动发现并索引为一个项目 |
| `cbm-cache:/var/lib/codebase-memory-mcp` | named volume | CBM SQLite 索引缓存，`docker rm` 不丢；换机器需重新索引 |

> `/path/to/projects` 替换为宿主机实际路径，例如 `/home/user/code-projects` 或 `D:/code/projects`（Windows）。

### 4.3 启动流程（entrypoint 自动执行）

1. **Phase 0**：发现项目（内置 `/opt/codebase-memory-mcp`、`/opt/codegraph` + 挂载的 `/projects/*` + `$EXTRA_PROJECTS`）
2. **Phase 1**：幂等索引——CBM `index_repository` + codegraph `init`，已索引则跳过
3. **Phase 2**：启 CBM daemon + 每项目一个 codegraph daemon + supergateway HTTP 桥
4. **Phase 3**：前台执行 CBM NL HTTP 服务（成为主进程）

---

## 5. 验证

```bash
# 健康检查
curl http://localhost:9789/health

# 列出已索引项目
curl http://localhost:9789/projects
# 或容器内 helper
docker exec dual-mcp list-projects

# NL 查询（项目名格式：<父目录名>-<项目目录名>）
curl -X POST http://localhost:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q":"memset_s","project":"projects-<your-dir>"}'

# Swagger 交互式文档
open http://localhost:9789/docs
```

---

## 6. 常用操作

```bash
# 查主容器日志（NL server + entrypoint 流程）
docker logs -f dual-mcp

# 查索引时的一次性日志
docker exec dual-mcp ls /var/log/

# codegraph daemon 日志（每项目一个）
docker exec dual-mcp cat /var/log/cg-<project>.log

# 添加新项目（容器内 helper，幂等）
docker exec dual-mcp add-project /projects/<new-project>

# 删除项目索引 + 停 daemon
docker exec dual-mcp remove-project <name_or_path>

# 一键验证/Demo 脚本在仓库 scripts/（不在镜像内），在仓库内运行：
cd tools && bash run_file_code.sh
```

---

## 7. 可选环境变量覆盖

```bash
docker run -d --name dual-mcp \
  -p 9789:9788 -p 9800:9800 -p 9801:9801 \
  -v /path/to/projects:/projects:rw \
  -v cbm-cache:/var/lib/codebase-memory-mcp \
  -e EXTRA_PROJECTS="/extra/path1 /extra/path2" \
  -e CBM_PROJECT="projects-<your-dir>" \
  -e CBM_DOC_FILTER="ISP" \
  -e CBM_FUNC_FILTER="memset_s" \
  --restart unless-stopped \
  haisi-dual-mcp:v1
```

| 变量 | 默认 | 作用 |
|------|------|------|
| `EXTRA_PROJECTS` | (空) | 额外索引路径（空格分隔） |
| `CBM_PROJECT` | `projects-Hi3516CV610` | `run_*.sh` 默认项目名 |
| `CBM_DOC_FILTER` | `ISP` | `run_file_code.sh` 默认文档过滤 |
| `CBM_FUNC_FILTER` | `memset_s` | `run_file_code.sh` 默认函数过滤 |

其余内置变量（`CODEGRAPH_NO_WATCHDOG`、`CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS` 等）已在 Dockerfile/entrypoint 中调优，一般无需改动，详见 [README.md](README.md) 环境变量表。

---

## 8. 本次会话改动清单

镜像相比上游 codebase-memory-mcp 的定制改动：

### 8.1 CBM C 源码
- `pass_doc_links.c`：preceding-char 白名单扩展（补充 `)` 等），修复 `(td_void)memset_s(...)` 类调用漏抽
- `pass_doc_links.c`：`DL_CANDIDATE_CAP` 200 → 4000，避免大文档候选被截断（memset_s 等被正确抽取）

### 8.2 Python 脚本（`scripts/`）
- 移除 `/data/hunan-*` 等硬编码路径，改为环境变量驱动：`CBM_PROJECT` / `CBM_DOC_FILTER` / `CBM_FUNC_FILTER`
- 新增 `derive_project_root()`：从项目名自动推导项目根，脚本可跨项目复用

### 8.3 NL 查询服务（`src/nl_query_server.py`）
- 新增 5 个 REST endpoint：`POST /projects`（异步建索引）、`GET /jobs`、`GET /jobs/{id}`、`DELETE /projects/{name}`、`POST /projects/{name}/reindex`
- 引入 `ThreadPoolExecutor` 异步 Job 系统：建索引请求立即返回 `job_id`，后台轮询状态

### 8.4 容器入口（`src/entrypoint.sh`）
- CBM daemon 启动 + 索引幂等检查
- supergateway streamable-http MCP 桥：9800（CBM）、9801（codegraph），支持多客户端并发

### 8.5 Helper 脚本（`src/bin/`）
- `add-project` / `list-projects` / `query-project` / `remove-project` 四个命令，安装到 `/usr/local/bin/`

### 8.6 文档
- `API.md`：8 个 REST endpoint 完整规范 + MCP HTTP Bridges 章节
- `Dockerfile`：三阶段多阶段构建（CBM 编译 + codegraph 构建 + runtime），自包含无外部依赖

---

## 9. 与 RAG 服务对接

RAG 服务侧通过 `kbs_config.dual_mcp.base_url` 读取容器地址，默认 `http://localhost:9789`（见 `params/kb_config.yaml`）。对接细节见 RAG 主服务仓库的 `server/comm/kafka-structure.md` 与 `server/comm/dual_mcp_client.py`（在 `haisi_rag_models_server` 仓库，不在本仓库）。

生产环境若 dual-mcp 与 RAG 服务同机部署，保持 `localhost:9789`；若分属不同节点/k8s Service，改为对应内网地址。
