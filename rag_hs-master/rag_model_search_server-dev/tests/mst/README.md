# 对外 API 连通性测试

本目录下的 `*_connectivity.py` 用于验证 `server/api.py` 中注册的所有 `/kw/...` 对外路由的连通性与基本可用性，作为回归基线。测试目标为 WSL Ubuntu Docker 容器 `haisi_rag_models_server_dev`，目标地址 `http://127.0.0.1:7861/kw`（来自 `server/utils.py:api_address()`）。

## 运行前置

1. **服务可达**：容器内 `http://127.0.0.1:7861/kw/server/configs` 必须返回 HTTP 200。`conftest.py` 的 `ensure_server_up`（session 级 autouse）会在用例开始前探测该地址，不可达时全部用例以 `SKIPPED`（原因：服务未启动）结束，不报 `FAILED`。
2. **Python 依赖**：`pytest`、`requests`（容器内已预装）。
3. **测试知识库**：`test_kb` session 级 fixture 会自动创建 `kb_api_test_tmp` 知识库并上传一个临时 txt，session 结束自动清理。无需手动准备数据。

## 运行方式

进入容器后在项目根目录（`/mnt/d/Code/haisi/haisi_rag_models_server`）执行：

```bash
# 只跑非 LLM 依赖测试（默认，不调用火山引擎）
pytest tests/mst/*_connectivity.py -v

# 跑全部用例（含 LLM 依赖，会真实调用火山引擎 doubao）
pytest tests/mst/*_connectivity.py -v --run-llm

# 只跑某一类
pytest tests/mst/test_server_connectivity.py -v
pytest tests/mst/test_kb_connectivity.py -v

# 仅收集不执行（验证用例是否能被正确收集）
pytest tests/mst/*_connectivity.py --collect-only
```

> 本目录仅含 `*_connectivity.py` 连通性用例（loader 测试已清理），`pytest tests/mst/` 与通配模式等价。

### `--run-llm` 用法

默认所有 LLM 依赖用例（`chat`、`next_question`、`knowledge_base_chat`、`file_chat`、`completion`、3 个 summary 用例）在签名中请求 `require_llm` fixture，未传 `--run-llm` 时自动 `SKIPPED`，不实际调用火山引擎。加 `--run-llm` 后才会运行，**会真实产生 doubao 调用费用，请确认后再启用**。

## 测试文件覆盖范围

| 测试文件 | 覆盖路由 | 用例数 | 默认运行 |
|---|---|---|---|
| `test_server_connectivity.py` | `/kw/server/configs`、`/kw/server/get_prompt_template` | 2 | ✅ |
| `test_llm_model_connectivity.py` | `/kw/llm_model/list_running_models`、`list_config_models`、`get_model_config`、`stop`、`change` | 5 | ✅ |
| `test_kb_connectivity.py` | `/kw/knowledge_base/list_knowledge_bases`、`create_knowledge_base`、`delete_knowledge_base`、`list_files` | 3 | ✅ |
| `test_kb_docs_connectivity.py` | `/kw/knowledge_base/search_docs`、`batch_search_docs`、`download_doc`、`delete_docs`、`update_info`（上传走 fixture） | 5（3 个 skip） | ✅ |
| `test_kb_summary_connectivity.py` | `/kw/knowledge_base/kb_summary_api/summary_file_to_vector_store`、`summary_doc_ids_to_vector_store`、`recreate_summary_vector_store` | 3 | ⏭ 需 `--run-llm` |
| `test_chat_connectivity.py` | `/kw/chat/chat`、`next_question`、`feedback`、`knowledge_base_chat`、`file_chat` | 5（4 个需 `--run-llm`） | 部分 |
| `test_other_connectivity.py` | `/kw/other/embed_texts`、`/kw/other/completion` | 2（completion 需 `--run-llm`） | 部分 |

合计 25 个用例。最近一次运行（未启用 `--run-llm`）：**14 passed, 11 skipped, 0 failed**。

SSE 接口（`chat`、`next_question`、`knowledge_base_chat`、`summary_file_to_vector_store`、`recreate_summary_vector_store`、`completion`）使用 `stream=True` 并解析 `data:` 行，见各文件内 `_parse_sse` / `_sse_post` helper。

## 已知跳过/未覆盖路由及原因

详见 [question.md](./question.md)。简表：

| 类别 | 路由 | 原因 |
|---|---|---|
| **未覆盖** | `upload_docs_use_path`、`batch_upload_docs`、`update_docs`、`update_docs_use_path`、`update_docs_by_id`、`recreate_vector_store`、`upload_temp_docs` | 需本地文件路径或大数据量，连通性可由 upload/search 间接验证 |
| **skip（embedding 缺口下游）** | `search_docs`、`batch_search_docs`、`download_doc` | Q1/Q2：`embed_texts` 不支持在线 embedding 模型，`EmbeddingsFunAdapter.embed_query()` 级联失败 |
| **skip（需 LLM）** | `chat`、`next_question`、`knowledge_base_chat`、`file_chat`、`completion`、3 个 summary | 默认不调用火山引擎 |
| **断言 code=500（已知缺口）** | `embed_texts` 带 `qwen3-embedding-api` | Q1：`embed_texts` 仅服务本地模型，不路由到 `Qwen3Embedding` |

## 修复记录（无需用户决策）

运行测试期间发现并修复的 3 个 server 端 bug（详见 [question.md](./question.md) 一节）：

1. `server/utils.py:list_embed_models()`：`MODEL_PATH.embed_model` 为 None 时 TypeError。
2. `server/utils.py:get_prompt_template()`：`prompt_config` 模块名错误（应为 `prompt_loader`）。
3. `server/db/repository/message_repository.py:feedback_message_to_db()`：`m` 为 None 时 AttributeError。
