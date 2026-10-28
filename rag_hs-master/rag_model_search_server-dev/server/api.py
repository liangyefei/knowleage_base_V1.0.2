import argparse
import os
import sys
from typing import Literal, Optional

import nltk
import uvicorn
from fastapi import Body
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import RedirectResponse

sys.path.append(os.path.dirname(os.path.dirname(__file__)))

from configs import NLTK_DATA_PATH, OPEN_CROSS_DOMAIN, VERSION
from server.embeddings_api import embed_texts_endpoint
from server.utils import (
    BaseResponse,
    FastAPI,
    ListResponse,
    MakeFastAPIOffline,
    get_prompt_template,
    get_server_configs,
)

nltk.data.path = [NLTK_DATA_PATH] + nltk.data.path


async def document():
    return RedirectResponse(url="/kw/docs")


async def health():
    """健康检查端点，供 k8s liveness/readiness probe 使用。

    pod 假活（API 子进程崩溃但主进程仍在 sleep）时此端点不响应，
    k8s liveness probe 失败 → 重启 pod → poller 重新初始化（配合退避重连自愈）。
    """
    return BaseResponse(code=200, msg="healthy", data={"version": VERSION})


# ==================== 运行模式开关 ====================
# HAISI_RUN_MODE 取值：
#   - 未设置 / "all"  ：兼容模式，挂载全部路由（向后兼容现有单进程开发/部署）
#   - "lite"          ：等价于 all（历史 lite 模式不裁剪路由，仅控制模型加载为在线 API）
#   - "ingest"        ：仅挂载入库路由（写路径 + 摘要），由 startup.py 启动 KafkaCommServer
#   - "qa"            ：仅挂载问答路由（对话 + 检索 + LLM 模型管理 + file_chat 含 upload_temp_docs），startup.py 不启动 KafkaCommServer
def _enable_qa(run_mode: str) -> bool:
    return run_mode in (None, "qa", "all", "lite")


def _enable_ingest(run_mode: str) -> bool:
    return run_mode in (None, "ingest", "all", "lite")


def create_app(run_mode: str = None):
    # factory 模式下 create_app() 无参，run_mode 通过环境变量传递
    if run_mode is None:
        run_mode = os.environ.get("HAISI_RUN_MODE") or None

    # 每个 worker 子进程都需要独立设置 httpx 配置（进程级配置）；
    # 统一在 create_app 内调用，覆盖单 worker 与多 worker 两种模式
    from server.utils import set_httpx_config
    set_httpx_config()

    app = FastAPI(
            title="HaiSI RAG API Server",
            description=(
                "海思芯片知识库问答服务（HaiSI RAG Server）\n\n"
                "面向海思系列芯片（如 Hi3798MV310、Hi3516CV610 等）的检索增强生成（RAG）服务，\n"
                "提供芯片资料知识库管理、文档向量化、多轮对话问答、芯片标签路由检索、文件对话、\n"
                "文本摘要、LLM 模型管理及文本向量化等能力。\n\n"
                "- 知识库对话：基于 Milvus 向量库 + LAC 分词 + 芯片型号路由的精准检索与问答\n"
                "- 文档管理：支持 PDF/Word/PPT/Markdown/代码等多种芯片资料格式的上传与切分\n"
                "- 多轮对话：基于 PostgreSQL 的会话历史持久化，支持流式输出\n"
                "- 模型管理：在线 LLM、Embedding、Reranker 模型的配置与切换\n\n"
                f"运行模式（HAISI_RUN_MODE）：{run_mode or 'all（兼容模式，全路由）'}"
            ),
            version=VERSION,
            openapi_url=None,
            docs_url=None,
            redoc_url=None,
    )
    MakeFastAPIOffline(app, docs_url=None, redoc_url=None)
    # Add CORS middleware to allow all origins
    # 在config.py中设置OPEN_DOMAIN=True，允许跨域
    # set OPEN_DOMAIN=True in config.py to allow cross-domain
    if OPEN_CROSS_DOMAIN:
        app.add_middleware(
                CORSMiddleware,
                allow_origins=["*"],
                allow_credentials=True,
                allow_methods=["*"],
                allow_headers=["*"],
        )
    mount_app_routes(app, run_mode=run_mode)

    return app


