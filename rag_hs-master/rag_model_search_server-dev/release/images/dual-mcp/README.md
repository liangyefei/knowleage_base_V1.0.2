# Dual-MCP 代码知识图谱服务

一站式代码知识图谱容器：[codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp) (CBM) + codegraph + 轻量 MCP 桥，对外提供 **REST API** 与 **Streamable HTTP MCP** 双协议。

> **源码已独立**：镜像构建源码（Dockerfile / entrypoint / CBM C 源码 / codegraph TS 源码 / scripts / bin）已迁移至独立仓库 **<https://gitcode.com/hi_kb_base/rag_mcp_server>**。本目录仅保留文档与 MCP 桥脚本，构建镜像、获取源码请去该仓库。

---

## 本目录内容

| 文件 | 用途 |
|------|------|
| [API.md](API.md) | REST API 完整规范（8 个 endpoint）+ MCP HTTP Bridges（9800/9801）协议与工具清单 |
| [API-K8S.md](API-K8S.md) | K8s 集群内调用文档（Service `haisi-dual-mcp`，含 `format=graph` 可视化对接） |
| [docker-shell.md](docker-shell.md) | 容器构建与操作手册（多阶段构建、端口、卷挂载、helper 命令） |
| [mcp_stdio_bridge.py](mcp_stdio_bridge.py) | 轻量 stdio ↔ streamable-http MCP 桥（替代 supergateway） |
| README.md | 本文件（导航总览） |

---

## 架构概览

容器启动时三件事：

1. **发现项目** — 扫描 `/projects/*` 子目录（+ 内置 `/opt/codebase-memory-mcp`、`/opt/codegraph`、`$EXTRA_PROJECTS`）
2. **建索引** — 每个项目用 CBM（SQLite 知识图谱）+ codegraph（`.codegraph/`）双引擎幂等索引
3. **启服务** — CBM NL HTTP 查询服务（9788，前台主进程）+ MCP HTTP 桥（9800 CBM / 9801 codegraph）

核心概念：所有"导入 / 索引"类操作走 **Job 异步模式**——POST 立即返 `job_id`，客户端轮询 `GET /jobs/{id}` 直到 `done` / `failed`。原因：大项目索引耗时分钟级。

---

## 端口与协议

| 宿主端口 → 容器 | 协议 | 路径 | 用途 |
|---|---|---|---|
| 9789 → 9788 | HTTP | `/health` `/projects` `/query` `/jobs` … | REST API（自然语言查询 / 项目管理 / Job 轮询） |
| 9800 → 9800 | HTTP | `/mcp` | CBM MCP（15 工具：search_graph / trace_path / get_code_snippet …） |
| 9801 → 9801 | HTTP | `/mcp` | codegraph MCP（codegraph_explore） |

> 仅用 REST 可只映射 9789；需要 MCP 远程客户端时再映射 9800/9801。

REST 字段细节 → [API.md](API.md)；K8s Service 地址与 `format=graph` 可视化对接 → [API-K8S.md](API-K8S.md)；构建、启动、helper 命令 → [docker-shell.md](docker-shell.md)。

---

## MCP 桥（mcp_stdio_bridge.py）

替代 supergateway 的轻量实现，根因解决其单连接崩溃问题：

- 一个 HTTP 请求 = 子进程一次完整往返（每会话一子进程 + 会话级锁）
- 不做跨请求响应路由 → 慢调用 / 乱序响应无从触发 "No connection established" 崩溃
- 子进程崩溃 → 该次调用返 JSON-RPC error，下次自动重拉
- 空闲会话超时自动回收；桥进程本身永不退出

协议面：`POST /mcp`（无会话头→新建，带头→复用）、`DELETE /mcp`（结束会话）、`GET /mcp` 返回 405。响应统一 `application/json`（非 SSE）。部署 / env 配置见脚本头部 docstring 与 [API-K8S.md §3](API-K8S.md#3-mcp-接口streamable-http)。

---

## 环境变量速查

| 变量 | 默认 | 作用 |
|---|---|---|
| `CBM_CACHE_DIR` | `/var/lib/codebase-memory-mcp` | CBM SQLite 索引目录 |
| `NL_PORT` | `9788` | REST 服务端口 |
| `CBM_SSE_PORT` | `9800` | CBM MCP 桥端口；空字符串跳过 |
| `CG_SSE_BRIDGES` | `/projects/Hi3516CV610` | codegraph 桥接项目路径（空格分隔） |
| `CG_SSE_PORT_START` | `9801` | codegraph 桥端口起始，每项目递增 |
| `CBM_INDEX_TIMEOUT` | `1800` | CBM index_repository 超时秒 |
| `CODEGRAPH_INIT_TIMEOUT` | `1800` | codegraph init 超时秒 |
| `CODEGRAPH_NO_WATCHDOG` | `1` | 禁用 codegraph 60s 看门狗（大项目索引防误杀） |
| `CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS` | `2147483647` | codegraph daemon 空闲保活（Node int32 上限） |
| `EXTRA_PROJECTS` | (空) | 额外索引路径（空格分隔） |

完整字段说明见 [API.md 环境变量](API.md#环境变量)。

---

## 与 RAG 服务对接

RAG 侧通过 `kbs_config.dual_mcp.base_url` 读取服务地址，默认 `http://localhost:9789`（见 `params/kb_config.yaml`）。生产环境同机部署保持 localhost；分属不同节点 / k8s Service 改为对应内网地址。

对接细节：[server/comm/dual_mcp_client.py](../../../server/comm/dual_mcp_client.py) 与 [server/comm/kafka-structure.md](../../../server/comm/kafka-structure.md)。

项目命名约定：**kbId == dual-mcp 项目名**。RAG 侧上传项目 zip 后自动落 `/projects/<kbId>/code/` 并触发索引；检索时 kbId 直接作为 `project` 参数传入。

---

## 历史背景

本镜像在 Hi3516CV610 SDK（1.7GB，1300+ C/H 文件）实战中踩过并修复的问题：codegraph 看门狗误杀大项目索引、daemon 立即退出（idle timeout 超 int32 上限）、CBM doc-links 漏抽 `(td_void)memset_s(...)`、缺 chip.yaml 导致 0 Document 节点、脚本硬编码路径。改动清单见 [docker-shell.md §8](docker-shell.md#8-本次会话改动清单)。

CBM 源码改动（pass_doc_links.c：preceding-char 白名单补 `)`、`DL_CANDIDATE_CAP` 200→4000）已包含在独立仓库的 vendored 源码中，无需再单独打补丁。
