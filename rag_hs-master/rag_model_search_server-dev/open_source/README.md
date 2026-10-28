# open_source/ 开源代码专区

> 本目录集中存放海思 RAG Server 引用的第三方开源项目源码（vendored 副本）。
> 原先内嵌于 `release/images/<service>/` 下，2026-10-14 阶段 2 抽离至此（commit `2b2b57d`）。
> 部署资产（Dockerfile / docker-compose.yml / 部署文档）仍留在 `release/images/<service>/`。

---

## 一、合规清单

| 项目 | 本地路径 | 上游来源 | 版本 | License | Copyright |
|---|---|---|---|---|---|
| MemMachine | `memmachine/` | https://github.com/MemMachine/MemMachine | 动态（setuptools-scm，见上游 Releases） | Apache-2.0 | MemMachine |
| MemPalace | `mempalace/` | https://github.com/MemPalace/mempalace | 3.5.0 | MIT | 2026 MemPalace Contributors |
| MinerU | `MinerU-mineru-4.0.11-released/` | https://github.com/opendatalab/MinerU | 4.0.11 | Apache-2.0（含商业条款，见 LICENSE.md） | MinerU Team |

> 抽离日期：2026-10-14。各项目 License 文件原位保留于 `open_source/<repo>/LICENSE`，不另建副本以免版本漂移。

---

## 二、各项目说明

### 1. MemMachine（`memmachine/`）
- **用途**：AI Agent 长期记忆层（记忆存储 / 检索 MCP 服务）
- **上游**：https://github.com/MemMachine/MemMachine
- **License**：Apache-2.0（见 `memmachine/LICENSE`）
- **版本**：动态版本，由 `setuptools-scm` 从 git tag 推导；具体发布版本见上游 Releases
- **本仓用法**：自研 `release/images/memmachine/Dockerfile` 包装构建；部署见 `release/images/memmachine/docker-shell.md`
- **配置**：`memmachine/configuration.yml`（随源码）

### 2. MemPalace（`mempalace/`）
- **用途**：本地优先 AI 记忆系统，verbatim 存储 + 混合检索（BM25 + 向量）
- **上游**：https://github.com/MemPalace/mempalace
- **License**：MIT（见 `mempalace/LICENSE`）
- **版本**：3.5.0（`mempalace/pyproject.toml`）
- **本仓用法**：复用上游 `Dockerfile.rest` 构建；部署见 `release/images/mempalace/MemPalace部署文档.md`
- **构建脚本**：`release/images/mempalace/build.sh`

### 3. MinerU（`MinerU-mineru-4.0.11-released/`）
- **用途**：文档解析工具，将 PDF/OFD/EPUB/HTML/图片/CSV/RTF/OOXML/OpenDocument 等转为 Markdown + JSON
- **上游**：https://github.com/opendatalab/MinerU
- **License**：Apache-2.0（含商业条款：MAU > 1 亿或月营收 > 2000 万美元需单独商业许可，见 `MinerU-mineru-4.0.11-released/LICENSE.md`）
- **版本**：4.0.11（`pyproject.toml` 的 `dynamic = ["version"]`，目录名后缀为发布版本号）
- **本仓用法**：作为 MinerU API 服务源码部署，由 `document_loaders/mineru_client.py` 通过 HTTP API 调用；部署见 `release/images/mineru/` 下的 Dockerfile / compose
- **关键依赖**：Python ≥ 3.10，< 3.15；构建时用 setuptools（见 `pyproject.toml`）

### 4. ~~codebase-memory-mcp~~ / ~~codegraph~~（已移除）

> 2026-10 清理：两项目源码已集成进 `release/images/dual-mcp/`（dual-mcp 镜像自包含 CBM C 源码 + codegraph TypeScript 源码，构建时无需外部 clone）。本目录不再 vendored 两项目副本，`release/images/` 下也不再单独构建两项目镜像。代码知识图谱检索统一走 dual-mcp 服务（见 [server/chat/pipeline/dual_mcp.py](../server/chat/pipeline/dual_mcp.py)）。

---

## 三、维护与升级

- **升级开源版本**：替换 `open_source/<repo>/` 下源码后，更新本 README 的"版本"列与抽离日期；对照 `release/images/<repo>/` 的自研 Dockerfile / compose 确认 COPY 路径仍匹配。
- **新增开源项目**：在本目录建子目录 → 补本 README 清单行 → 在 `release/images/<service>/` 放部署资产 → 配置 compose build context（见《目录结构改造建议.md》§4.5 两种模式）。
- **License 合规**：每个开源项目必须保留原始 `LICENSE` 与版权声明；本仓仅作 vendored 副本，不修改 License 文件。
- **不应在此目录**：自研代码（放 `src/` 或 `release/images/<service>/`）、部署资产（Dockerfile / compose / 脚本）、配置数据（放 `configs/`）。

---

## 四、相关文档

- 目录结构改造总览：[`../docs/todo/目录结构改造建议.md`](../docs/todo/目录结构改造建议.md)（§阶段 2、§4.5）
- 镜像构建与推送：[`../release/images/push-docker.md`](../release/images/push-docker.md)
- 部署总览：[`../release/images/docker.md`](../release/images/docker.md)