def mount_app_routes(app: FastAPI, run_mode: Optional[str] = None):
    # ---------- 两端都暴露：健康检查 / 文档入口 ----------
    _CHAT_KEY = 'Chat 对话'
    _LLM_MGMT_KEY = 'LLM Model Management 模型管理'
    app.get("/kw/health",
            response_model=BaseResponse,
            summary="健康检查（k8s liveness/readiness probe 用）")(health)

    app.get("/kw/",
            response_model=BaseResponse,
            summary="接口文档入口：跳转至 Swagger 文档")(document)

    # ---------- 服务器配置（两端） ----------
    app.post("/kw/server/configs",
             tags=["Server State 服务状态"],
             summary="获取服务器原始配置信息（脱敏）",
             )(get_server_configs)

    @app.post("/kw/server/get_prompt_template",
              tags=["Server State 服务状态"],
              summary="获取服务器配置的 Prompt 模板")
    def get_server_prompt_template(
            type: Literal["llm_chat", "knowledge_base_chat"] = Body("llm_chat",
                                                                    description="模板类型，可选值：llm_chat（普通对话），knowledge_base_chat（知识库对话）"),  # pylint: disable=line-too-long
            name: str = Body("default", description="模板名称，默认 default"),
    ) -> str:
        return get_prompt_template(type=type, name=name)

    # ---------- 文本向量化（两端：入库内部直调 common 的 embed_texts，此处为对外 HTTP 端点） ----------
    app.post("/kw/other/embed_texts",
             tags=["Other 其他"],
             summary="文本向量化（支持本地 Embedding 模型，用于海思资料检索）",
             )(embed_texts_endpoint)

    # ---------- 知识库路由（内部按 run_mode 再分流读/写） ----------
    mount_knowledge_routes(app, run_mode=run_mode)
    # ---------- 摘要路由（仅 ingest） ----------
    mount_filename_summary_routes(app, run_mode=run_mode)

    # ---------- 问答路由（仅 qa / 兼容模式）：对话 + 补全 + LLM 模型管理 ----------
    if _enable_qa(run_mode):
        from server.chat.chat import chat
        from server.chat.completion import completion
        from server.chat.feedback import chat_feedback
        from server.chat.next_question import next_question
        from server.chat.question_classify import question_classify
        from server.llm_api import (
            change_llm_model,
            get_model_config,
            list_config_models,
            list_running_models,
            stop_llm_model,
        )

        app.post("/kw/chat/chat",
                 tags=[_CHAT_KEY],
                 summary="与 LLM 模型对话（基于 LLMChain，支持海思芯片技术问题咨询）",
                 )(chat)

        app.post("/kw/chat/next_question",
                 tags=["Guess Next Question 猜你想问"],
                 summary="根据上一个问题预测下一个问题（基于 LLMChain，用于海思芯片问答引导）",
                 )(next_question)

        app.post("/kw/chat/feedback",
                 tags=[_CHAT_KEY],
                 summary="对 LLM 模型的回答进行评分反馈",
                 )(chat_feedback)

        app.post("/kw/chat/question_classify",
                 tags=[_CHAT_KEY],
                 response_model=BaseResponse,
                 summary="用户问题分类（结合用户问题和 AI 回答，利用 LLM 对问题进行分类）",
                 )(question_classify)

        app.post("/kw/other/completion",
                 tags=["Other 其他"],
                 summary="LLM 模型文本补全（基于 LLMChain）",
                 )(completion)

        app.post("/kw/llm_model/list_running_models",
                 tags=[_LLM_MGMT_KEY],
                 summary="列出当前已加载（在线）的 LLM 模型",
                 )(list_running_models)

        app.post("/kw/llm_model/list_config_models",
                 tags=[_LLM_MGMT_KEY],
                 summary="列出 configs 中已配置的 LLM 模型",
                 )(list_config_models)

        app.post("/kw/llm_model/get_model_config",
                 tags=[_LLM_MGMT_KEY],
                 summary="获取指定 LLM 模型的合并配置（脱敏）",
                 )(get_model_config)

        app.post("/kw/llm_model/stop",
                 tags=[_LLM_MGMT_KEY],
                 summary="停止指定的 LLM 模型（在线 API 模型无需停止）",
                 )(stop_llm_model)

        app.post("/kw/llm_model/change",
                 tags=[_LLM_MGMT_KEY],
                 summary="切换指定的 LLM 模型（在线 API 模型请改配置重启）",
                 )(change_llm_model)


