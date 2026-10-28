# pylint: disable=redefined-outer-name
# 该文件封装了对api.py的请求，可以被不同的webui使用
# 通过ApiRequest和AsyncApiRequest支持同步/异步调用

import contextlib
import json as _json
import os
from io import BytesIO
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple, Union

import httpx

# 此处导入的配置为发起请求（如WEBUI）机器上的配置，主要用于为前端设置默认值。分布式部署时可以与服务器上的不同
from configs import (
    CHUNK_SIZE,
    DEFAULT_VS_TYPE,
    EMBEDDING_MODEL,
    HTTPX_DEFAULT_TIMEOUT,
    INGESTION_API_URL,
    LLM_MODELS,
    OVERLAP_SIZE,
    SCORE_THRESHOLD,
    TEMPERATURE,
    VECTOR_SEARCH_TOP_K,
    ZH_LABEL_ENHANCE,
    ZH_TITLE_ENHANCE,
    log_verbose,
    logger,
)
from server.utils import api_address, get_httpx_client, set_httpx_config
from server._http_constants import HTTP_OK, HTTP_METHOD_GET, HTTP_METHOD_POST

set_httpx_config()


class ApiRequest:
    """
    api.py调用的封装（同步模式）,简化api调用方式
    """

    # Phase 2 务实拆分：入库路由（写/生命周期/解析，需解析栈）走 ingestion client，
    # 问答/检索/只读路由走 qa client。单 Pod 部署（all 模式）不设 INGESTION_API_URL 时，
    # ingest_base_url 退化为 base_url，行为与拆分前一致（兼容本地开发）。
    # 路径格式与 utils.py 各方法传入的 url 一致（不带 /kw 前缀，由 base_url 的 /kw 后缀拼接）。

    _INGEST_ROUTES = {
        "/knowledge_base/upload_temp_docs",
        "/knowledge_base/create_knowledge_base",
        "/knowledge_base/delete_knowledge_base",
        "/knowledge_base/update_docs_by_id",
        "/knowledge_base/upload_docs",
        "/knowledge_base/upload_docs_use_path",
        "/knowledge_base/batch_upload_docs",
        "/knowledge_base/upload_project",
        "/knowledge_base/delete_docs",
        "/knowledge_base/archive_docs",
        "/knowledge_base/update_info",
        "/knowledge_base/update_docs",
        "/knowledge_base/update_docs_use_path",
        "/knowledge_base/download_doc",
        "/knowledge_base/recreate_vector_store",
        "/knowledge_base/kb_summary_api/summary_file_to_vector_store",
        "/knowledge_base/kb_summary_api/summary_doc_ids_to_vector_store",
        "/knowledge_base/kb_summary_api/recreate_summary_vector_store",
    }

    def __init__(
        self,
        base_url: str = api_address(),
        timeout: float = HTTPX_DEFAULT_TIMEOUT,
        ingest_base_url: Optional[str] = None,
    ):
        self.base_url = base_url
        # 优先级：显式传入 > params 配置 > 环境变量 > base_url（单 Pod 兼容）
        self.ingest_base_url = (
            ingest_base_url or
            INGESTION_API_URL or
            os.getenv("INGESTION_API_URL") or
            base_url
        )
        self.timeout = timeout
        self._use_async = False
        self._client = None
        self._ingest_client = None

    @property
    def client(self):
        if self._client is None or self._client.is_closed:
            self._client = get_httpx_client(base_url=self.base_url,
                                            use_async=self._use_async,
                                            timeout=self.timeout)
        return self._client

    @property
    def ingest_client(self):
        if self._ingest_client is None or self._ingest_client.is_closed:
            self._ingest_client = get_httpx_client(base_url=self.ingest_base_url,
                                                    use_async=self._use_async,
                                                    timeout=self.timeout)
        return self._ingest_client

    def get(
            self,
            url: str,
            params: Union[Dict, List[Tuple], bytes] = None,
            retry: int = 3,
            stream: bool = False,
            **kwargs: Any,
    ) -> Union[httpx.Response, Iterator[httpx.Response], None]:
        while retry > 0:
            try:
                if stream:
                    return self._client_for(url).stream(HTTP_METHOD_GET, url, params=params, **kwargs)
                else:
                    return self._client_for(url).get(url, params=params, **kwargs)
            except Exception as e:
                msg = f"error when get {url}: {e}"
                logger.error(f'{e.__class__.__name__}: {msg}',
                             exc_info=e if log_verbose else None)
                retry -= 1
        return None

    def post(
            self,
            url: str,
            data: Dict = None,
            json: Dict = None,
            retry: int = 3,
            stream: bool = False,
            **kwargs: Any
    ) -> Union[httpx.Response, Iterator[httpx.Response], None]:
        while retry > 0:
            try:
                if stream:
                    return self._client_for(url).stream(HTTP_METHOD_POST, url, data=data, json=json, **kwargs)
                else:
                    return self._client_for(url).post(url, data=data, json=json, **kwargs)
            except Exception as e:
                msg = f"error when post {url}: {e}"
                logger.error(f'{e.__class__.__name__}: {msg}',
                             exc_info=e if log_verbose else None)
                retry -= 1
        return None

    def delete(
            self,
            url: str,
            data: Dict = None,
            json: Dict = None,
            retry: int = 3,
            stream: bool = False,
            **kwargs: Any
    ) -> Union[httpx.Response, Iterator[httpx.Response], None]:
        while retry > 0:
            try:
                if stream:
                    return self._client_for(url).stream("DELETE", url, data=data, json=json, **kwargs)
                else:
                    return self._client_for(url).delete(url, data=data, json=json, **kwargs)
            except Exception as e:
                msg = f"error when delete {url}: {e}"
                logger.error(f'{e.__class__.__name__}: {msg}',
                             exc_info=e if log_verbose else None)
                retry -= 1
        return None

    # 服务器信息

    def get_server_configs(self, **kwargs) -> Dict:
        response = self.post("/server/configs", **kwargs)
        return self._get_response_value(response, as_json=True)

    def list_search_engines(self, **kwargs) -> List:
        response = self.post("/server/list_search_engines", **kwargs)
        return self._get_response_value(response, as_json=True, value_func=lambda r: r["data"])

    def get_prompt_template(
            self,
            type: str = "llm_chat",
            name: str = "default",
            **kwargs,
    ) -> str:
        data = {
            "type": type,
            "name": name,
        }
        response = self.post("/server/get_prompt_template", json=data, **kwargs)
        return self._get_response_value(response, value_func=lambda r: r.text)

    # 对话相关操作

    def chat_chat(self, query: str, **kwargs):
        '''
        对应api.py/chat/chat接口
        '''
        user_id = kwargs.pop("user_id", None)
        conversation_id = kwargs.pop("conversation_id", None)
        history_len = kwargs.pop("history_len", -1)
        history = kwargs.pop("history", None) or []
        stream = kwargs.pop("stream", True)
        model = kwargs.pop("model", LLM_MODELS[0])
        temperature = kwargs.pop("temperature", TEMPERATURE)
        max_tokens = kwargs.pop("max_tokens", None)
        prompt_name = kwargs.pop("prompt_name", "default")
        data = {
            "query": query,
            "user_id": user_id,
            "conversation_id": conversation_id,
            "history_len": history_len,
            "history": history,
            "stream": stream,
            "model_name": model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "prompt_name": prompt_name,
        }

        response = self.post("/chat/chat", json=data, stream=True, **kwargs)
        return self._httpx_stream2generator(response, as_json=True)

    def knowledge_base_chat(self, query: str, **kwargs):
        '''
        对应 api.py /chat/multi_knowledge_base_chat 接口。
        注：单库 knowledge_base_chat 端点已下线，此处内部将单个知识库名
        包成列表转发到 multi 端点，保持 webui 调用签名不变（dialogue.py 无需改动）。
        '''
        user_id = kwargs.get("user_id", None)
        knowledge_base_name = kwargs.get("knowledge_base_name", None)
        top_k = kwargs.get("top_k", VECTOR_SEARCH_TOP_K)
        score_threshold = kwargs.get("score_threshold", SCORE_THRESHOLD)
        history = kwargs.get("history", None) or []
        stream = kwargs.get("stream", True)
        model = kwargs.get("model", LLM_MODELS[0])
        temperature = kwargs.get("temperature", TEMPERATURE)
        max_tokens = kwargs.get("max_tokens", None)
        prompt_name = kwargs.get("prompt_name", "default")
        deep_think = kwargs.get("deep_think", False)
        data = {
            "query": query,
            "user_id": user_id,
            "knowledge_base_names": [knowledge_base_name],
            "top_k": top_k,
            "score_threshold": score_threshold,
            "history": history,
            "stream": stream,
            "model_name": model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "prompt_name": prompt_name,
            "deep_think": deep_think,
        }

        response = self.post(
            "/chat/multi_knowledge_base_chat",
            json=data,
            stream=True,
        )
        return self._httpx_stream2generator(response, as_json=True)

    def upload_temp_docs(
            self,
            files: List[Union[str, Path, bytes]],
            knowledge_id: Optional[str] = None,
            chunk_size=CHUNK_SIZE,
            chunk_overlap=OVERLAP_SIZE,
            zh_title_enhance=ZH_TITLE_ENHANCE,
    ):
        """
        对应api.py/knowledge_base/upload_tmep_docs接口
        """
        def convert_file(file, filename=None):
            if isinstance(file, bytes): # raw bytes
                file = BytesIO(file)
            elif hasattr(file, "read"): # a file io like object
                filename = filename or file.name
            else: # a local path
                p = Path(file).absolute()
                with p.open("rb") as f:
                    content = f.read()
                file = BytesIO(content)
                filename = filename or p.name
            return filename, file

        files = [convert_file(file) for file in files]
        data = {
            "knowledge_id": knowledge_id,
            "chunk_size": chunk_size,
            "chunk_overlap": chunk_overlap,
            "zh_title_enhance": zh_title_enhance,
        }

        response = self.post(
            "/knowledge_base/upload_temp_docs",
            data=data,
            files=[("files", (filename, file)) for filename, file in files],
        )
        return self._get_response_value(response, as_json=True)

    def file_chat(self, query: str, **kwargs):
        """
        对应api.py/chat/file_chat接口
        """
        knowledge_id = kwargs.get("knowledge_id", None)
        top_k = kwargs.get("top_k", VECTOR_SEARCH_TOP_K)
        score_threshold = kwargs.get("score_threshold", SCORE_THRESHOLD)
        history = kwargs.get("history", None) or []
        stream = kwargs.get("stream", True)
        model = kwargs.get("model", LLM_MODELS[0])
        temperature = kwargs.get("temperature", TEMPERATURE)
        max_tokens = kwargs.get("max_tokens", None)
        prompt_name = kwargs.get("prompt_name", "default")
        data = {
            "query": query,
            "knowledge_id": knowledge_id,
            "top_k": top_k,
            "score_threshold": score_threshold,
            "history": history,
            "stream": stream,
            "model_name": model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "prompt_name": prompt_name,
        }

        response = self.post(
            "/chat/file_chat",
            json=data,
            stream=True,
        )
        return self._httpx_stream2generator(response, as_json=True)

    # 知识库相关操作

    def list_knowledge_bases(
            self,
    ):
        '''
        对应api.py/knowledge_base/list_knowledge_bases接口
        '''
        response = self.get("/knowledge_base/list_knowledge_bases")
        return self._get_response_value(response,
                                        as_json=True,
                                        value_func=lambda r: r.get("data", []))

    def create_knowledge_base(
            self,
            knowledge_base_name: str,
            vector_store_type: str = DEFAULT_VS_TYPE,
            embed_model: str = EMBEDDING_MODEL,
    ):
        '''
        对应api.py/knowledge_base/create_knowledge_base接口
        '''
        data = {
            "knowledge_base_name": knowledge_base_name,
            "vector_store_type": vector_store_type,
            "embed_model": embed_model,
        }

        response = self.post(
            "/knowledge_base/create_knowledge_base",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def delete_knowledge_base(
            self,
            knowledge_base_name: str,
    ):
        '''
        对应api.py/knowledge_base/delete_knowledge_base接口
        '''
        response = self.post(
            "/knowledge_base/delete_knowledge_base",
            json=f"{knowledge_base_name}",
        )
        return self._get_response_value(response, as_json=True)

    def list_kb_docs(
            self,
            knowledge_base_name: str,
    ):
        '''
        对应api.py/knowledge_base/list_files接口
        '''
        response = self.get(
            "/knowledge_base/list_files",
            params={"knowledge_base_name": knowledge_base_name}
        )
        return self._get_response_value(response,
                                        as_json=True,
                                        value_func=lambda r: r.get("data", []))

    def search_kb_docs(self, knowledge_base_name: str, **kwargs) -> List:
        '''
        对应api.py/knowledge_base/search_docs接口
        '''
        query = kwargs.get("query", "")
        top_k = kwargs.get("top_k", VECTOR_SEARCH_TOP_K)
        score_threshold = kwargs.get("score_threshold", SCORE_THRESHOLD)
        file_name = kwargs.get("file_name", "")
        metadata = kwargs.get("metadata", None) or {}
        data = {
            "query": query,
            "knowledge_base_name": knowledge_base_name,
            "top_k": top_k,
            "score_threshold": score_threshold,
            "file_name": file_name,
            "metadata": metadata,
        }

        response = self.post(
            "/knowledge_base/search_docs",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def update_docs_by_id(
            self,
            knowledge_base_name: str,
            docs: Dict[str, Dict],
    ) -> bool:
        '''
        对应api.py/knowledge_base/update_docs_by_id接口
        '''
        data = {
            "knowledge_base_name": knowledge_base_name,
            "docs": docs,
        }
        response = self.post(
            "/knowledge_base/update_docs_by_id",
            json=data
        )
        return self._get_response_value(response)

    def upload_kb_docs(self, files: List[Union[str, Path, bytes]],
                       knowledge_base_name: str, **kwargs):
        '''
        对应api.py/knowledge_base/upload_docs接口
        '''
        _DOCS_KEY = 'docs'
        override = kwargs.get("override", False)
        to_vector_store = kwargs.get("to_vector_store", True)
        chunk_size = kwargs.get("chunk_size", CHUNK_SIZE)
        chunk_overlap = kwargs.get("chunk_overlap", OVERLAP_SIZE)
        zh_title_enhance = kwargs.get("zh_title_enhance", ZH_TITLE_ENHANCE)
        zh_label_enhance = kwargs.get("zh_label_enhance", ZH_LABEL_ENHANCE)
        docs = kwargs.get("docs", None)
        not_refresh_vs_cache = kwargs.get("not_refresh_vs_cache", False)
        if docs is None:
            docs = {}

        def convert_file(file, filename=None):
            if isinstance(file, bytes):  # raw bytes
                file = BytesIO(file)
            elif hasattr(file, "read"):  # a file io like object
                filename = filename or file.name
            else:  # a local path
                _file_path = Path(file).absolute()
                file = BytesIO(_file_path.read_bytes())
                filename = filename or _file_path.name
            return filename, file

        files = [convert_file(file) for file in files]
        data = {
            "knowledge_base_name": knowledge_base_name,
            "override": override,
            "to_vector_store": to_vector_store,
            "chunk_size": chunk_size,
            "chunk_overlap": chunk_overlap,
            "zh_title_enhance": zh_title_enhance,
            _DOCS_KEY: docs,
            "not_refresh_vs_cache": not_refresh_vs_cache,
        }

        if isinstance(data[_DOCS_KEY], dict):
            data[_DOCS_KEY] = _json.dumps(data[_DOCS_KEY], ensure_ascii=False)
        response = self.post(
            "/knowledge_base/upload_docs",
            data=data,
            files=[("files", (filename, file)) for filename, file in files],
        )
        return self._get_response_value(response, as_json=True)

    def delete_kb_docs(
            self,
            knowledge_base_name: str,
            file_names: List[str],
            delete_content: bool = False,
            not_refresh_vs_cache: bool = False,
    ):
        '''
        对应api.py/knowledge_base/delete_docs接口
        '''
        data = {
            "knowledge_base_name": knowledge_base_name,
            "file_names": file_names,
            "delete_content": delete_content,
            "not_refresh_vs_cache": not_refresh_vs_cache,
        }

        response = self.post(
            "/knowledge_base/delete_docs",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def update_kb_info(self, knowledge_base_name, kb_info):
        '''
        对应api.py/knowledge_base/update_info接口
        '''
        data = {
            "knowledge_base_name": knowledge_base_name,
            "kb_info": kb_info,
        }

        response = self.post(
            "/knowledge_base/update_info",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def update_kb_docs(self, knowledge_base_name: str,
                       file_names: List[str], **kwargs):
        '''
        对应api.py/knowledge_base/update_docs接口
        '''
        _DOCS_KEY = 'docs'
        override_custom_docs = kwargs.get("override_custom_docs", False)
        chunk_size = kwargs.get("chunk_size", CHUNK_SIZE)
        chunk_overlap = kwargs.get("chunk_overlap", OVERLAP_SIZE)
        zh_title_enhance = kwargs.get("zh_title_enhance", ZH_TITLE_ENHANCE)
        docs = kwargs.get("docs", None)
        not_refresh_vs_cache = kwargs.get("not_refresh_vs_cache", False)
        if docs is None:
            docs = {}
        data = {
            "knowledge_base_name": knowledge_base_name,
            "file_names": file_names,
            "override_custom_docs": override_custom_docs,
            "chunk_size": chunk_size,
            "chunk_overlap": chunk_overlap,
            "zh_title_enhance": zh_title_enhance,
            _DOCS_KEY: docs,
            "not_refresh_vs_cache": not_refresh_vs_cache,
        }

        if isinstance(data[_DOCS_KEY], dict):
            data[_DOCS_KEY] = _json.dumps(data[_DOCS_KEY], ensure_ascii=False)

        response = self.post(
            "/knowledge_base/update_docs",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def recreate_vector_store(self, knowledge_base_name: str, **kwargs):
        '''
        对应api.py/knowledge_base/recreate_vector_store接口
        '''
        allow_empty_kb = kwargs.get("allow_empty_kb", True)
        vs_type = kwargs.get("vs_type", DEFAULT_VS_TYPE)
        embed_model = kwargs.get("embed_model", EMBEDDING_MODEL)
        chunk_size = kwargs.get("chunk_size", CHUNK_SIZE)
        chunk_overlap = kwargs.get("chunk_overlap", OVERLAP_SIZE)
        zh_title_enhance = kwargs.get("zh_title_enhance", ZH_TITLE_ENHANCE)
        data = {
            "knowledge_base_name": knowledge_base_name,
            "allow_empty_kb": allow_empty_kb,
            "vs_type": vs_type,
            "embed_model": embed_model,
            "chunk_size": chunk_size,
            "chunk_overlap": chunk_overlap,
            "zh_title_enhance": zh_title_enhance,
        }

        response = self.post(
            "/knowledge_base/recreate_vector_store",
            json=data,
            stream=True,
            timeout=None,
        )
        return self._httpx_stream2generator(response, as_json=True)

    # LLM模型相关操作

    def list_running_models(
            self,
            controller_address: str = None,
    ):
        '''
        获取正运行的模型列表
        '''
        data = {
            "controller_address": controller_address,
        }

        if log_verbose:
            logger.info(f'{self.__class__.__name__}:data: {data}')

        response = self.post(
            "/llm_model/list_running_models",
            json=data,
        )
        return self._get_response_value(response, as_json=True, value_func=lambda r: r.get("data", []))

    def get_default_llm_model(self, local_first: bool = True) -> Tuple[str, bool]:
        '''
        从服务器上获取当前运行的LLM模型。
        当 local_first=True 时，优先返回运行中的本地模型，否则优先按LLM_MODELS配置顺序返回。
        返回类型为（model_name, is_local_model）
        '''

        def ret_sync():
            running_models = self.list_running_models()
            if not running_models:
                return "", False

            model = ""
            for m in LLM_MODELS:
                if m not in running_models:
                    continue
                is_local = not running_models[m].get("online_api")
                if local_first and not is_local:
                    continue
                else:
                    model = m
                    break

            if not model:  # LLM_MODELS中配置的模型都不在running_models里
                model = list(running_models)[0]
            is_local = not running_models[model].get("online_api")
            return model, is_local

        async def ret_async():
            running_models = await self.list_running_models()
            if not running_models:
                return "", False

            model = ""
            for m in LLM_MODELS:
                if m not in running_models:
                    continue
                is_local = not running_models[m].get("online_api")
                if local_first and not is_local:
                    continue
                else:
                    model = m
                    break

            if not model:  # LLM_MODELS中配置的模型都不在running_models里
                model = list(running_models)[0]
            is_local = not running_models[model].get("online_api")
            return model, is_local

        if self._use_async:
            return ret_async()
        else:
            return ret_sync()

    def list_config_models(
            self,
            types: List[str] = None,
    ) -> Dict[str, Dict]:
        '''
        获取服务器configs中配置的模型列表，返回形式为{"type": {model_name: config}, ...}。
        '''
        if types is None:
            types = ["local", "online"]
        data = {
            "types": types,
        }
        response = self.post(
            "/llm_model/list_config_models",
            json=data,
        )
        return self._get_response_value(response, as_json=True, value_func=lambda r: r.get("data", {}))

    def get_model_config(
            self,
            model_name: str = None,
    ) -> Dict:
        '''
        获取服务器上模型配置
        '''
        data = {
            "model_name": model_name,
        }
        response = self.post(
            "/llm_model/get_model_config",
            json=data,
        )
        return self._get_response_value(response, as_json=True, value_func=lambda r: r.get("data", {}))

    def list_search_engines(self) -> List[str]:
        '''
        获取服务器支持的搜索引擎
        '''
        response = self.post(
            "/server/list_search_engines",
        )
        return self._get_response_value(response, as_json=True, value_func=lambda r: r.get("data", {}))

    def stop_llm_model(
            self,
            model_name: str,
            controller_address: str = None,
    ):
        '''
        停止某个LLM模型。
        注意：实际上是把LLM模型所在的model_worker停掉。
        '''
        data = {
            "model_name": model_name,
            "controller_address": controller_address,
        }

        response = self.post(
            "/llm_model/stop",
            json=data,
        )
        return self._get_response_value(response, as_json=True)

    def change_llm_model(
            self,
            model_name: str,
            new_model_name: str,
            controller_address: str = None,
    ):
        '''
        请求切换LLM模型。
        '''
        if not model_name or not new_model_name:
            return {
                "code": 500,
                "msg": "未指定模型名称"
            }

        def ret_sync():
            _CODE_KEY = 'code'
            _MSG_KEY = 'msg'
            running_models = self.list_running_models()
            if new_model_name == model_name or new_model_name in running_models:
                return {
                    _CODE_KEY: 200,
                    _MSG_KEY: "无需切换"
                }

            if model_name not in running_models:
                return {
                    _CODE_KEY: 500,
                    _MSG_KEY: f"指定的模型'{model_name}'没有运行。当前运行模型：{running_models}"
                }

            config_models = self.list_config_models()
            if new_model_name not in config_models.get("local", {}):
                return {
                    _CODE_KEY: 500,
                    _MSG_KEY: f"要切换的模型'{new_model_name}'在configs中没有配置。"
                }

            data = {
                "model_name": model_name,
                "new_model_name": new_model_name,
                "controller_address": controller_address,
            }

            response = self.post(
                "/llm_model/change",
                json=data,
            )
            return self._get_response_value(response, as_json=True)

        async def ret_async():
            _CODE_KEY = 'code'
            _MSG_KEY = 'msg'
            running_models = await self.list_running_models()
            if new_model_name == model_name or new_model_name in running_models:
                return {
                    _CODE_KEY: 200,
                    _MSG_KEY: "无需切换"
                }

            if model_name not in running_models:
                return {
                    _CODE_KEY: 500,
                    _MSG_KEY: f"指定的模型'{model_name}'没有运行。当前运行模型：{running_models}"
                }

            config_models = await self.list_config_models()
            if new_model_name not in config_models.get("local", {}):
                return {
                    _CODE_KEY: 500,
                    _MSG_KEY: f"要切换的模型'{new_model_name}'在configs中没有配置。"
                }

            data = {
                "model_name": model_name,
                "new_model_name": new_model_name,
                "controller_address": controller_address,
            }

            response = self.post(
                "/llm_model/change",
                json=data,
            )
            return self._get_response_value(response, as_json=True)

        if self._use_async:
            return ret_async()
        else:
            return ret_sync()

    def embed_texts(
            self,
            texts: List[str],
            embed_model: str = EMBEDDING_MODEL,
            to_query: bool = False,
    ) -> List[List[float]]:
        '''
        对文本进行向量化，可选模型包括本地 embed_models 和支持 embeddings 的在线模型
        '''
        data = {
            "texts": texts,
            "embed_model": embed_model,
            "to_query": to_query,
        }
        resp = self.post(
            "/other/embed_texts",
            json=data,
        )
        return self._get_response_value(resp, as_json=True, value_func=lambda r: r.get("data"))

    def chat_feedback(
            self,
            message_id: str,
            user_id: str,
            score: int,
            reason: str = "",
    ) -> int:
        '''
        反馈对话评价
        '''
        data = {
            "message_id": message_id,
            "user_id": user_id,
            "score": score,
            "reason": reason,
        }
        resp = self.post("/chat/feedback", json=data)
        return self._get_response_value(resp)

    def _client_for(self, url: str):
        # 按 URL 自动选 client：入库路由走 ingestion，其余走 qa（base_url）
        path = url.split("?", 1)[0]
        if path in self._INGEST_ROUTES:
            return self.ingest_client
        return self.client

    def _httpx_stream2generator(
            self,
            response: contextlib._GeneratorContextManager,
            as_json: bool = False,
    ):
        """
        将httpx.stream返回的GeneratorContextManager转化为普通生成器
        """
        async def ret_async(response, as_json):
            _CODE_KEY = 'code'
            _MSG_KEY = 'msg'
            try:
                async with response as r:
                    async for chunk in r.aiter_text(None):
                        if not chunk: # streaming api yield empty bytes on start and end
                            continue
                        if as_json:
                            try:
                                if chunk.startswith("data: "):
                                    data = _json.loads(chunk[6:-2])
                                elif chunk.startswith(":"): # skip sse comment line
                                    continue
                                else:
                                    data = _json.loads(chunk)
                                yield data
                            except Exception as e:
                                msg = f"接口返回json错误： ‘{chunk}’。错误信息是：{e}。"
                                logger.error(f'{e.__class__.__name__}: {msg}',
                                            exc_info=e if log_verbose else None)
                        else:
                            # print(chunk, end="", flush=True)
                            yield chunk
            except httpx.ConnectError as e:
                msg = f"无法连接API服务器，请确认 ‘api.py’ 已正常启动。({e})"
                logger.error(msg)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}
            except httpx.ReadTimeout as e:
                msg = f"API通信超时，请确认已启动API服务（详见Wiki '5. 启动 API 服务或 Web UI'）。（{e}）"
                logger.error(msg)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}
            except Exception as e:
                msg = f"API通信遇到错误：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}',
                            exc_info=e if log_verbose else None)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}

        def ret_sync(response, as_json):
            _CODE_KEY = 'code'
            _MSG_KEY = 'msg'
            try:
                with response as r:
                    for chunk in r.iter_text(None):
                        if not chunk:  # streaming api yield empty bytes on start and end
                            continue
                        if as_json:
                            try:
                                if chunk.startswith("data: "):
                                    data = _json.loads(chunk[6:-2])
                                elif chunk.startswith(":"):  # skip sse comment line
                                    continue
                                else:
                                    data = _json.loads(chunk)
                                yield data
                            except Exception as e:
                                msg = f"接口返回json错误： ‘{chunk}’。错误信息是：{e}。"
                                logger.error(f'{e.__class__.__name__}: {msg}',
                                             exc_info=e if log_verbose else None)
                        else:
                            # print(chunk, end="", flush=True)
                            yield chunk
            except httpx.ConnectError as e:
                msg = f"无法连接API服务器，请确认 ‘api.py’ 已正常启动。({e})"
                logger.error(msg)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}
            except httpx.ReadTimeout as e:
                msg = f"API通信超时，请确认已启动API服务（详见Wiki '5. 启动 API 服务或 Web UI'）。（{e}）"
                logger.error(msg)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}
            except Exception as e:
                msg = f"API通信遇到错误：{e}"
                logger.error(f'{e.__class__.__name__}: {msg}',
                             exc_info=e if log_verbose else None)
                yield {_CODE_KEY: 500, _MSG_KEY: msg}

        if self._use_async:
            return ret_async(response, as_json)
        return ret_sync(response, as_json)

    def _get_response_value(
            self,
            response: httpx.Response,
            as_json: bool = False,
            value_func: Callable = None,
    ):
        '''
        转换同步或异步请求返回的响应
        `as_json`: 返回json
        `value_func`: 用户可以自定义返回值，该函数接受response或json
        '''

        def to_json(r):
            try:
                return r.json()
            except Exception as e:
                msg = "API未能返回正确的JSON。" + str(e)
                if log_verbose:
                    logger.error(f'{e.__class__.__name__}: {msg}',
                                 exc_info=e if log_verbose else None)
                return {"code": 500, "msg": msg, "data": None}

        if value_func is None:
            def _identity(r):
                return r
            value_func = _identity

        async def ret_async(response):
            if as_json:
                return value_func(to_json(await response))
            else:
                return value_func(await response)

        if self._use_async:
            return ret_async(response)
        else:
            if as_json:
                return value_func(to_json(response))
            else:
                return value_func(response)


class AsyncApiRequest(ApiRequest):
    def __init__(self, base_url: str = api_address(), timeout: float = HTTPX_DEFAULT_TIMEOUT, ingest_base_url: str = None):  # pylint: disable=line-too-long
        super().__init__(base_url, timeout, ingest_base_url)
        self._use_async = True


def check_error_msg(data: Union[str, dict, list], key: str = "errorMsg") -> str:
    '''
    return error message if error occured when requests API
    '''
    if isinstance(data, dict):
        if key in data:
            return data[key]
        if "code" in data and data["code"] != HTTP_OK:
            return data["msg"]
    return ""


def check_success_msg(data: Union[str, dict, list], key: str = "msg") -> str:
    '''
    return error message if error occured when requests API
    '''
    _is_dict_with_key = isinstance(data, dict) and key in data
    if _is_dict_with_key and "code" in data and data["code"] == HTTP_OK:
        return data[key]
    return ""


if __name__ == "__main__":
    _api = ApiRequest()
    aapi = AsyncApiRequest()
