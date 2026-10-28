"""MemMachine 客户端：用户长期记忆（习惯/爱好/角色）的读写。

REST API v2（http://<host>:8080/api/v2/...）：
- POST /projects/list              查项目
- POST /projects                   建项目（org_id + project_id）
- POST /memories                   写入消息（producer/metadata 携带 user_id）
- POST /memories/search            episodic+semantic 检索（agent_mode）
- POST /memories/episodic|semantic/delete  按 id 删除

隔离策略：服务端无 user 维度过滤——producer 与 metadata 双写 user_id，
search 后客户端二次过滤。全链路 fail-open：任何异常只告警，绝不影响聊天。

性能：全部 HTTP 走 httpx.AsyncClient（可取消），_finish_mem 超时 cancel
能真正中断在途连接，避免孤儿 task；检索 2s 超时 + 连接池复用。
"""
import asyncio
import json
import os
import threading
import time

import httpx

from configs import logger
from configs.server_loader import MEMMACHINE_BASE_URL, MEMMACHINE_ORG_ID, MEMMACHINE_PROJECT_ID, MEMMACHINE_TOP_K

_TIMEOUT = 2.0  # 检索超时：健康服务 ~300ms，2s 给 5-10× 余量；fail-open 特性无需长等
_PROJECT_RETRY_INTERVAL = 300.0  # 建项目失败后的退避（秒）

# 简单类型（search 结果条目格式由 MemMachine 决定，这里只做浅解析）
_SEARCH_RESULT_FIELDS = ("id", "memory_id", "content", "text", "metadata",
                         "producer", "score", "similarity", "timestamp", "created_at")

# 模块级共享 AsyncClient（连接池复用；cancel 能真正中断在途请求）
_async_client: httpx.AsyncClient | None = None


def _get_async_client() -> httpx.AsyncClient:
    """懒初始化共享 AsyncClient；limits 防资源耗尽，timeout 兜底。"""
    global _async_client
    if _async_client is None:
        _async_client = httpx.AsyncClient(
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
            timeout=httpx.Timeout(_TIMEOUT),
        )
    return _async_client


async def _http_async(base_url: str, path: str, body: dict,
                      timeout: float | None = None) -> dict:
    """异步 POST JSON；异常向上抛（调用方 fail-open 捕获）。

    用共享 AsyncClient，cancel 时 httpx 会关闭在途连接——
    替代 urllib（同步、线程内不可中断）。
    """
    client = _get_async_client()
    resp = await client.post(
        base_url.rstrip("/") + path,
        json=body,
        timeout=timeout if timeout is not None else _TIMEOUT,
    )
    resp.raise_for_status()
    return resp.json()


# ── 用户记忆本地索引（/kw 持久卷）──────────────────────────────────
# MemMachine v2 无按 user 列出/过滤接口，宽召回语义检索不稳定——写入时
# 同步登记 (uid, fact)，list/查重/删除走本地索引，检索注入走语义 search。
_INDEX_ROOT = os.environ.get("USER_MEMORY_INDEX_DIR", "/kw/.user_memories")
_INDEX_MAX = 200
_index_lock = threading.Lock()


def _index_path(user_id: str) -> str:
    safe = "".join(c for c in user_id if c.isalnum() or c in "-_")[:64]
    return os.path.join(_INDEX_ROOT, f"{safe}.json")