def mount_knowledge_routes(app: FastAPI, run_mode: Optional[str] = None):
    _CHAT_KEY = 'Chat 对话'
    _KB_MGMT_KEY = 'Knowledge Base Management 知识库管理'
    from server.knowledge_base.kb_api import create_kb, delete_kb, list_kbs
    from server.knowledge_base.kb_doc_api import (
        DocumentWithVSId,
        archive_docs,
        batch_search_docs,
        batch_upload_docs,
        delete_docs,
        download_doc,
        get_doc_by_filename,
        list_files,
        recreate_vector_store,
        search_docs,
        update_docs,
        update_docs_by_id,
        update_docs_use_path,
        update_info,
        upload_docs,
        upload_docs_use_path,
        upload_project,
    )

    # ---------- QA 子集：只读 / 查询 / 对话 ----------
    if _enable_qa(run_mode):
        # knowledge_base_chat（单库问答）已下线，统一走 multi_knowledge_base_chat（多库并发检索）
        # Phase 2 A 方案：file_chat 留 QA（只查共享 Milvus temp_{id} collection，不做解析）；
        # upload_temp_docs 移 Ingestion 块（带解析栈写入共享 Milvus），qa 镜像可瘦身
        from server.chat.file_chat import file_chat
        from server.chat.multi_knowledge_base_chat import multi_knowledge_base_chat

        app.post("/kw/chat/multi_knowledge_base_chat",
                 tags=[_CHAT_KEY],
                 summary="与多个知识库对话（并发检索多个芯片资料库，合并上下文做 RAG 问答）")(multi_knowledge_base_chat)

        # 用户长期记忆管理（MemMachine 用户画像）
        from server.memory.memory_api import delete_user_memories, list_user_memories
        app.get("/kw/memory/list",
                tags=[_CHAT_KEY],
                response_model=BaseResponse,
                summary="列出用户画像记忆（习惯/爱好/角色，MemMachine 长期记忆）"
                )(list_user_memories)
        app.post("/kw/memory/delete",
                 tags=[_CHAT_KEY],
                 response_model=BaseResponse,
                 summary="删除用户画像记忆（校验归属）"
                 )(delete_user_memories)

        app.post("/kw/chat/file_chat",
                 tags=[_KB_MGMT_KEY],
                 summary="与临时上传文件对话（无需入库，适合一次性芯片资料问答）"
                 )(file_chat)

        app.post("/knowledge_base/get_doc_by_filename", tags=[_KB_MGMT_KEY],
                 summary="文件分块查询")(get_doc_by_filename)

        app.get("/kw/knowledge_base/list_knowledge_bases",
                tags=[_KB_MGMT_KEY],
                response_model=ListResponse,
                summary="获取知识库列表（如 hi3798mv310、hi3516cv610 等）")(list_kbs)

        app.get("/kw/knowledge_base/list_files",
                tags=[_KB_MGMT_KEY],
                response_model=ListResponse,
                summary="获取指定知识库内的文件列表"
                )(list_files)

        app.post("/kw/knowledge_base/batch_search_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=list,
                 summary="跨多个知识库批量检索（如同时检索多款芯片资料库）"
                 )(batch_search_docs)

        app.post("/kw/knowledge_base/search_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=list[DocumentWithVSId],
                 summary="在指定知识库中检索相关文档"
                 )(search_docs)

    # ---------- Ingestion 子集：写 / 生命周期 / 入库 ----------
    if _enable_ingest(run_mode):
        app.post("/kw/knowledge_base/create_knowledge_base",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="创建知识库（用于承载海思芯片资料文档）"
                 )(create_kb)

        app.post("/kw/knowledge_base/delete_knowledge_base",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="删除知识库（含向量库与文档源文件）"
                 )(delete_kb)

        app.post("/kw/knowledge_base/update_docs_by_id",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="按文档 ID 批量更新知识库文档内容"
                 )(update_docs_by_id)

        app.post("/kw/knowledge_base/upload_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="上传芯片资料文件到知识库，并/或进行向量化"
                 )(upload_docs)

        app.post("/kw/knowledge_base/upload_docs_use_path",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="按文件路径加载芯片资料到知识库，并/或进行向量化"
                 )(upload_docs_use_path)

        app.post("/kw/knowledge_base/batch_upload_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="按文件夹路径批量入库芯片资料（数据量大时处理时间较长，请耐心等待）"
                 )(batch_upload_docs)

        app.post("/kw/knowledge_base/upload_project",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="上传整个项目（zip）：代码文件进 dual-mcp 建索引，文档文件走向量化入库"
                 )(upload_project)

        app.post("/kw/knowledge_base/delete_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="删除知识库内指定的芯片资料文件"
                 )(delete_docs)

        app.post("/kw/knowledge_base/archive_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="归档文档（只删除向量数据，不删磁盘文件和数据库记录）"
                 )(archive_docs)

        app.post("/kw/knowledge_base/update_info",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="更新知识库介绍（用于 Agent 选择知识库时的描述）"
                 )(update_info)

        app.post("/kw/knowledge_base/update_docs",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="更新知识库内已有文件（重新切分并向量化）"
                 )(update_docs)

        app.post("/kw/knowledge_base/update_docs_use_path",
                 tags=[_KB_MGMT_KEY],
                 response_model=BaseResponse,
                 summary="按文件路径更新知识库内已有文件（重新切分并向量化）"
                 )(update_docs_use_path)

        app.get("/kw/knowledge_base/download_doc",
                tags=[_KB_MGMT_KEY],
                summary="下载知识库内的芯片资料源文件（支持浏览器预览）")(download_doc)

        app.post("/kw/knowledge_base/recreate_vector_store",
                 tags=[_KB_MGMT_KEY],
                 summary="根据 content 目录重建向量库（流式输出处理进度）"
                 )(recreate_vector_store)

        # Phase 2 A 方案：upload_temp_docs 由 Ingestion 提供（带解析栈），写入共享 Milvus temp_{id} collection，
        # 供 QA 侧 file_chat 跨 Pod 查询（QA 镜像可去除 document_loaders/text_splitter/VLM 依赖）
        from server.chat.file_chat import upload_temp_docs
        app.post("/kw/knowledge_base/upload_temp_docs",
                 tags=[_KB_MGMT_KEY],
                 summary="上传文件到临时目录（用于文件对话，不持久化到知识库）"
                 )(upload_temp_docs)

        # 文件级多版本管理路由。守卫式导入：qa 镜像的 kb_doc_api 无这两个函数
        # （版本功能只挂在 ingestion 的热修 kb_doc_api.py），import 失败静默跳过，
        # 使 qa/ingestion 可共用本 CM api.py 而不 CrashLoop
        try:
            from server.knowledge_base.kb_doc_api import list_file_versions, rollback_file_version
            app.get("/kw/knowledge_base/file_versions",
                    tags=[_KB_MGMT_KEY],
                    response_model=BaseResponse,
                    summary="列出文件的历史版本（文件级多版本管理）"
                    )(list_file_versions)
            app.post("/kw/knowledge_base/file_rollback",
                     tags=[_KB_MGMT_KEY],
                     response_model=BaseResponse,
                     summary="回滚文件到历史版本（恢复内容+重向量化/重索引，登记为新版本）"
                     )(rollback_file_version)
        except ImportError:
            pass

        # 冲突检测任务（手工触发）：文件 vs 知识库既有内容，C1-C5 冲突判定。
        # 守卫式导入同上，qa/ingestion 共用本 api.py 不 CrashLoop
        try:
            from server.knowledge_base.kb_conflict_api import (
                create_conflict_detection_task, create_conflict_detection_batch_task,
                get_conflict_detection_task, get_conflict_detection_task_results,
                list_conflict_detection_tasks,
                get_conflict_detection_auto_switch, set_conflict_detection_auto_switch)
            app.post("/kw/conflict_detection/tasks",
                     tags=[_KB_MGMT_KEY],
                     response_model=BaseResponse,
                     summary="创建冲突检测任务（异步单文件：文件内容 vs 知识库既有内容，LLM 判定 C1-C5 冲突）"
                     )(create_conflict_detection_task)
            app.post("/kw/conflict_detection/batch_tasks",
                     tags=[_KB_MGMT_KEY],
                     response_model=BaseResponse,
                     summary="创建批量冲突检测任务（异步多文件：一个任务覆盖多个文件，结果按文件分组）"
                     )(create_conflict_detection_batch_task)
            app.get("/kw/conflict_detection/tasks/{task_id}",
                    tags=[_KB_MGMT_KEY],
                    response_model=BaseResponse,
                    summary="查询冲突检测任务状态、进度与冲突结果概要"
                    )(get_conflict_detection_task)
            app.get("/kw/conflict_detection/tasks/{task_id}/results",
                    tags=[_KB_MGMT_KEY],
                    response_model=BaseResponse,
                    summary="查询冲突检测任务结果明细（按文件分组，file_path 参数可过滤单文件）"
                    )(get_conflict_detection_task_results)
            app.get("/kw/conflict_detection/tasks",
                    tags=[_KB_MGMT_KEY],
                    response_model=BaseResponse,
                    summary="冲突检测任务列表（分页，按状态筛选）"
                    )(list_conflict_detection_tasks)
            app.get("/kw/conflict_detection/auto_switch",
                    tags=[_KB_MGMT_KEY],
                    response_model=BaseResponse,
                    summary="查询入库自动冲突检测开关（按知识库，默认关；不带参数返回全部）"
                    )(get_conflict_detection_auto_switch)
            app.post("/kw/conflict_detection/auto_switch",
                     tags=[_KB_MGMT_KEY],
                     response_model=BaseResponse,
                     summary="设置入库自动冲突检测开关（按知识库，供前端页面调用，默认关）"
                     )(set_conflict_detection_auto_switch)
        except ImportError:
            pass



