# MemPalace 项目说明

> MemPalace 是一个 local-first 的 AI 长期记忆系统（Python 包 `mempalace`，v3.5.0，Python ≥ 3.9，MIT 协议）：把对话与项目内容以**原文 verbatim** 形式存入"宫殿"（Wing→Room→Drawer），用 AAAK 压缩索引 + 时序知识图谱 + 混合检索实现高召回检索，零外部 API 默认，所有数据默认不离开本机。

官方唯一来源：[GitHub 仓库](https://github.com/MemPalace/mempalace)、[PyPI 包](https://pypi.org/project/mempalace/)、文档站 [mempalaceofficial.com](https://mempalaceofficial.com)。其他域名（`.tech`、`.net` 等）均为仿冒站点，详见 [docs/HISTORY.md](https://github.com/MemPalace/mempalace/blob/main/docs/HISTORY.md)。

---

## 目录

1. [项目简介与核心能力](#1-项目简介与核心能力)
2. [设计原则](#2-设计原则)
3. [技术架构](#3-技术架构)
4. [代码仓库结构](#4-代码仓库结构)
5. [接口说明](#5-接口说明)
6. [对接集成到普通 RAG](#6-对接集成到普通-rag)
7. [部署方式](#7-部署方式)
8. [配置与环境变量](#8-配置与环境变量)
9. [使用示例](#9-使用示例)
10. [基准测试](#10-基准测试)
11. [参考项目代码与开源文档](#11-参考项目代码与开源文档)
12. [常用命令速查与健康检查](#12-常用命令速查与健康检查)

---

## 1. 项目简介与核心能力

MemPalace 把你的对话历史与项目文件以**原文**形式存储，再用语义检索找回来。它**不摘要、不抽取、不改写**——索引是结构化的：人与项目变成 *wings*（翼），主题变成 *rooms*（房间），原文内容存进 *drawers*（抽屉）；此外还有 *closets*（衣橱）作为指向 drawer 的压缩指针索引层。这样检索可以按 wing/room 范围限定，而不是对着一个扁平语料库盲搜。

**核心能力：**

- **宫殿结构**：`WING → ROOM → DRAWER` 三级组织，外加 closet 指针索引、跨 wing 的 tunnel（隧道）与 wing 内的 hallway（走廊）。
- **AAAK 压缩索引**：`dialect.py` 实现的紧凑符号格式，把名字/重复词/概念/关键时刻压成 LLM 可秒扫的 shorthand，指向具体 drawer 位置（有损压缩，原文不可还原；96.6% 基准来自 raw 模式而非 AAAK 模式）。
- **时序知识图谱**：`knowledge_graph.py`，基于本地 SQLite，三元组 `subject → predicate → object` + `valid_from / valid_to`，支持 `as_of` 时间点过滤、invalidate、timeline。
- **混合检索**：`searcher.py`，向量相似度 + BM25 关键词重排 + 时间/偏好加权，`max_distance` 阈值过滤，closet 命中只做加权不挡结果。
- **可插拔存储后端**：`backends/base.py` 定义抽象接口，内置 `chroma`（默认）、`sqlite_exact`、`qdrant`、`pgvector` 四个实现；第三方可通过 entry point 注册。
- **多嵌入模型**：`minilm`（384d，英）、`embeddinggemma`（384d，多语言，新装默认）、`bgem3`（1024d，BGE-M3，需 GPU）。
- **多接口**：CLI、MCP Server（35 工具，stdio + http）、REST Adapter（FastAPI，端口 8777）、Python API、以及 Claude Code / Codex / Cursor / Antigravity 的 auto-save hooks。
- **零外部 API 默认**：核心抽取/分块/嵌入/检索全部在本机完成；外部 BYOK（Anthropic/OpenAI 等）永远是显式 opt-in，从不在后台静默启用。
- **基准**：LongMemEval raw 模式 **96.6% R@5**（500 题，零 API key、零云、零 LLM）；hybrid v4 held-out 450 题 **98.4%**；hybrid v4 + LLM rerank 全 500 题 **≥99%**。

**适用场景**：给 AI agent 加长期记忆、把多项目/多对话沉淀成可检索知识库、为 RAG 流水线提供"原文 + 时序事实"的高召回检索底座。

---

## 2. 设计原则

以下原则来自 `AGENTS.md`（即 `CLAUDE.md`），是每个 PR/feature/refactor 必须遵守的硬约束：

- **Verbatim always（永远原文）**：绝不摘要、改写或有损压缩用户数据。系统检索索引并返回原始用词——用户说过什么就存什么。这是最底层的承诺。
- **Incremental only（只增量）**：初次构建后只做 append-only 摄取，绝不为了重建而销毁既有数据；操作中途崩溃必须让既有宫殿原封不动。
- **Entity-first（实体优先）**：一切以真实姓名为键，用 DOB/ID/上下文消歧；人比主题更重要。
- **Local-first, zero external API by default（本地优先，默认零外部 API）**：抽取/分块/嵌入/LLM 辅助精炼默认都在本机（Ollama、LM Studio、llama.cpp、vLLM 等）；外部 provider 走 BYOK 但从不默认开启、从不静默回退。本机的 Ollama 不算"外部 API"。
- **Performance budgets（性能预算）**：hooks < 500ms；启动注入 < 100ms；记忆应当让人感觉是即时的。
- **Privacy by architecture（架构级隐私）**：系统在物理上无法外发你的数据，因为数据从不离开本机；核心操作无遥测、无 phone-home、无外部服务依赖。
- **Background everything（一切后台化）**：归档、索引、时间戳、流水线工作都通过后台 hooks 完成，绝不打断用户对话；聊天窗口里零 token 花在簿记上。

**不接受的反向改动**：用户内容摘要化、云存储/同步、遥测/分析、需要 API key 的核心记忆功能、任何绕开原文存储的捷径。

---

## 3. 技术架构

### 3.1 宫殿结构

```
WING  (person / project / topic)        ← 大类，顶层组织单元
  └── ROOM  (day / topic / aspect)      ← 时间或主题分组
        └── DRAWER  (verbatim chunk)    ← 原文文本块（~800 字符/块，可调）
  (+ CLOSET  → 指向 drawer 的压缩指针索引)
```

- **Wing**：每个项目/人/主题一个翼，如 `wing_code`、`wing_alice`。
- **Room**：翼内的具体主题/日期，如 `auth-migration`、`graphql-switch`、`diary`。
- **Drawer**：原文 verbatim 块，是"真相之源"（source of truth）。
- **Closet**：紧凑指针索引（`mempalace_closets` 集合），格式如 `topic|entities|→drawer_id_a,drawer_id_b`；搜索先扫 closet（快、短文本）再开 drawer 取原文。
- **Hallway**（走廊）：同一 wing 内两实体共现时自动生成的图边。
- **Tunnel**（隧道）：跨 wing 的显式/主题连接，让查询能从一个项目的房间跳到另一个项目的房间。

### 3.2 AAAK 索引层（`dialect.py`）

AAAK 是一种**有损**的结构化符号摘要格式，把实体/主题/关键句/情绪/标志抽成紧凑表示，任何 LLM 都能原生读取（无需解码器）。格式示例：

```
Header:  FILE_NUM|PRIMARY_ENTITY|DATE|TITLE
Zettel:  ZID:ENTITIES|topic_keywords|"key_quote"|WEIGHT|EMOTIONS|FLAGS
Tunnel:  T:ZID<->ZID|label
Arc:     ARC:emotion->emotion->emotion
```

关键点：AAAK 是 closet 层的压缩指针，**不可还原原文**；96.6% 基准分来自 raw 模式（直接搜 drawer），不是 AAAK 模式。情绪码（如 `vul/joy/fear/love/hope`）与标志（`ORIGIN/CORE/DECISION/TECHNICAL` 等）详见 `mempalace/dialect.py` 顶部 docstring。

### 3.3 时序知识图谱（`knowledge_graph.py`）

基于本地 SQLite（默认 `~/.mempalace/knowledge_graph.sqlite3`，schema 见 `docs/schema.sql`）：

```sql
CREATE TABLE triples (
    id TEXT PRIMARY KEY,
    subject TEXT NOT NULL, predicate TEXT NOT NULL, object TEXT NOT NULL,
    valid_from TEXT, valid_to TEXT,
    confidence REAL DEFAULT 1.0,
    source_closet TEXT, source_file TEXT
);
-- entities / attributes 表 + subject/object/predicate/valid 索引
```

- 三元组带时间窗：`Subject → Predicate → Object [valid_from → valid_to]`。
- `as_of` 时间点过滤：`query_entity("Max", as_of="2026-01-15")` 只返回该时刻为真的事实。
- `invalidate()` 标记事实失效（设 `valid_to`），历史查询仍可见。
- 写入时拒绝 `valid_to < valid_from` 的反向区间（`ValueError`）。
- 日期校验：`sanitize_iso_temporal()` 接受 `YYYY-MM-DD`、`YYYY-MM-DDTHH:MM:SSZ`、`YYYY-MM-DDTHH:MM:SS+00:00`（归一为 `Z`），拒绝部分日期/naive datetime/非 UTC 偏移。

### 3.4 可插拔存储后端（`backends/base.py`）

`BaseBackend` / `BaseCollection` 抽象接口（RFC 001），`QueryResult` / `GetResult` typed dataclass、`PalaceRef` palace 句柄、统一错误类（`BackendError`、`PalaceNotFoundError`、`BackendMismatchError`、`DimensionMismatchError`、`EmbedderIdentityMismatchError`、`UnsupportedCapabilityError` 等）。隔离契约：一个 `PalaceRef.id` 写入的记录绝不能被另一个 id 的操作读到/改到/删到；`namespace` 是额外分区，仅 `supports_namespace_isolation` 能力的后端兑现。

四个内置后端（在 `pyproject.toml` 的 `[project.entry-points."mempalace.backends"]` 注册）：

| 后端 | 说明 | 入口 |
|---|---|---|
| `chroma` | 默认，ChromaDB 本地持久化（HNSW + cosine） | `mempalace.backends.chroma:ChromaBackend` |
| `sqlite_exact` | 本地精确向量正确性校验后端，无外部服务 | `mempalace.backends.sqlite_exact:SQLiteExactBackend` |
| `qdrant` | Qdrant REST 后端（opt-in，默认 localhost:6333） | `mempalace.backends.qdrant:QdrantBackend` |
| `pgvector` | PostgreSQL + pgvector 后端（opt-in，需 `vector` 扩展） | `mempalace.backends.pgvector:PgVectorBackend` |

后端选择优先级（`backends/registry.py:resolve_backend_for_palace`）：显式 kwarg/CLI flag > per-palace config 值 > `MEMPALACE_BACKEND` 环境变量 > 从磁盘产物自动探测（仅迁移用） > 默认 `chroma`。qdrant/pgvector 默认指向 localhost，绝不静默把记忆发往远端；写本地 marker 文件（`qdrant_backend.json` / `pgvector_backend.json`）防止误连错服务器。

### 3.5 嵌入模型

| 模型（`MEMPALACE_EMBEDDING_MODEL`） | 维度 | 语言 | 大小 | 说明 |
|---|---|---|---|---|
| `minilm` | 384 | 英文 only | ~80 MB | `all-MiniLM-L6-v2`，ChromaDB 默认；旧宫殿默认值（向后兼容） |
| `embeddinggemma` | 384（MRL 截断） | 100+ 语言 | ~300 MB | `onnx-community/embeddinggemma-300m-ONNX`（q8）；**新装默认**；跨语言 cosine ~0.88 vs minilm ~0.35 |
| `bgem3` | 1024 | 多语言（中文/多语言最强） | ~2.3 GB | BAAI/bge-m3 via sentence-transformers（PyTorch，需 GPU torch）；`MEMPALACE_BGE_M3_PATH` 指向本地模型目录（默认 `/disk/models/bge-m3`） |

设备选项（`MEMPALACE_EMBEDDING_DEVICE`）：`auto`（默认，按 CUDA→CoreML→DirectML→CPU 探测）、`cpu`、`cuda`（需 `pip install mempalace[gpu]`）、`coreml`（macOS，`[coreml]`）、`dml`（Windows，`[dml]`）。请求不可用加速器时打一次 warning 并回退 CPU，绝不硬失败。

**维度变更注意**：切换嵌入模型（不同向量空间）必须 `mempalace repair --yes` 全量重嵌入；bgem3 是 1024 维，与 384 维不兼容。

### 3.6 L0–L3 唤醒栈（`layers.py`）

```
Layer 0: Identity       ~100 tokens    始终加载，读 ~/.mempalace/identity.txt
Layer 1: Essential Story ~500-800       始终加载，从 palace 最高权重/最新 drawer 自动生成
Layer 2: On-Demand      ~200-500/次    主题/wing 出现时按需加载
Layer 3: Deep Search    不限           完整 ChromaDB 语义搜索
```

唤醒成本约 600–900 tokens（L0+L1），留下 95%+ 上下文给对话。`mempalace wake-up` 命令打印 L0+L1。

### 3.7 数据流

```
User → CLI / MCP Server / REST Adapter → Storage Backend (chroma 默认，可插拔)
                                      → SQLite (时序知识图谱)
Index layer (AAAK / closets): 压缩指针 → DRAWER 位置
```

- 写入：`mine`/`add_drawer`/`diary_write`/hooks → 分块 → 嵌入 → 后端 upsert（幂等，确定性 drawer ID）→ 同步生成 closet 指针、hallway、KG 三元组。
- 检索：`search` → 向量查询 drawer + closet 指针召回 → BM25 重排 → 时间/偏好加权 → `max_distance` 过滤 → 返回原文片段。
- 唤醒：`wake-up` → L0(identity) + L1(精选 drawer)。

---

## 4. 代码仓库结构

仓库根目录关键文件与目录（以 `d:\Code\haisi\mempalace` 为根，下同）：

```
mempalace/                         ← 核心包（pip install mempalace 安装的就是它）
├── cli.py                         ← CLI 子命令派发（init/mine/search/wake-up/...）
├── mcp_server.py                  ← MCP Server：35 工具 + stdio/http 传输
├── config.py                      ← 配置 + 输入校验（sanitize_name/sanitize_content/sanitize_iso_temporal）
├── embedding.py                   ← 嵌入模型工厂（minilm/embeddinggemma/bgem3 + 设备）
├── onboarding.py                   ← 首次运行交互（mode/people/projects/wings/embedding 选择）
├── searcher.py                     ← 混合检索（向量 + BM25 + 时间/偏好加权 + max_distance）
├── knowledge_graph.py             ← 时序知识图谱（SQLite 三元组 + valid_from/valid_to + as_of）
├── dialect.py                      ← AAAK 压缩 dialect（closet 层）
├── layers.py                       ← L0-L3 唤醒栈
├── palace.py                       ← 宫殿共享操作（get_collection/build_closet_lines/mine_lock...）
├── palace_graph.py                 ← 房间遍历 + 跨 wing 隧道
├── miner.py                        ← 项目文件挖掘
├── convo_miner.py                  ← 对话 transcript 挖掘
├── sweeper.py / sync.py / split_mega_files.py / normalize.py / repair.py / migrate.py
├── entity_detector.py / entity_registry.py / corpus_origin.py / llm_refine.py / llm_client.py
├── hallways.py / dynamics.py / dedup.py / exporter.py / fact_checker.py / query_sanitizer.py
├── hooks_cli.py / hook_shell.py / daemon.py / service.py / wal.py / backups.py / ids.py
├── spellcheck.py / format_miner.py / general_extractor.py / closet_llm.py / collision_scan.py
├── room_detector_local.py / project_scanner.py / convo_scanner.py / diary_ingest.py
├── instructions_cli.py / _stdio.py / version.py / __main__.py / py.typed
├── backends/                       ← 可插拔存储后端
│   ├── base.py                     ← 抽象接口（BaseBackend/BaseCollection/PalaceRef/QueryResult）
│   ├── chroma.py / sqlite_exact.py / qdrant.py / pgvector.py
│   ├── registry.py                 ← 注册 + entry-point 发现 + resolve_backend_for_palace
│   ├── embedding_wrapper.py / _sidecar.py
├── sources/                        ← RFC 002 source-adapter 脚手架（base/context/registry/transforms）
├── i18n/                           ← 14 个 locale JSON（en/zh-CN/zh-TW/ja/ko/de/es/fr/it/ru/hi/pt-br/id/be）
├── instructions/                   ← 5 个 skill 指令（help/init/mine/search/status.md）
└── data/                           ← coca_content_words.json / known_systems.json

hooks/                              ← auto-save 钩子脚本
├── mempal_save_hook.sh             ← Claude Code Stop
├── mempal_precompact_hook.sh       ← Claude Code PreCompact
├── mempal_session_end_hook.sh      ← Claude Code SessionEnd
├── cursor/                         ← Cursor（save/precompact/wake + install.sh + lib/common.sh）
└── antigravity/                    ← Antigravity IDE（save/wake + install.sh）

examples/                           ← 用法示例
├── rest_adapter.py                 ← FastAPI REST 适配层（即 haisi-mempalace:rest 镜像入口）
├── rest_adapter_API.md             ← REST API 中文文档
├── mcp_setup.md / HOOKS_TUTORIAL.md / basic_mining.py / convo_import.py
├── rest_api_demo.py / gemini_cli_setup.md
├── cursor/  antigravity/           ← 各 IDE 的 hooks.json 示例

benchmarks/                         ← 可复现基准
├── BENCHMARKS.md / HYBRID_MODE.md / README.md
├── longmemeval_bench.py / locomo_bench.py / convomem_bench.py / membench_bench.py / mine_bench.py
├── lme_split_50_450.json           ← LongMemEval 50/450 确定性 split（seed=42）
├── results_*.jsonl                 ← 每题结果文件
└── model_eval/                     ← 模型评估数据集/任务/报告

docs/                               ← 概念与运维文档
├── CLOSETS.md / schema.sql / HISTORY.md / RELEASING.md / format-coverage.md / virtual-line-numbering.md
├── rfcs/002-source-adapter-plugin-spec.md
└── recovery/                       ← index-metadata-recovery / wing-name-migration

tests/                              ← 测试套件（test_*.py，镜像源码结构）
└── benchmarks/                     ← scale/stress 测试

website/                            ← VitePress 文档站
├── guide/   (getting-started / configuration / mcp-integration / hooks / cursor-hooks / claude-code / claude-code-retention / antigravity / gemini-cli / local-models / mining / searching / openclaw)
├── reference/ (cli / mcp-tools / python-api / api-reference / benchmarks / modules / contributing)
├── concepts/ (the-palace / knowledge-graph / aaak-dialect / memory-stack / agents / contradiction-detection)
└── .vitepress/ (config.mts + theme)

# 多 IDE 插件目录
.claude-plugin/   ← Claude Code 插件（commands/hooks/skills + plugin.json + .mcp.json + marketplace.json）
.codex-plugin/    ← Codex CLI 插件（hooks/mempal-hook.sh + skills + plugin.json）
.cursor-plugin/   ← Cursor IDE 插件（commands/skills/mcp.json + plugin.json + marketplace.json）
.antigravity-plugin/ ← Google Antigravity 插件（rules/skills/hooks + mcp_config.json）
skills/            ← 顶层 skill（mempalace / mempalace-recall）
commands/          ← 顶层命令 markdown（mempalace-help/init/mine/search/status.md）
integrations/      ← openclaw SKILL + shared recall-protocol.md
tools/             ← backup_claude_jsonls.sh / find_orphan_claude_jsonls.sh / render_jsonl.py / save.md
landing/           ← 落地页 HTML + logo
assets/            ← logo + brand
.devcontainer/ / .github/ (CI workflows + ISSUE_TEMPLATE + dependabot)

# 部署与打包
Dockerfile              ← CPU 镜像（haisi-mempalace:latest，默认 bundling extract+spellcheck）
Dockerfile.gpu          ← NVIDIA CUDA 镜像（haisi-mempalace:gpu，onnxruntime-gpu）
Dockerfile.rest         ← REST 适配层镜像（haisi-mempalace:rest，基于 haisi-mempalace:latest + FastAPI/uvicorn）
Dockerfile.rest.bgem3   ← BGE-M3 + GPU torch 镜像（haisi-mempalace:rest-bgem3）
docker-compose.yml      ← mcp 服务 + mempalace-data 卷
docker-entrypoint.sh    ← 灵活入口（mcp / cli / 任意子命令）
mcp.json                ← 最简 MCP 客户端配置（{"mcpServers":{"mempalace":{"command":"mempalace-mcp"}}}）
pyproject.toml          ← 包元数据 + 依赖 + extras + entry points + ruff/pytest 配置
uv.lock / .pre-commit-config.yaml / .python-version / SECURITY.md / LICENSE
README.md / AGENTS.md / MISSION.md / ROADMAP.md / CHANGELOG.md / CONTRIBUTING.md / CLAUDE.md
```

> 说明：仓库内另有 `mempalace_haisi/` 目录，是本机对 `mempalace/` 的一份工作副本，**不属于上游 MemPalace 官方包结构**，本说明文档不将其计入。

---

## 5. 接口说明

MemPalace 提供四种集成接口 + 一套 auto-save hooks。

### 5.1 CLI（`mempalace/cli.py`）

入口：`mempalace`（`pyproject.toml` 的 `[project.scripts]` 声明 `mempalace = "mempalace.cli:main"`）。所有命令接受 `--palace <path>` 覆盖宫殿位置、`--backend <name>` 覆盖后端。实际存在的子命令：

| 子命令 | 说明 | 关键参数 |
|---|---|---|
| `init <dir>` | 扫描项目目录，检测人/项目/room，初始化宫殿 | `--yes` `--auto-mine` `--lang en,pt-br` `--no-llm` `--llm-provider ollama/openai-compat/anthropic` `--llm-model gemma4:e4b` `--backend` |
| `mine <dir>` | 把文件挖掘进宫殿 | `--mode projects/convos/extract` `--wing` `--agent` `--limit` `--dry-run` `--no-gitignore` `--include-ignored` `--extract exchange/general` `--max-chunks-per-file` `--redetect-origin` `--daemon --background` |
| `sweep <target>` | 串联挖掘：补主挖掘漏掉的消息级内容（幂等、可恢复） | `<target>` 是 .jsonl 或目录 |
| `sync [dir]` | 清理被 gitignore/删除/移动的源文件对应 drawer | `--wing` `--root` `--dry-run`(默认) `--apply` `--daemon` |
| `search <query>` | 语义检索（混合 BM25+向量） | `--wing` `--room` `--results`(默认 5) `--backend` |
| `compress` | 用 AAAK dialect 压缩 drawer（~30x） | `--wing` `--dry-run` `--config entities.json` |
| `wake-up` | 打印 L0+L1 唤醒上下文（~600-900 tokens） | `--wing` |
| `split <dir>` | 拆分拼接型 transcript 大文件为单会话文件 | `--output-dir` `--dry-run` `--min-sessions`(默认 2) |
| `hook run` | 执行 hook 逻辑（stdin JSON→stdout JSON） | `--hook session-start/stop/session-end/precompact` `--harness claude-code/codex` |
| `instructions <name>` | 输出 skill 指令到 stdout | name ∈ init/search/mine/help/status |
| `repair` | 重建索引 / 修 max_seq_id / 从 SQLite 重建 | `--mode legacy/max-seq-id/from-sqlite` `--yes` `--source` `--archive-existing` `--segment` `--from-sidecar` `--backup` `--dry-run` `--confirm-truncation-ok` |
| `repair-status` | 只读 HNSW 容量健康检查（sqlite vs HNSW 计数对比） | — |
| `daemon start/stop/status/jobs/wait` | 管理可选本地守护进程（排队写） | `start --foreground --backend`、`jobs --limit`、`wait <job_id>` |
| `mcp` | 打印 MCP 接入命令 | `--backend` |
| `migrate` | 跨 ChromaDB 版本迁移宫殿 | `--dry-run` `--yes` |
| `migrate-wings` | 规范化旧 wing 名（去首尾分隔符） | `--dry-run` `--yes` |
| `status` | 显示宫殿已归档内容概览 | `--backend` |
| `palace set-embedder` | 记录/覆盖宫殿嵌入器 identity | `--model` `--force` `--backend` |

示例：

```bash
mempalace init ~/projects/myapp --yes --auto-mine
mempalace mine ~/projects/myapp
mempalace mine ~/.claude/projects/ --mode convos --wing myapp
mempalace search "why did we switch to GraphQL" --wing myapp --results 5
mempalace wake-up --wing myapp
mempalace status
mempalace sweep ~/.claude/projects/
```

### 5.2 MCP Server（`mempalace/mcp_server.py`）

入口：`mempalace-mcp`（`[project.scripts]` 声明 `mempalace-mcp = "mempalace.mcp_server:main"`）。共 **35 个工具**（`TOOLS` dict，源码逐条确认）：

**宫殿读（7）**：`mempalace_status`、`mempalace_list_wings`、`mempalace_list_rooms`、`mempalace_get_taxonomy`、`mempalace_search`、`mempalace_check_duplicate`、`mempalace_get_aaak_spec`

**Drawer 读写（9）**：`mempalace_add_drawer`、`mempalace_checkpoint`、`mempalace_delete_drawer`、`mempalace_mine`、`mempalace_delete_by_source`、`mempalace_sync`、`mempalace_get_drawer`、`mempalace_list_drawers`、`mempalace_update_drawer`

**知识图谱（5）**：`mempalace_kg_query`、`mempalace_kg_add`、`mempalace_kg_invalidate`、`mempalace_kg_timeline`、`mempalace_kg_stats`

**宫殿图（9）**：`mempalace_traverse`、`mempalace_find_tunnels`、`mempalace_graph_stats`、`mempalace_create_tunnel`、`mempalace_list_tunnels`、`mempalace_delete_tunnel`、`mempalace_list_hallways`、`mempalace_delete_hallway`、`mempalace_follow_tunnels`

**Diary（2）**：`mempalace_diary_write`、`mempalace_diary_read`

**设置/运维（3）**：`mempalace_hook_settings`、`mempalace_memories_filed_away`、`mempalace_reconnect`

**两种传输**：

- **stdio**（默认）：JSON-RPC over stdin/stdout。
- **http**（opt-in，v3.5.0 新增）：`mempalace-mcp --transport http` 在 `POST /mcp` 提供 JSON-RPC、`GET /healthz` 探活；默认 host `127.0.0.1`、默认 port `8765`（`--host`/`--port` 覆盖）。安全加固：loopback 绑定固定 Host 头、拒绝非 loopback 浏览器 Origin（防 DNS-rebinding/SSRF）、`MEMPALACE_MCP_HTTP_TOKEN` 可选 bearer token（`/mcp` 强制、`/healthz` 从不要求）、16 MiB 请求上限、非 loopback 绑定时醒目 warning。http 传输复用 stdio 同一个 `handle_request` 派发器，无独立写/搜路径。

**Claude Code 接入**（最简）：

```bash
claude mcp add mempalace -- mempalace-mcp
# 或指定宫殿路径：
claude mcp add mempalace -- mempalace-mcp --palace /path/to/palace
```

或直接写 `~/.claude/settings.local.json` / `.mcp.json`：

```json
{
  "mcpServers": {
    "mempalace": { "command": "mempalace-mcp" }
  }
}
```

**Cursor 接入**：`.cursor-plugin/` 插件自动注册 `mempalace-mcp` server + 5 个 slash command + `mempalace` skill，无需手改 `~/.cursor/mcp.json`（`mcp.json` 需 `mcpServers` 包装，与 Claude 的扁平 `.mcp.json` 不同）。

完整工具参数详见 [mempalaceofficial.com/reference/mcp-tools](https://mempalaceofficial.com/reference/mcp-tools.html)。

### 5.3 REST Adapter（`examples/rest_adapter.py` → `haisi-mempalace:rest` 镜像）

一个薄的 FastAPI 层，**直接复用 MCP 的 `tool_search` / `tool_kg_query` / `tool_diary_write` handler**，不重新实现检索。默认绑 `127.0.0.1:8777`，公网暴露需前置 Caddy/nginx 做 TLS + 访问控制。端点：

| 方法/路径 | 鉴权 | 说明 |
|---|---|---|
| `GET /healthz` | 否 | 探活，返回 `"ok"` |
| `GET /` | 否 | 服务信息 + 端点清单 + 鉴权状态 |
| `POST /palace` | 是 | 创建空宫殿（幂等，不下载嵌入模型，秒回） |
| `POST /store` | 是 | 存一条原文条目（首次写自动建宫 + 加载嵌入模型） |
| `GET /search` | 是 | 语义检索（向量+BM25+时间/偏好加权） |
| `GET /kg` | 是 | 知识图谱查询 |

**`GET /search` 完整参数表：**

| 参数 | 类型 | 必填 | 默认 | 约束 | 说明 |
|---|---|---|---|---|---|
| `q` | string | 是 | — | 1–250 字符 | 搜索关键词或问题 |
| `limit` | int | 否 | `5` | 1–100 | 最多返回条数 |
| `wing` | string | 否 | — | — | 按翼过滤 |
| `room` | string | 否 | — | — | 按房间过滤 |
| `source_file` | string | 否 | — | — | 按来源文件**完整路径**精确过滤 |
| `max_distance` | float | 否 | `1.5` | 0–2 | 余弦距离阈值，越小越严（0=只召回几乎相同的） |
| `context` | string | 否 | — | — | 背景上下文（不参与嵌入，仅未来重排用） |
| `detail` | string | 否 | `snippet` | meta/snippet/full | 敏感字段裁剪档位 |
| `snippet_len` | int | 否 | `240` | 16–4000 | `detail=snippet` 时截断长度 |
| `palace` | string | 否 | — | — | 宫殿编号；省略查默认宫殿 |

**`GET /kg` 完整参数表：**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `entity` | string | 是 | — | 实体名（作为 subject 或 object） |
| `direction` | string | 否 | `both` | `outgoing` / `incoming` / `both` |
| `as_of` | string | 否 | — | 时间点过滤（`YYYY-MM-DD` 或 `YYYY-MM-DDTHH:MM:SSZ`） |
| `detail` | string | 否 | `snippet` | meta/snippet/full；`meta` 会把 subject/object 打码 |
| `palace` | string | 否 | — | 宫殿编号 |

**`POST /palace` 参数**：Query `palace`（可选宫殿编号）。

**`POST /store` Body（JSON）：**

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `entry` | string | 是 | — | 要存储的原文（≥1 字符） |
| `agent_name` | string | 否 | `"mcp"` | 归属 agent（转小写） |
| `topic` | string | 否 | `"general"` | 主题标签 |
| `wing` | string | 否 | `""` | 翼；留空按 `agent_name` 自动派生 |
| `palace` | string | 否 | `null` | 宫殿编号 |

**多宫殿隔离**：`palace` 编号经 `sanitize_name` 校验（拦 `..`/`/`/`\`/空字符/unsafe），映射到 `$MEMPALACE_PALACES_ROOT/<id>`（默认 `/data/palaces`）。每个宫殿是完全独立的后端，存查互不干扰；多宫殿切换在进程级锁内串行执行，并发不串库。默认宫殿（不传 `palace`）走 `MEMPALACE_PALACE_PATH`（镜像默认 `/data/.mempalace/palace`），向后兼容老调用。

**敏感字段裁剪 `detail`：**

| `detail` | `/search` 返回 | 适用场景 |
|---|---|---|
| `meta` | 翼/房间/相似度等元数据，**不含原文、不含来源路径** | 只想知道"有没有/在哪个房间" |
| `snippet`（默认） | meta + 原文截断（默认 240 字，可调 `snippet_len`） | 对接方看个上下文 |
| `full` | 完整原文 + 来源路径（仅剥内部簿记字段） | 完全信任的对接方 |

任何档位都剥掉内部字段（`_sort_key`、`_source_file_full`、`_chunk_index`、`_parent_drawer_id`、`closet_boost`、`effective_distance`）。`/kg` 的 `meta` 档会把实体值打码（如 `Alice` → `A***`）。

**鉴权**：`Authorization: Bearer <MEMPALACE_REST_TOKEN>`；非 loopback 绑定无 token 且未开 `NO_AUTH` → **fail-closed 拒绝启动**。`MEMPALACE_REST_NO_AUTH=1` 在可信内网免鉴权（启动打醒目 WARNING）。token 用 `secrets.compare_digest` 常量时间比较。

**HTTP 状态码**：`200` 查询成功、`201` 创建成功、`400` 参数非法/宫殿编号非法/handler 报错（如空宫殿查询返回 `No palace found`）、`401` 未授权、`422` FastAPI 校验失败。交互式 API 文档：启动后访问 `/docs`（Swagger）或 `/redoc`。

### 5.4 Python API

```python
# 检索
from mempalace.searcher import search_memories
results = search_memories(query="why did we switch to GraphQL",
                          wing="myapp", room="architecture", n_results=5)

# 唤醒栈
from mempalace.layers import MemoryStack
stack = MemoryStack()
context = stack.wake_up(wing="myapp")          # L0+L1，~600-900 tokens
recall = stack.recall(wing="myapp", room="auth", n_results=10)  # L2
deep  = stack.search("pricing change", wing="myapp")            # L3

# 时序知识图谱
from mempalace.knowledge_graph import KnowledgeGraph
kg = KnowledgeGraph()
kg.add_triple("Kai", "works_on", "Orion", valid_from="2025-06-01")
kg.invalidate("Kai", "works_on", "Orion", ended="2026-03-01")
facts = kg.query_entity("Kai", as_of="2026-01-15", direction="both")

# 宫殿图（跨 wing 隧道）
from mempalace.palace_graph import build_graph, traverse, find_tunnels, graph_stats
nodes, edges = build_graph()
path = traverse("auth-migration", max_hops=2)

# AAAK dialect
from mempalace.dialect import Dialect
dialect = Dialect()
compressed = dialect.compress("We decided to use GraphQL because REST was too chatty.")
```

**扩展后端**：子类化 `mempalace/backends/base.py` 的 `BaseBackend` + `BaseCollection`，实现 `get_collection`/`add`/`query`/`get`/`update`/`delete`/`run_maintenance` 等，再通过 `pyproject.toml` 的 `[project.entry-points."mempalace.backends"]` 注册（或在测试中调用 `backends.registry.register(name, cls)`，显式注册优先于 entry-point）。

### 5.5 Auto-save Hooks

| IDE | 钩子 | 触发 | 脚本位置 |
|---|---|---|---|
| Claude Code | Stop / PreCompact / SessionEnd | 每隔 15 条人消息存一次；压缩前先存；会话结束做收尾 mine | `hooks/mempal_save_hook.sh`、`hooks/mempal_precompact_hook.sh`、`hooks/mempal_session_end_hook.sh`（也打包进 `.claude-plugin/hooks/`） |
| Codex CLI | 单 hook | 同上 | `hooks/` + `.codex-plugin/hooks/mempal-hook.sh` |
| Cursor IDE | stop / preCompact / sessionStart | stop 默认发 `followup_message`（每 `MEMPAL_SAVE_INTERVAL=15`）；preCompact 同步 mine；sessionStart 注入 recall | `hooks/cursor/mempal_save_hook_cursor.sh`、`mempal_precompact_hook_cursor.sh`、`mempal_wake_hook_cursor.sh` + `install.sh` |
| Antigravity | Stop / PreInvocation | Stop 后台 mine；PreInvocation 首次调用注入 verbatim 记忆 | `hooks/antigravity/` + `.antigravity-plugin/` |

**总开关 / kill switch**：`MEMPALACE_HOOKS_AUTO_SAVE=false`（所有 hooks 共用）、`MEMPAL_DISABLE_HOOK=1`（Cursor 别名）、`rm -rf ~/.mempalace`（稳定状态，hooks 不再自动重建目录）。安装参考 `examples/HOOKS_TUTORIAL.md`、[mempalaceofficial.com/guide/hooks](https://mempalaceofficial.com/guide/hooks.html)、[/guide/cursor-hooks](https://mempalaceofficial.com/guide/cursor-hooks.html)。

---

## 6. 对接集成到普通 RAG

把 MemPalace 当 RAG 的"原文 + 时序事实"高召回检索底座，推荐路径如下。

### 6.1 推荐架构

```
[数据源]                          [MemPalace REST :8777]                  [RAG LLM]
对话/文档/工单  ──POST /store──►  宫殿(ChromaDB+SQLite KG)  ──GET /search──►  拼入 prompt
                                  原文 verbatim 入 drawer     ──GET /kg────►  查时序事实
```

1. **建库**：`POST /palace?palace=<业务id>` 预建空宫殿（秒回，不下载模型）。
2. **存原文**：`POST /store` 把每条原文（对话/文档段落/工单）以 verbatim 写入，`wing`/`room`/`topic` 做范围标签。首次写会触发嵌入模型下载（minilm ~80MB / embeddinggemma ~300MB / bgem3 加载本地 2.3GB）。
3. **检索阶段**：RAG 在生成前调 `GET /search` 取 top-k 原文片段拼进 prompt；对需要"某实体当前/历史事实"的提问调 `GET /kg` 查三元组。
4. **敏感字段裁剪**：用 `detail` 控制是否下发原文给下游 LLM——`meta` 只给元数据（不下发原文/来源路径），`snippet` 给截断原文，`full` 给完整原文（仅完全信任的对接方）。

### 6.2 多宫殿（多库）隔离

按业务线/租户/项目建独立 palace：

```bash
curl -X POST "$B/palace?palace=kb-docs"   -H "Authorization: Bearer $TOKEN"
curl -X POST "$B/palace?palace=tickets"   -H "Authorization: Bearer $TOKEN"
curl -X POST "$B/palace?palace=alice"     -H "Authorization: Bearer $TOKEN"
```

每个 `palace` 编号是物理隔离的独立后端实例，存查互不干扰；编号经 `sanitize_name` 防路径穿越（`palace=../etc` → 400）。

### 6.3 嵌入模型选择与维度变更

- **中文/多语言**：选 `embeddinggemma`（384d，多语言，新装默认）或 `bgem3`（1024d，BGE-M3，中文/多语言最强，需 GPU）。
- **维度变更必须全量重嵌入**：bgem3 是 1024 维，与 minilm/embeddinggemma 的 384 维不兼容；已有宫殿切 bgem3 必须：

```bash
MEMPALACE_EMBEDDING_MODEL=bgem3 MEMPALACE_BGE_M3_PATH=/models/bge-m3 \
  mempalace repair --yes
```

全新宫殿无此问题。`mempalace palace set-embedder` 可记录/覆盖宫殿嵌入器 identity。

### 6.4 完整 curl 链路示例

```bash
B=http://127.0.0.1:8777
TOKEN="$(openssl rand -hex 32)"   # 实际从环境变量读
export MEMPALACE_REST_TOKEN="$TOKEN"

# 1. 建库
curl -X POST "$B/palace?palace=kb-docs" -H "Authorization: Bearer $TOKEN"

# 2. 存原文
curl -X POST "$B/store" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"entry":"Project Aurora 用 PostgreSQL 16 + pgvector 做向量检索，截止 Q3 2026。",
          "agent_name":"ops","topic":"architecture","palace":"kb-docs"}'

# 3. 语义检索（严召回）
curl "$B/search?q=向量数据库&limit=5&max_distance=0.6&detail=snippet&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"

# 4. 只取元数据（最严，不下发原文）
curl "$B/search?q=架构&palace=kb-docs&detail=meta" -H "Authorization: Bearer $TOKEN"

# 5. 知识图谱查询（注：写 KG 事实目前需 MCP 工具 mempalace_kg_add）
curl "$B/kg?entity=Project+Aurora&direction=both&palace=kb-docs" \
     -H "Authorization: Bearer $TOKEN"
```

### 6.5 Python `requests` 调用片段

```python
import requests

B = "http://127.0.0.1:8777"
H = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}

# 存
requests.post(f"{B}/store", headers=H,
             json={"entry": "Aurora 用 pgvector", "topic": "notes", "palace": "kb-docs"})

# 搜（取片段拼进 RAG prompt）
r = requests.get(f"{B}/search", headers=H, params={
    "q": "向量数据库", "limit": 5, "max_distance": 0.6,
    "detail": "snippet", "snippet_len": 320, "palace": "kb-docs",
}).json()
context = "\n".join(h["text"] for h in r["results"])   # 拼入 prompt

# 查时序事实
facts = requests.get(f"{B}/kg", headers=H, params={
    "entity": "Project Aurora", "direction": "both", "palace": "kb-docs",
}).json()
```

### 6.6 替代接入方式

- **MCP Server（原生）**：Claude Desktop / Cursor / Codex 原生支持 MCP，直接 `claude mcp add mempalace -- mempalace-mcp`，LLM 自动调 `mempalace_search` / `mempalace_kg_query`。适合"AI 自主检索"而非固定 RAG 流水线。
- **Python API（深度集成）**：`from mempalace.searcher import search_memories` 直接调，零网络跳，适合同机集成、自定义重排、嵌入到现有 Python RAG 管线。
- **CLI（脚本/CI）**：`mempalace search "..." --wing myapp` 适合 CI 批跑、shell 脚本、一次性回填。`mempalace sweep`/`mine` 适合定时把新 transcript 回填进宫殿。

---

## 7. 部署方式

### 7.1 uv（推荐）

```bash
uv tool install mempalace        # 隔离环境，装到 PATH
mempalace init ~/projects/myapp
```

### 7.2 pip（venv 内）+ 可选 extras

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install mempalace
```

可选 extras（`pyproject.toml [project.optional-dependencies]`）：

| extra | 说明 |
|---|---|
| `[pgvector]` | `psycopg[binary]`，pgvector 后端客户端驱动 |
| `[gpu]` | `onnxruntime-gpu`，NVIDIA CUDA |
| `[dml]` | `onnxruntime-directml`，Windows AMD/Intel/NVIDIA |
| `[coreml]` | `onnxruntime`，macOS Neural Engine |
| `[extract]` | `striprtf` + `markitdown[docx,pdf,pptx,xlsx]`（`mine --mode extract`，PDF/DOCX/PPTX/XLSX/RTF/EPUB；MarkItDown 需 Python ≥ 3.10） |
| `[spellcheck]` | `autocorrect` |
| `[dev]` | pytest/pytest-cov/pytest-rerunfailures/ruff/hypothesis/pre-commit/mypy |
| `[multilingual]` | 空别名（多语言依赖已在 core） |

装完硬件加速 extra 后设 `MEMPALACE_EMBEDDING_DEVICE=cuda|dml|coreml`（或 `auto`）。

### 7.3 Docker stdio MCP

```bash
# CPU 镜像（默认 bundling extract+spellcheck）
docker build -t mempalace .

# MCP server over stdio（注意 -i，JSON-RPC 需要 stdin）
docker run -i --rm -v mempalace-data:/data mempalace

# 跑 CLI 子命令（挂载宿主目录挖掘）
docker run --rm -v mempalace-data:/data -v /path/to/project:/work mempalace mine /work
docker run --rm -v mempalace-data:/data mempalace search "why GraphQL"

# 自定义 extras
docker build --build-arg EXTRAS="extract,spellcheck" -t mempalace .
```

接入 Claude Code（stdio server）：

```json
{
  "mcpServers": {
    "mempalace": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-v", "mempalace-data:/data", "mempalace"]
    }
  }
}
```

### 7.4 Docker REST Adapter（`haisi-mempalace:rest`）

基于 `haisi-mempalace:latest` + FastAPI/uvicorn 薄层。三种部署模式：

**内网免鉴权（可信网络，推荐）**：

```bash
docker run -d --name mempalace-rest --restart unless-stopped \
  -p 8777:8777 -v mempalace-data:/data \
  -e MEMPALACE_REST_HOST=0.0.0.0 -e MEMPALACE_REST_NO_AUTH=1 \
  haisi-mempalace:rest
```

**带 token / 公网（前置 TLS 反代）**：

```bash
docker run -d --name mempalace-rest --restart unless-stopped \
  -p 127.0.0.1:8777:8777 -v mempalace-data:/data \
  -e MEMPALACE_REST_TOKEN="$(openssl rand -hex 32)" \
  haisi-mempalace:rest
# 然后 Caddy/nginx 反代 127.0.0.1:8777 做 HTTPS + 访问控制
```

**loopback 调试（默认）**：不设 token、绑 `127.0.0.1`，所有端点免鉴权直通（启动打 WARNING）。

### 7.5 Docker Compose

`docker-compose.yml` 定义 `mcp` 服务（stdio）+ `mempalace-data` 卷：

```bash
docker compose build
docker compose run --rm mcp                       # MCP over stdio
docker compose run --rm mcp cli search "GraphQL"  # 一次性 CLI
```

### 7.6 BGE-M3 + GPU（`haisi-mempalace:rest-bgem3`）

中文/多语言最强，需 NVIDIA GPU。需先手动下载 cu128 torch wheel（Blackwell sm_120 仅 cu128 有内核）：

```bash
mkdir -p wheels && cd wheels
wget -O torch-2.11.0+cu128-cp312-cp312-manylinux_2_28_x86_64.whl \
  "https://download-r2.pytorch.org/whl/cu128/torch-2.11.0%2Bcu128-cp312-cp312-manylinux_2_28_x86_64.whl"

# 构建（需先有 haisi-mempalace:rest）
docker build -t haisi-mempalace:rest-bgem3 -f Dockerfile.rest.bgem3 .

# 运行（挂载本地模型只读 + --gpus all）
docker run -d --name mempalace-bgem3 --restart unless-stopped \
  --gpus all -p 8777:8777 -v mempalace-data:/data \
  -v /disk/models/bge-m3:/models/bge-m3:ro \
  -e MEMPALACE_REST_HOST=0.0.0.0 -e MEMPALACE_REST_NO_AUTH=1 \
  -e MEMPALACE_EMBEDDING_MODEL=bgem3 \
  -e MEMPALACE_BGE_M3_PATH=/models/bge-m3 \
  -e MEMPALACE_EMBEDDING_DEVICE=cuda \
  haisi-mempalace:rest-bgem3
```

也可用 `Dockerfile.gpu`（NVIDIA CUDA 基镜像，onnxruntime-gpu，`--gpus all` + `MEMPALACE_EMBEDDING_DEVICE=cuda`）跑 stdio MCP/CLI：

```bash
docker build -f Dockerfile.gpu -t haisi-mempalace:gpu .
docker run -i --rm --gpus all -e MEMPALACE_EMBEDDING_DEVICE=cuda \
  -v mempalace-data:/data haisi-mempalace:gpu
```

### 7.7 外部后端部署

**Qdrant**（默认 `http://localhost:6333`）：

```bash
MEMPALACE_QDRANT_URL=http://localhost:6333 \
  mempalace mine ~/projects/myapp --backend qdrant
# 可选：MEMPALACE_QDRANT_API_KEY / MEMPALACE_QDRANT_NAMESPACE / MEMPALACE_QDRANT_TIMEOUT
```

**pgvector**（默认 `postgresql://localhost:5432/mempalace`，需服务端 `vector` 扩展 + 客户端 `pip install mempalace[pgvector]`）：

```bash
MEMPALACE_PGVECTOR_DSN=postgresql://localhost:5432/mempalace \
  mempalace mine ~/projects/myapp --backend pgvector
# 可选：MEMPALACE_PGVECTOR_NAMESPACE（多租户表前缀隔离）
```

两后端都通过 `supports_namespace_isolation` 能力按 namespace 隔离租户，并写本地 marker 文件防误连。

---

## 8. 配置与环境变量

**配置优先级**（`config.py`）：环境变量 > `~/.mempalace/config.json` > 默认值。`~/.mempalace/config.json` 示例：

```json
{
  "palace_path": "/custom/path/to/palace",
  "collection_name": "mempalace_drawers",
  "backend": "chroma",
  "embedding_model": "embeddinggemma",
  "embedding_device": "auto",
  "entity_languages": ["en"],
  "topic_wings": ["emotions","consciousness","memory","technical","identity","family","creative"],
  "max_backups": 10,
  "hooks": {"auto_save": true, "silent_save": true, "desktop_toast": false, "daemon": false}
}
```

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEMPALACE_PALACE_PATH` | `~/.mempalace/palace`（镜像 `HOME=/data` 时 `/data/.mempalace/palace`） | 宫殿目录；`MEMPAL_PALACE_PATH` 为兼容别名 |
| `MEMPALACE_EMBEDDING_MODEL` | `minilm`（config 无值时回退；onboarding 写 `embeddinggemma` 为新装默认） | `minilm` / `embeddinggemma` / `bgem3` |
| `MEMPALACE_EMBEDDING_DEVICE` | `auto` | `auto` / `cpu` / `cuda` / `coreml` / `dml`；bgem3 强烈建议 `cuda` |
| `MEMPALACE_EMBEDDING_THREADS` | `auto`（= 半逻辑 CPU） | ONNX intra-op 线程上限；`0`/负 = 不限（ORT 默认） |
| `MEMPALACE_BGE_M3_PATH` | `/disk/models/bge-m3` | 仅 `bgem3`：本地 BGE-M3 模型目录 |
| `MEMPALACE_BACKEND` | `chroma` | `chroma` / `sqlite_exact` / `qdrant` / `pgvector` |
| `MEMPALACE_QDRANT_URL` | `http://localhost:6333` | Qdrant 端点 |
| `MEMPALACE_QDRANT_API_KEY` | — | Qdrant API key |
| `MEMPALACE_QDRANT_NAMESPACE` | — | Qdrant collection 命名空间/前缀 |
| `MEMPALACE_QDRANT_TIMEOUT` | `10.0` | Qdrant HTTP 超时（秒） |
| `MEMPALACE_PGVECTOR_DSN` | `postgresql://localhost:5432/mempalace` | Postgres DSN |
| `MEMPALACE_PGVECTOR_NAMESPACE` | — | pgvector 表命名空间/前缀 |
| `MEMPALACE_ENTITY_LANGUAGES` | `en`（逗号分隔） | 实体检测语言，如 `en,pt-br,ru`；`MEMPAL_ENTITY_LANGUAGES` 别名 |
| `MEMPALACE_TOPIC_TUNNEL_MIN_COUNT` | `1` | 跨 wing 主题隧道所需最小重叠主题数 |
| `MEMPALACE_MAX_BACKUPS` | `10` | `mempalace migrate` / `repair max-seq-id` 时间戳备份保留数；`0` = 全留 |
| `MEMPALACE_MAX_CHUNKS_PER_FILE` | 见 cli.py | 单文件 chunk 上限（Windows ONNX bad_alloc 可调低）；`0` = 禁用 |
| `MEMPALACE_HOOKS_AUTO_SAVE` | `true` | hooks 总开关；`false`/`0`/`no` 关闭 |
| `MEMPALACE_HOOKS_DAEMON` | `false` | hooks 是否提交给可选 daemon |
| `MEMPAL_DISABLE_HOOK` | — | Cursor hooks 别名 kill switch |
| `MEMPAL_VERBOSE` | `false` | 开发者：在聊天里显示 diary |
| `MEMPAL_PYTHON` | 自动 | hooks 用的 Python 解释器（默认从 `mempalace-mcp` shebang 解析） |
| `MEMPAL_STATE_DIR` | `~/.mempalace/hook_state/` | Cursor per-conversation 状态目录 |
| `MEMPAL_SAVE_INTERVAL` | `15` | Cursor/Antigravity save hook 触发间隔（人消息数） |
| `MEMPAL_STATE_TTL_DAYS` | `30` | Cursor 状态文件 TTL 清扫 |
| `MEMPAL_CURSOR_SILENT` | `false` | Cursor 静默模式（不发 followup_message） |
| `MEMPALACE_REST_TOKEN` | — | REST bearer token；非 loopback 必填（除非 `NO_AUTH`） |
| `MEMPALACE_REST_NO_AUTH` | — | `1` 关闭 REST 鉴权（仅可信网络） |
| `MEMPALACE_REST_HOST` | `127.0.0.1` | REST 绑定地址；内网 `0.0.0.0` |
| `MEMPALACE_REST_PORT` | `8777` | REST 端口 |
| `MEMPALACE_REST_DEFAULT_DETAIL` | `snippet` | `detail` 参数默认档 |
| `MEMPALACE_PALACES_ROOT` | `/data/palaces` | REST 多宫殿根目录 |
| `MEMPALACE_MCP_HTTP_TOKEN` | — | MCP http 传输可选 bearer token（`/mcp` 强制） |

---

## 9. 使用示例

### 9.1 CLI 快速上手

```bash
uv tool install mempalace
mempalace init ~/projects/myapp --yes --auto-mine   # 初始化 + 自动挖掘
mempalace mine ~/projects/myapp                      # 挖项目文件
mempalace mine ~/.claude/projects/ --mode convos     # 挖 Claude Code 会话
mempalace search "why did we switch to GraphQL" --wing myapp
mempalace wake-up --wing myapp                       # 加载 L0+L1 上下文
mempalace status                                     # 宫殿概览
mempalace sweep ~/.claude/projects/                  # 补消息级挖掘
```

### 9.2 curl REST 全链路

见 [§6.4](#64-完整-curl-链路示例)。

### 9.3 Python requests

见 [§6.5](#65-python-requests-调用片段)。

### 9.4 MCP 接入 Claude Code

```bash
claude mcp add mempalace -- mempalace-mcp
# 或自定义宫殿：
claude mcp add mempalace -- mempalace-mcp --palace /path/to/palace
```

之后 Claude 会在被问及"过去做过/决定过什么"时自动调 `mempalace_search`，取 verbatim 结果回答。

---

## 10. 基准测试

完整方法论与每题结果见 [benchmarks/BENCHMARKS.md](https://github.com/MemPalace/mempalace/blob/main/benchmarks/BENCHMARKS.md)，结果文件提交在 `benchmarks/results_*`。

**LongMemEval — 检索召回 R@5（500 题）：**

| 模式 | R@5 | LLM | 成本/查询 |
|---|---|---|---|
| Raw（语义检索，无启发式、无 LLM） | **96.6%** | 无 | $0 |
| Hybrid v4，held-out 450 题（50 dev 调参，不参与训练） | **98.4%** | 无 | $0 |
| Hybrid v4 + LLM rerank（全 500） | ≥99% | 任意能干模型 | ~$0.001–0.003 |

raw 96.6% 无 API key、无云、无 LLM；hybrid 加关键词加权 + 时间邻近加权 + 偏好模式抽取；held-out 98.4% 是可推广的诚实数字。rerank 从 top-20 候选里用 LLM reader 选最佳，模型无关（Claude Haiku/Sonnet、minimax-m2.7 via Ollama Cloud 均复现）。最后 0.6% 来自检查 3 个具体错答（"teaching to the test"），故不对外标"100%"。

**其他基准摘要：**

| 基准 | 指标 | 分数 | 说明 |
|---|---|---|---|
| LoCoMo（session，top-10，无 rerank） | R@10 | 60.3% | 1,986 题 |
| LoCoMo（hybrid v5，top-10，无 rerank） | R@10 | 88.9% | 同集 |
| ConvoMem（全类目，250 项） | 平均召回 | 92.9% | 每类 50 |
| MemBench（ACL 2025，8,500 项） | R@5 | 80.3% | 全类目 |

> MemPalace 不与 Mem0/Mastra/Hindsight/Supermemory/Zep 做并排对比——它们在不同 split 上发不同指标（检索召回 vs 端到端 QA 准确率不可比）。

**复现：**

```bash
git clone https://github.com/MemPalace/mempalace.git
cd mempalace
uv sync --extra dev   # 或 pip install -e ".[dev]"
# 见 benchmarks/README.md 下载数据集
uv run python benchmarks/longmemeval_bench.py /path/to/longmemeval_s_cleaned.json
# 快速测试（20 题，~30s）：
uv run python benchmarks/longmemeval_bench.py /path/to/longmemeval_s_cleaned.json --limit 20
```

50/450 split 用 `benchmarks/lme_split_50_450.json`（确定性 `seed=42`），所有 BENCHMARKS.md 声明可精确复现。

---

## 11. 参考项目代码与开源文档

### 关键源码文件（仓库内相对路径）

| 文件 | 职责 |
|---|---|
| `mempalace/cli.py` | CLI 子命令派发（init/mine/search/wake-up/repair/daemon/palace...） |
| `mempalace/mcp_server.py` | MCP Server：35 工具 TOOLS dict + stdio/http 传输 |
| `mempalace/config.py` | 配置 + `sanitize_name`/`sanitize_content`/`sanitize_iso_temporal` |
| `mempalace/embedding.py` | 嵌入工厂（minilm/embeddinggemma/bgem3 + 设备 + 线程上限） |
| `mempalace/onboarding.py` | 首次运行交互（mode/people/projects/wings/embedding） |
| `mempalace/searcher.py` | 混合检索（向量 + BM25 + 时间/偏好加权 + max_distance） |
| `mempalace/knowledge_graph.py` | 时序三元组（SQLite + valid_from/valid_to + as_of） |
| `mempalace/dialect.py` | AAAK 压缩 dialect（closet 层） |
| `mempalace/layers.py` | L0-L3 唤醒栈 |
| `mempalace/palace.py` | 宫殿共享操作（get_collection/build_closet_lines/mine_lock） |
| `mempalace/palace_graph.py` | 房间遍历 + 跨 wing 隧道 |
| `mempalace/miner.py` / `mempalace/convo_miner.py` | 项目文件 / 对话挖掘 |
| `mempalace/backends/base.py` | 后端抽象接口（BaseBackend/BaseCollection/PalaceRef/QueryResult） |
| `mempalace/backends/chroma.py` / `sqlite_exact.py` / `qdrant.py` / `pgvector.py` | 四个后端实现 |
| `mempalace/backends/registry.py` | 注册 + entry-point 发现 + resolve_backend_for_palace |
| `examples/rest_adapter.py` | FastAPI REST 适配层（haisi-mempalace:rest 入口） |
| `examples/rest_adapter_API.md` | REST API 中文文档 |
| `docs/CLOSETS.md` | closet 索引层概述 |
| `docs/schema.sql` | KG SQLite schema |
| `benchmarks/BENCHMARKS.md` | 基准方法论与结果 |
| `Dockerfile` / `Dockerfile.rest` / `Dockerfile.rest.bgem3` / `Dockerfile.gpu` | 四种镜像 |
| `docker-compose.yml` / `docker-entrypoint.sh` / `mcp.json` | Compose + 入口 + 最简 MCP 配置 |
| `pyproject.toml` | 包元数据 + 依赖 + extras + entry points |

### 官方文档 URL

- 主站：[mempalaceofficial.com](https://mempalaceofficial.com)
- Getting Started：[/guide/getting-started](https://mempalaceofficial.com/guide/getting-started.html)
- CLI Reference：[/reference/cli](https://mempalaceofficial.com/reference/cli.html)
- MCP Tools Reference：[/reference/mcp-tools](https://mempalaceofficial.com/reference/mcp-tools.html)
- Python API：[/reference/python-api](https://mempalaceofficial.com/reference/python-api.html)
- The Palace（概念）：[/concepts/the-palace](https://mempalaceofficial.com/concepts/the-palace.html)
- Knowledge Graph（概念）：[/concepts/knowledge-graph](https://mempalaceofficial.com/concepts/knowledge-graph.html)
- Hooks 指南：[/guide/hooks](https://mempalaceofficial.com/guide/hooks.html)
- Cursor Hooks：[/guide/cursor-hooks](https://mempalaceofficial.com/guide/cursor-hooks.html)
- Claude Code Retention Checklist：[/guide/claude-code-retention](https://mempalaceofficial.com/guide/claude-code-retention.html)
- Antigravity 指南：[/guide/antigravity](https://mempalaceofficial.com/guide/antigravity.html)
- GitHub 仓库：[github.com/MemPalace/mempalace](https://github.com/MemPalace/mempalace)
- PyPI：[pypi.org/project/mempalace](https://pypi.org/project/mempalace/)
- 基准：[benchmarks/BENCHMARKS.md](https://github.com/MemPalace/mempalace/blob/main/benchmarks/BENCHMARKS.md)
- CHANGELOG：[CHANGELOG.md](https://github.com/MemPalace/mempalace/blob/main/CHANGELOG.md)
- 历史与防伪声明：[docs/HISTORY.md](https://github.com/MemPalace/mempalace/blob/main/docs/HISTORY.md)
- 贡献指南：[CONTRIBUTING.md](https://github.com/MemPalace/mempalace/blob/main/CONTRIBUTING.md)
- 项目使命：[MISSION.md](https://github.com/MemPalace/mempalace/blob/main/MISSION.md)
- 路线图：[ROADMAP.md](https://github.com/MemPalace/mempalace/blob/main/ROADMAP.md)
- 安全策略：[SECURITY.md](https://github.com/MemPalace/mempalace/blob/main/SECURITY.md)

---

## 12. 常用命令速查与健康检查

### 12.1 健康检查

#### REST Adapter 存活探针

```bash
# /healthz — 未鉴权存活探针（供反向代理/编排器用），返回 "ok"
curl http://127.0.0.1:8777/healthz

# / — 自描述根路径，列出端点、detail 档位与鉴权状态
curl http://127.0.0.1:8777/

# 带 token 的 search 自检（确认检索链路通）
curl "http://127.0.0.1:8777/search?q=health+check&limit=1&detail=meta" \
  -H "Authorization: Bearer $MEMPALACE_REST_TOKEN"
```

`/healthz` 是唯一的未鉴权端点（`include_in_schema=False`，不在 OpenAPI schema 中暴露）；其余端点在非 loopback 绑定时强制 Bearer token（`MEMPALACE_REST_TOKEN`），未设 token 且非 loopback 时 fail-closed 拒绝启动。

#### 宫殿状态

```bash
# 显示已归档内容概览（wing/room/drawer 计数等）
mempalace status

# 指定后端查看状态
mempalace status --backend sqlite_exact

# daemon 运行状态与最近作业
mempalace daemon status
mempalace daemon jobs --limit 10
```

#### 嵌入器一致性自检

```bash
# palace 的 embedder identity 与当前配置不一致时，搜索会报
# EmbedderIdentityMismatchError；用 palace 子命令对齐（仅记录 identity，不改已存向量）
mempalace palace set-embedder --model embeddinggemma

# 切换嵌入模型（维度变更）后，必须全量重建索引：
MEMPALACE_EMBEDDING_MODEL=bgem3 MEMPALACE_BGE_M3_PATH=/models/bge-m3 \
  mempalace repair --yes
```

`mempalace repair` 默认 `--mode legacy`（经 chromadb 客户端全量重建）；chromadb 客户端打不开集合时改用 `--mode from-sqlite` 直接读 `chroma.sqlite3` 重建。`mempalace repair-status` 只做只读的 HNSW 容量健康检查（sqlite vs HNSW 计数对比）。

### 12.2 Docker 速查

#### 构建镜像

```bash
# CPU 镜像（默认，含 extract + spellcheck extras）
docker build -t mempalace .

# GPU 镜像（CUDA 加速嵌入）
docker build -f Dockerfile.gpu -t haisi-mempalace:gpu .

# REST Adapter 镜像
docker build -f Dockerfile.rest -t haisi-mempalace:rest .

# BGE-M3 + GPU 镜像（多语言最强，需挂载本地模型）
docker build -f Dockerfile.rest.bgem3 -t haisi-mempalace:rest-bgem3 .

# 自定义 bundled extras
docker build --build-arg EXTRAS="extract,spellcheck" -t mempalace .
```

#### 运行

```bash
# MCP server over stdio（注意 -i 标志，JSON-RPC 需 stdin）
docker run -i --rm -v mempalace-data:/data mempalace

# 一次性 CLI 命令
docker run --rm -v mempalace-data:/data mempalace search "why GraphQL"
docker run --rm -v mempalace-data:/data -v /path/to/project:/work mempalace mine /work

# GPU 加速嵌入
docker run --rm --gpus all -v mempalace-data:/data haisi-mempalace:gpu

# REST Adapter（后台运行）
docker run -d --name mempalace-rest -p 8777:8777 \
  -v mempalace-data:/data \
  -e MEMPALACE_REST_TOKEN="$(openssl rand -hex 32)" \
  haisi-mempalace:rest
```

#### Docker Compose

```bash
# 构建
docker compose build

# 交互式 MCP（stdio，不要用 -d 后台运行）
docker compose run --rm mcp

# 一次性 CLI
docker compose run --rm mcp cli search "GraphQL"
```

`docker-compose.yml` 的 `mcp` 服务用命名卷 `mempalace-data` 挂在 `/data`，跨运行保留 palace、config 与缓存的嵌入模型。

#### 停止与日志

```bash
# 停止并删除 REST 容器
docker stop mempalace-rest
docker rm mempalace-rest

# 查看日志（-f follow，--tail 取最后 N 行）
docker logs mempalace-rest
docker logs -f mempalace-rest
docker logs --tail 100 mempalace-rest

# 清理卷（谨慎！会删除整个宫殿数据）
docker volume rm mempalace-data
```

### 12.3 MCP Server 速查

```bash
# stdio 传输（默认，给 Claude Code/Cursor 等用）
mempalace-mcp

# http 传输（默认 host 127.0.0.1，默认 port 8765）
mempalace-mcp --transport http --host 127.0.0.1 --port 8765

# 带 token 的 http（非 loopback 绑定必须设 token）
MEMPALACE_MCP_HTTP_TOKEN="$(openssl rand -hex 32)" \
  mempalace-mcp --transport http

# 自检
curl http://127.0.0.1:8765/healthz
```

### 12.4 命令速查表

| 任务 | 命令 |
|---|---|
| REST 存活探针 | `curl http://127.0.0.1:8777/healthz` |
| REST 根自描述 | `curl http://127.0.0.1:8777/` |
| 宫殿状态 | `mempalace status` |
| daemon 状态 | `mempalace daemon status` |
| 挖掘项目文件 | `mempalace mine ~/projects/myapp` |
| 挖掘对话 | `mempalace mine ~/.claude/projects/ --mode convos` |
| 语义检索 | `mempalace search "why GraphQL"` |
| 唤醒上下文 | `mempalace wake-up` |
| 全量重建索引 | `MEMPALACE_EMBEDDING_MODEL=bgem3 mempalace repair --yes` |
| 嵌入器对齐 | `mempalace palace set-embedder --model embeddinggemma` |
| HNSW 容量检查 | `mempalace repair-status` |
| 构建 CPU 镜像 | `docker build -t mempalace .` |
| 构建 GPU 镜像 | `docker build -f Dockerfile.gpu -t haisi-mempalace:gpu .` |
| 构建 REST 镜像 | `docker build -f Dockerfile.rest -t haisi-mempalace:rest .` |
| 运行 MCP 容器 | `docker run -i --rm -v mempalace-data:/data mempalace` |
| 运行 REST 容器 | `docker run -d -p 8777:8777 -v mempalace-data:/data haisi-mempalace:rest` |
| 查看容器日志 | `docker logs -f mempalace-rest` |
| 停止容器 | `docker stop mempalace-rest` |
| MCP http 自检 | `curl http://127.0.0.1:8765/healthz` |