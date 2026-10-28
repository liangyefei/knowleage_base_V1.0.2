# Dual-MCP Docker Image

将 [`codebase-memory-mcp`](https://github.com/DeusData/codebase-memory-mcp) (CBM) 与 [`codegraph`](https://github.com/colbymchenry/codegraph) 打包进同一 Docker 镜像。容器启动时：

1. **自动发现项目**：扫描 `/projects/*` 子目录，每个子目录作为一个项目（也支持内置 `/opt/codebase-memory-mcp`、`/opt/codegraph`，或通过 `$EXTRA_PROJECTS` 显式追加）
2. **建立索引**：每个项目用 CBM（SQLite 知识图谱）和 codegraph（`.codegraph/`）双引擎建立索引，幂等
3. **启动服务**：CBM daemon（共享索引协调）、CBM NL HTTP 查询服务（端口 9788，前台）、每项目一个 codegraph MCP daemon（预热）

## 仓库布局

```
rag_mcp_server/（dual-mcp）
├── Dockerfile                  ← 三阶段多阶段构建（自包含，仓库根即 build context）
├── README.md                   ← 本文档
├── open_source/                ← 开源代码（vendored，随本仓库同步）
│   ├── codebase-memory-mcp/    ← CBM C 源码（含 doc-links 等 C 侧定制改动）
│   └── codegraph/              ← codegraph TypeScript 源码
├── src/                        ← 自研生产代码（代码审核范围，进镜像的全部文件）
│   ├── nl_query_server.py      ← CBM 自然语言 HTTP 服务（端口 9788，REST 主服务）
│   ├── mcp_stdio_bridge.py     ← 自写 MCP stdio→streamable-http 桥（替代 supergateway）
│   ├── entrypoint.sh           ← 容器入口（Phase 0-3：发现/索引/守护进程/NL 服务；CM 热修母本）
│   ├── cbm_sync.py             ← 本地盘→EFS 回传（本地盘部署方案的生产组件）
│   ├── glossary_extract.py     ← CN→EN 查询词表抽取（entrypoint Phase 1c 调用）
│   └── bin/                    ← 容器 CLI helper（add-project 等 4 个，装到 /usr/local/bin/）
├── tools/                      ← 工具类脚本（nl_query / code_search / codegraph_client / build_doc_links / run_*.sh）
├── scripts/                    ← 构建/编译/部署脚本（local_deploy / package / serve）
├── tests/                      ← e2e 与功能验证脚本（test_*.py / e2e_test.py）
└── docs/                       ← API 与运维文档（API*.md / docker-shell.md）
```

镜像内自研代码全部来自 `src/`（5 个服务文件 + `bin/` 4 个 helper）；`tools/`、`scripts/`、`tests/`、`docs/` 不进镜像。

## 构建镜像

仓库根即完整的 build context（源码已 vendored，无需 git clone、无需预编译）：

```bash
docker build -t dual-mcp:latest .
```

## 运行

```bash
docker run -d --name dual-mcp \
  -p 9789:9788 \
  -v /path/to/projects:/projects:rw \
  -v cbm-cache:/var/lib/codebase-memory-mcp \
  --restart unless-stopped \
  dual-mcp:latest
```

`/projects/` 下每个子目录会被自动索引。详细用法见下方各章节。

## 验证

```bash
# CBM NL HTTP 服务
curl http://localhost:9789/health
curl http://localhost:9789/projects
curl -X POST http://localhost:9789/query \
  -H 'Content-Type: application/json' \
  -d '{"q":"memset_s","project":"projects-<your-dir>"}'

# 列出所有已索引项目 + 服务状态
docker exec dual-mcp list-projects

# 一键验证/Demo 脚本在仓库 scripts/ 下（不在镜像内），在本目录内运行：
cd tools && bash run_file_code.sh
```

## 环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `CBM_CACHE_DIR` | `/var/lib/codebase-memory-mcp` | CBM SQLite 索引存储位置 |
| `NL_PORT` | `9788` | CBM NL HTTP 端口（也是 `EXPOSE` 的端口） |
| `EXTRA_PROJECTS` | (空) | 额外要索引的项目路径（空格分隔） |
| `CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS` | `2147483647` (~24.8 天，Node int32 上限) | daemon 空闲保活 |
| `CODEGRAPH_TELEMETRY` | `0` | codegraph 匿名遥测关闭 |
| `CODEGRAPH_NO_WATCHDOG` | `1`（entrypoint 设） | 禁用 60s 主线程看门狗，避免大项目索引被误杀 |
| `CBM_PROJECT` | `projects-Hi3516CV610` | 仓库侧 `scripts/run_*.sh` 默认项目名 |
| `CBM_DOC_FILTER` | `ISP` | `run_file_code.sh` 默认文档过滤 |
| `CBM_FUNC_FILTER` | `memset_s` | `run_file_code.sh` 默认函数过滤 |

## Helper 脚本（容器内 `/usr/local/bin/`）

| 命令 | 作用 |
|---|---|
| `list-projects` | 列出 CBM/codegraph 所有已索引项目 + 服务状态 |
| `add-project <path>` | 索引新项目 + 启 codegraph daemon（幂等） |
| `query-project <name> <query...>` | CBM NL 查询（自动 JSON 构造） |
| `remove-project <name_or_path>` | 删除项目索引 + 停 daemon |

## 持久化

- **CBM 索引**：建议 `-v cbm-cache:/var/lib/codebase-memory-mcp` 用 named volume 持久化，docker rm 不丢
- **codegraph 索引**：每个项目的 `.codegraph/` 在项目目录内，跟着项目挂载卷走

## 日志

```bash
# NL server（前台进程，主容器日志）
docker logs dual-mcp

# codegraph daemon（每个项目一个日志文件）
docker exec dual-mcp cat /var/log/cg-<project>.log

# 索引时的一次性日志
docker exec dual-mcp ls /var/log/
```

## 已知坑 & 修复历史

本镜像在 Hi3516CV610 SDK（1.7GB，1300+ C/H 文件）实战过程中遇到并修复了以下问题：

1. **codegraph watchdog 误杀大项目索引**：默认 60s 主线程无响应就 kill → 通过 `CODEGRAPH_NO_WATCHDOG=1` 全局禁用
2. **codegraph daemon 立即退出**：`CODEGRAPH_DAEMON_IDLE_TIMEOUT_MS=31536000000` 超 Node int32 上限 → 改成 `2147483647`
3. **CBM doc-links 漏抽 `(td_void)memset_s(...)`**：`pass_doc_links.c` 的 preceding-char 白名单缺 `)` → 已扩展；同时把 `DL_CANDIDATE_CAP` 从 200 提到 4000，避免大文档被截断
4. **缺 chip.yaml 导致 0 个 Document 节点**：CBM 不会自动把 `.md` 升级为 Document，必须 `chips/*.chip.yaml` 显式声明 → 在 Hi3516 项目根加 `chips/hi3516cv610.chip.yaml`
5. **多个 Python 脚本硬编码 `/data/hunan/` 等路径**：env var 化（`CBM_PROJECT` / `CBM_DOC_FILTER` / `CBM_FUNC_FILTER`）+ 加 `derive_project_root()` 函数从项目名自动推导

CBM 源码改动（`pass_doc_links.c` 等，见 `open_source/codebase-memory-mcp/`）已包含在本仓库内，镜像构建时直接生效。