def mount_filename_summary_routes(app: FastAPI, run_mode: str = None):
    _KNOWLEDGESUMMARYMANAGEMENT_KEY = 'Knowledge Summary Management 知识摘要管理'
    if not _enable_ingest(run_mode):
        return
    from server.knowledge_base.kb_summary_api import (
        recreate_summary_vector_store,
        summary_doc_ids_to_vector_store,
        summary_file_to_vector_store,
    )

    app.post("/kw/knowledge_base/kb_summary_api/summary_file_to_vector_store",
             tags=[_KNOWLEDGESUMMARYMANAGEMENT_KEY],
             summary="按文件名生成知识库摘要（用于海思长文档的摘要检索）"
             )(summary_file_to_vector_store)
    app.post("/kw/knowledge_base/kb_summary_api/summary_doc_ids_to_vector_store",
             tags=[_KNOWLEDGESUMMARYMANAGEMENT_KEY],
             summary="按文档 ID 批量生成知识库摘要",
             response_model=BaseResponse,
             )(summary_doc_ids_to_vector_store)
    app.post("/kw/knowledge_base/kb_summary_api/recreate_summary_vector_store",
             tags=[_KNOWLEDGESUMMARYMANAGEMENT_KEY],
             summary="重建知识库全部文件摘要（流式输出处理进度）"
             )(recreate_summary_vector_store)


def run_api(app, host, port, **kwargs):
    if kwargs.get("ssl_keyfile") and kwargs.get("ssl_certfile"):
        uvicorn.run(app,
                    host=host,
                    port=port,
                    ssl_keyfile=kwargs.get("ssl_keyfile"),
                    ssl_certfile=kwargs.get("ssl_certfile"),
                    )
    else:
        uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(prog='langchain-ChatGLM',
                                     description='About langchain-ChatGLM, local knowledge based ChatGLM with langchain'
                                                 ' ｜ 基于本地知识库的 ChatGLM 问答')
    parser.add_argument("--host", type=str, default="0.0.0.0")
    parser.add_argument("--port", type=int, default=12345)
    parser.add_argument("--ssl_keyfile", type=str)
    parser.add_argument("--ssl_certfile", type=str)
    args = parser.parse_args()
    args_dict = vars(args)

    _app = create_app()

    run_api(_app,
            host=args.host,
            port=args.port,
            ssl_keyfile=args.ssl_keyfile,
            ssl_certfile=args.ssl_certfile,
            )