def _index_load(user_id: str) -> list[dict]:
    try:
        with open(_index_path(user_id), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _index_save(user_id: str, items: list[dict]) -> None:
    try:
        os.makedirs(_INDEX_ROOT, exist_ok=True)
        tmp = _index_path(user_id) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(items[-_INDEX_MAX:], f, ensure_ascii=False)
        os.replace(tmp, _index_path(user_id))
    except Exception as e:
        logger.warning(f"[memmachine] 记忆索引写入失败（不影响主流程）: {e}")


def _index_append(user_id: str, uid: str, content: str,
                  **kwargs) -> None:
    """追加一条记忆索引到本地索引文件。

    后 4 个参数（通过 kwargs 传入，避免签名参数过多）:
        topic: str - 主题（默认 ""）
        confidence: str - 置信度字符串（默认 ""）
        mem_type: str - 记忆类型（默认 "profile"）
        conv: str - 会话 ID（默认 ""）
    """
    topic = kwargs.get("topic", "")
    confidence = kwargs.get("confidence", "")
    mem_type = kwargs.get("mem_type", "profile")
    conv = kwargs.get("conv", "")
    with _index_lock:
        items = _index_load(user_id)
        if any(i.get("content") == content for i in items):
            return
        items.append({"uid": uid, "content": content, "topic": topic,
                      "confidence": confidence, "type": mem_type, "conv": conv,
                      "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
        _index_save(user_id, items)


def _index_remove(user_id: str, uids: list[str]) -> None:
    with _index_lock:
        items = _index_load(user_id)
        rest = [i for i in items if i.get("uid") not in uids]
        if len(rest) != len(items):
            _index_save(user_id, rest)


def _index_search(user_id: str, fact: str) -> bool:
    """本地索引查重：完全相同或高相似已存在。"""
    import difflib
    for i in _index_load(user_id):
        old = i.get("content", "")
        if fact in old or old in fact:
            return True
        if difflib.SequenceMatcher(None, fact, old).ratio() >= 0.8:
            return True
    return False


def _iter_memory_items(data: dict):
    """从 search 响应抽取 (source, item) 迭代。

    v2 响应包 {status, content}；content.episodic_memory 分
    long_term_memory/short_term_memory 两层 episodes，semantic_memory 为
    扁平列表。episode 字段：uid/content/producer_id/metadata/created_at。
    """
    content = data.get("content") if isinstance(data, dict) else data
    if not isinstance(content, dict):
        return
    epi = content.get("episodic_memory") or {}
    if isinstance(epi, dict):
        for part_key in ("long_term_memory", "short_term_memory"):
            part = epi.get(part_key) or {}
            if isinstance(part, dict):
                for it in part.get("episodes") or []:
                    if isinstance(it, dict):
                        it["_source"] = "episodic"
                        yield it
    sem = content.get("semantic_memory")
    if isinstance(sem, list):
        for it in sem:
            if isinstance(it, dict):
                it["_source"] = "semantic"
                yield it


class MemMachineClient:
    """单例客户端（get_memmachine()）；项目初始化用 asyncio.Lock 防并发。"""



    def __init__(self):
        self._base = MEMMACHINE_BASE_URL
        self._org = MEMMACHINE_ORG_ID
        self._project = MEMMACHINE_PROJECT_ID
        self._top_k = int(MEMMACHINE_TOP_K or 5)
        self._project_ok = False
        self._project_fail_at = 0.0
        self._project_lock = asyncio.Lock()

    # ── 管理（列表/删除） ─────────────────────────────────────────

    @staticmethod
    def list_user_memories(user_id: str, limit: int = 50,
                           mem_type: str = "") -> list[dict]:
        """列出该用户记忆（本地索引，稳定可靠）。

        mem_type：空=全部 / profile=用户画像 / conversation=对话记忆。
        纯本地索引读，无 HTTP，保持同步（管理端点用）。
        """
        _TYPE_KEY = 'type'
        items = [i for i in reversed(_index_load(user_id))
                 if not mem_type or i.get(_TYPE_KEY, "profile") == mem_type]
        return [{
            "id": i.get("uid", ""),
            "content": i.get("content", ""),
            "source": "index",
            _TYPE_KEY: i.get(_TYPE_KEY, "profile"),
            "conversation_id": i.get("conv", ""),
            "topic": i.get("topic", ""),
            "confidence": i.get("confidence"),
            "timestamp": i.get("ts", ""),
        } for i in items[:limit]]

    # ── 写入 ──────────────────────────────────────────────────────

    async def add_memory(self, user_id: str, content: str, topic: str = "",
                         confidence: float = 1.0) -> bool:
        """写入一条用户画像记忆（producer+metadata 双写 user_id）。"""
        if not await self._ensure_project():
            return False
        try:
            resp = await _http_async(self._base, "/api/v2/memories", {
                "org_id": self._org,
                "project_id": self._project,
                "messages": [{
                    "content": content,
                    "producer": user_id,
                    "produced_for": user_id,
                    "role": "user",
                    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "metadata": {
                        # API 要求 metadata 值全部为 string（float 会 422）
                        "user_id": user_id,
                        "type": "user_profile",
                        "topic": str(topic or ""),
                        "confidence": f"{float(confidence):.2f}",
                    },
                }],
            }, timeout=10.0)
            # 写入响应带 uid（{"results":[{"uid":"1"}]}）——登记本地索引
            uid = ""
            try:
                results = resp.get("results") or []
                if results and isinstance(results[0], dict):
                    uid = str(results[0].get("uid", ""))
            except Exception as e:
                logger.debug(f"[memmachine] failed to extract uid from memmachine response: {e}")
                pass
            _index_append(user_id, uid, content,
                          topic=str(topic or ""),
                          confidence=f"{float(confidence):.2f}")  # type=profile
            return True
        except Exception as e:
            logger.warning(f"[memmachine] 写入失败（跳过）: {e}")
            return False

    # ── 检索 ──────────────────────────────────────────────────────

    async def search_user_memories(self, user_id: str, query: str,
                                   top_k: int | None = None) -> list[dict]:
        """检索该用户的记忆（客户端二次过滤 user_id 后截断 top_k）。

        返回 [{id, content, source(episodic|semantic), score}]，异常返回 []。
        """
        if not await self._ensure_project():
            return []
        limit = int(top_k or self._top_k)
        try:
            data = await _http_async(self._base, "/api/v2/memories/search", {
                "org_id": self._org,
                "project_id": self._project,
                "query": query,
                "top_k": limit + 5,  # 过滤余量
                "types": ["episodic", "semantic"],
                "agent_mode": True,
            }, timeout=_TIMEOUT)
        except Exception as e:
            logger.warning(f"[memmachine] 检索失败（跳过注入）: {e}")
            return []

        out: list[dict] = []
        for it in _iter_memory_items(data):
            if not isinstance(it, dict):
                continue
            meta = it.get("metadata") or {}
            owner = meta.get("user_id") or it.get("producer_id") or it.get("producer")
            if owner != user_id:
                continue  # 其他用户的记忆不注入
            content = (it.get("content") or it.get("text") or "").strip()
            if not content:
                continue
            out.append({
                "id": it.get("uid") or it.get("id") or "",
                "content": content,
                "source": it.get("_source"),
                "score": it.get("score") or it.get("similarity"),
                "topic": meta.get("topic", ""),
                "confidence": meta.get("confidence"),
            })
        return out[:limit]

    # ── 对话记忆（不限轮次历史：直存问答对 + 语义召回） ────────────

    async def add_conversation_memory(self, user_id: str, conversation_id: str,
                                      query: str, answer: str) -> bool:
        """直存一轮问答为对话记忆（无 LLM、每轮唯一不查重）。

        前端只传最近 3 轮 history——更早的轮次以此形式沉淀，后续按
        语义相关性召回（search_conversation_memories），实现不限轮次的
        历史信息对话。metadata.type=conversation 与用户画像区分。
        """
        if not await self._ensure_project():
            return False
        content = f"问：{(query or '')[:600]}\n答：{(answer or '')[:600]}"
        if not content.strip():
            return False
        try:
            resp = await _http_async(self._base, "/api/v2/memories", {
                "org_id": self._org,
                "project_id": self._project,
                "messages": [{
                    "content": content,
                    "producer": user_id,
                    "produced_for": user_id,
                    "role": "user",
                    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "metadata": {
                        "user_id": user_id,
                        "type": "conversation",
                        "conversation_id": str(conversation_id or ""),
                        "topic": "对话",
                        "confidence": "1.00",
                    },
                }],
            }, timeout=10.0)
            uid = ""
            try:
                results = resp.get("results") or []
                if results and isinstance(results[0], dict):
                    uid = str(results[0].get("uid", ""))
            except Exception as e:
                logger.debug(f"[memmachine] failed to extract uid from memmachine conversation response: {e}")
                pass
            _index_append(user_id, uid, content,
                          topic="对话", confidence="1.00",
                          mem_type="conversation", conv=str(conversation_id or ""))
            return True
        except Exception as e:
            logger.warning(f"[memmachine] 对话记忆写入失败（跳过）: {e}")
            return False



    async def search_conversation_memories(self, user_id: str, conversation_id: str,
                                           query: str, top_k: int = 3) -> list[dict]:
        """语义召回该用户的相关历史对话——当前会话优先、跨会话补充。

        返回 [{id, content, conversation_id}]，异常返回 []。
        """
        _CONTENT_KEY = 'content'
        if not await self._ensure_project():
            return []
        try:
            data = await _http_async(self._base, "/api/v2/memories/search", {
                "org_id": self._org,
                "project_id": self._project,
                "query": query,
                "top_k": (top_k + 3) * 2,
                "types": ["episodic", "semantic"],
                "agent_mode": True,
            }, timeout=_TIMEOUT)
        except Exception as e:
            logger.warning(f"[memmachine] 对话记忆检索失败（跳过注入）: {e}")
            return []
        same_conv, other_conv = [], []
        _CONV_ID_KEY = 'conversation_id'
        for it in _iter_memory_items(data):
            meta = it.get("metadata") or {}
            if meta.get("type") != "conversation":
                continue  # 只召回对话记忆（画像走 search_user_memories）
            if (meta.get("user_id") or it.get("producer_id")) != user_id:
                continue
            item = {"id": it.get("uid") or "",
                    _CONTENT_KEY: (it.get(_CONTENT_KEY) or "").strip(),
                    _CONV_ID_KEY: meta.get(_CONV_ID_KEY, "")}
            if not item[_CONTENT_KEY]:
                continue
            if conversation_id and meta.get(_CONV_ID_KEY) == conversation_id:
                same_conv.append(item)
            else:
                other_conv.append(item)
        # 当前会话优先，跨会话补充
        return (same_conv + other_conv)[:top_k]



    async def delete_memories(self, user_id: str, memory_type: str,
                              memory_ids: list[str]) -> int:
        """删除指定记忆（删前校验归属，防越权删他人记忆）。"""
        _EPISODIC_KEY = 'episodic'
        if not await self._ensure_project():
            return 0
        owned = {i.get("uid", ""): i for i in _index_load(user_id)}
        ids = [i for i in memory_ids if i in owned]
        if not ids:
            return 0
        path = ("/api/v2/memories/episodic/delete" if memory_type == _EPISODIC_KEY
                else "/api/v2/memories/semantic/delete")
        id_key = ("episodic_ids" if memory_type == _EPISODIC_KEY else "semantic_ids")
        single_key = ("episodic_id" if memory_type == _EPISODIC_KEY else "semantic_id")
        try:
            await _http_async(self._base, path, {
                "org_id": self._org,
                "project_id": self._project,
                id_key: ids,
                single_key: ids[0],
            }, timeout=10.0)
            _index_remove(user_id, ids)
            return len(ids)
        except Exception as e:
            logger.warning(f"[memmachine] 删除失败: {e}")
            return 0

    # ── 项目懒初始化 ──────────────────────────────────────────────

    async def _ensure_project(self) -> bool:
        if self._project_ok:
            return True
        if time.time() - self._project_fail_at < _PROJECT_RETRY_INTERVAL:
            return False
        async with self._project_lock:
            if self._project_ok:
                return True
            try:
                data = await _http_async(self._base, "/api/v2/projects/list", {})
                projects = data if isinstance(data, list) else data.get("projects", [])
                exists = any(
                    (p.get("project_id") == self._project or p.get("id") == self._project)
                    for p in projects if isinstance(p, dict))
                if not exists:
                    await _http_async(self._base, "/api/v2/projects", {
                        "org_id": self._org,
                        "project_id": self._project,
                        "description": "RAG 用户长期记忆（习惯/爱好/角色画像）",
                    }, timeout=15.0)
                self._project_ok = True
                logger.info(f"[memmachine] 项目就绪: {self._org}/{self._project}")
                return True
            except Exception as e:
                self._project_fail_at = time.time()
                logger.warning(f"[memmachine] 项目初始化失败（5 分钟后重试）: {e}")
                return False


_client: MemMachineClient | None = None


def get_memmachine() -> MemMachineClient:
    global _client
    if _client is None:
        _client = MemMachineClient()
    return _client


def memmachine_enabled() -> bool:
    from configs.server_loader import MEMMACHINE_ENABLED
    return bool(MEMMACHINE_ENABLED and MEMMACHINE_BASE_URL)
