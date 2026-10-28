"""知识库冲突检测（手工触发，方案二；支持单文件与批量文件）。

输入：知识库名（MemPalace palace）+ 服务器本地文件路径（单个或列表）。
异步执行：文件切 chunk → MemPalace 检索候选 → 相似度粗筛 + 自匹配过滤 →
LLM 逐对精判（C1 事实/C2 语义/C3 版本/C4 跨模态/C5 近重复）→
结果 JSON 落盘 KW_ROOT_PATH/conflict-detections/<task_id>.json（按文件分组）。
任务状态存 PostgreSQL（conflict_detection_task 表，导入时自愈建表+在线加列）。
"""
import json
import os
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from typing import List

from fastapi import Body

from configs import logger, MEMPALACE_BASE_URL, KW_ROOT_PATH
from server.utils import BaseResponse
from server._http_constants import (
    HTTP_OK,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_INTERNAL_ERROR,
    SECURITY_ATTACK_MSG,
    SUCCESS_MSG,
    HTTP_UPLOAD_TIMEOUT_SEC,
    HTTP_INDEX_TIMEOUT_SEC,
)
from server.knowledge_base.utils import KnowledgeFile, validate_kb_name
from server.db.repository import conflict_detection_repository as repo

CONFLICT_LLM_MODEL = os.environ.get("CONFLICT_LLM_MODEL", "qwen-flash")
CONFLICT_SIM_THRESHOLD = float(os.environ.get("CONFLICT_SIM_THRESHOLD", "0.55"))
CONFLICT_TOP_K = int(os.environ.get("CONFLICT_TOP_K", "5"))
CONFLICT_MAX_PAIRS = int(os.environ.get("CONFLICT_MAX_PAIRS", "3"))
CONFLICT_WORKERS = int(os.environ.get("CONFLICT_WORKERS", "3"))
# MemPalace /search 对 q 有 max_length=250 校验（实测 string_too_long 422），
# 默认截断对齐上限并留余量；环境变量仍可覆盖
CONFLICT_QUERY_TRUNC = int(os.environ.get("CONFLICT_QUERY_TRUNC", "240"))
CONFLICT_MAX_FILES = int(os.environ.get("CONFLICT_MAX_FILES", "50"))

_CHUNK_MIN_LEN = 20          # 过短 chunk 不参与检测
_TEXT_EXCERPT_LEN = 400      # 结果里的双方文本摘录长度
_SUGGESTIONS = {"KEEP_NEW", "KEEP_OLD", "MERGE", "COEXIST"}


# 业务消息模板
TASK_NOT_FOUND_MSG_TEMPLATE = "任务不存在: {task_id}"
FILE_NOT_FOUND_MSG_TEMPLATE = "文件不存在: {file_path}"
FILE_LIST_EMPTY_MSG = "文件列表为空"
BATCH_LIMIT_MSG_TEMPLATE = "单次批量上限 {max_files} 个文件，当前 {actual} 个"
CREATE_TASK_FAILED_MSG_TEMPLATE = "创建任务失败: {error}"
CREATE_BATCH_TASK_FAILED_MSG_TEMPLATE = "创建批量任务失败: {error}"
RESULT_FILE_MISSING_MSG_TEMPLATE = "任务无结果文件（status={status}）"
READ_RESULT_FAILED_MSG_TEMPLATE = "读取结果文件失败: {error}"
RESULT_FILE_NOT_FOUND_MSG_TEMPLATE = "结果中不存在文件: {file_path}"
ENABLED_MUST_BE_BOOL_MSG = "enabled 必须为布尔值"
SET_SWITCH_FAILED_MSG_TEMPLATE = "设置开关失败: {error}"
CONFLICT_TASK_CREATED_MSG = "冲突检测任务已创建"
BATCH_CONFLICT_TASK_CREATED_MSG = "批量冲突检测任务已创建"


class ConflictDetectionError(Exception):
    """检测主流程错误（MemPalace 不可达等），任务整体 FAILED。"""


CONFLICT_JUDGE_PROMPT = """你是芯片知识库的冲突检测专家。知识库名称：{kb}。
给定【新文本】（待检测文件的片段）和【旧文本】（知识库既有内容片段），判断两者是否存在知识冲突。

冲突类型定义：
- C1 事实冲突：同一实体/属性的数值或取值不一致（如寄存器偏移地址、最大速率、分辨率、频率、电压等）
- C2 语义冲突：描述性结论矛盾（如"支持硬件流控"vs"不支持"、"默认上拉"vs"默认浮空"）
- C3 版本冲突：同一特性在不同版本文档/SDK 中的描述差异
- C4 跨模态冲突：代码实现与文档描述不一致（如宏定义值 vs 手册标注值）
- C5 近重复冲突：内容高度相似但关键结论或推荐值不同

注意：两者仅话题相似、互为补充、或一方是另一方的展开细化，都不算冲突；
仅当对同一事实的表述互相矛盾时才判定为冲突。

只输出一个 <json>...</json> 块，不要输出任何其他内容，格式：
<json>{{"is_conflict": true, "type": "C1", "conflict_point": "一句话点明冲突点，如 XX 寄存器偏移 0x20 vs 0x24", "confidence": 0.9, "suggestion": "KEEP_NEW", "reason": "判定理由（简短）"}}</json>
字段说明：is_conflict 为 false 时 type/conflict_point/suggestion 输出 null；
confidence 取 0~1；suggestion 从 KEEP_NEW / KEEP_OLD / MERGE / COEXIST 中选（依据：数据手册>应用笔记>驱动注释、最新版本优先；双方均有效选 COEXIST；高度相似仅细节不同选 MERGE）。

【新文本】：
{new_text}

【旧文本】：
{old_text}
"""


# ==================== 对外 API ====================

def create_conflict_detection_task(
        knowledge_base_name: str = Body(..., description="比对目标知识库名称（kbId 或注册名，即 MemPalace palace）",
                                        examples=["kbfccb654a43f241cbb520174f30951e81"]),
        file_path: str = Body(..., description="待检测文件的服务器本地绝对路径（不必已入库，支持 md/pdf/docx 等入库同款格式）",
                              examples=["/kb-files/kb/CV610ALL/检测样例.md"]),
) -> BaseResponse:
    """创建冲突检测任务（异步，单文件）：文件内容 vs 指定知识库既有内容，输出 C1-C5 冲突清单。"""
    logger.info(f"/kw/conflict_detection/tasks request kb={knowledge_base_name} file={file_path}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    if not file_path or not os.path.isfile(file_path):
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=FILE_NOT_FOUND_MSG_TEMPLATE.format(file_path=file_path))
    try:
        task_uuid = _start_detection_task(knowledge_base_name, [file_path])
    except Exception as e:
        logger.error(f"[conflict-detection] 创建任务失败: {e}", exc_info=True)
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=CREATE_TASK_FAILED_MSG_TEMPLATE.format(error=e))
    return BaseResponse(code=HTTP_OK, msg=CONFLICT_TASK_CREATED_MSG,
                        data={"task_id": task_uuid, "status": "QUEUED"})


def create_conflict_detection_batch_task(
        knowledge_base_name: str = Body(..., description="比对目标知识库名称（kbId 或注册名，即 MemPalace palace）",
                                        examples=["kbfccb654a43f241cbb520174f30951e81"]),
        file_paths: List[str] = Body(..., description="待检测文件的服务器本地绝对路径列表（不必已入库，单次上限 CONFLICT_MAX_FILES 个）",
                                     examples=[["/kb-files/kb/CV610ALL/检测样例1.md", "/kb-files/kb/CV610ALL/检测样例2.md"]]),
) -> BaseResponse:
    """创建批量冲突检测任务（异步，多文件）：一个任务覆盖多个文件，结果按文件分组。"""
    logger.info(f"/kw/conflict_detection/batch_tasks request kb={knowledge_base_name} files={file_paths}")
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    if not file_paths:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=FILE_LIST_EMPTY_MSG)
    if len(file_paths) > CONFLICT_MAX_FILES:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=BATCH_LIMIT_MSG_TEMPLATE.format(max_files=CONFLICT_MAX_FILES, actual=len(file_paths)))
    missing = [p for p in file_paths if not os.path.isfile(p)]
    if missing:
        shown = "、".join(missing[:5]) + (f" 等 {len(missing)} 个" if len(missing) > 5 else "")
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=f"文件不存在: {shown}")
    try:
        task_uuid = _start_detection_task(knowledge_base_name, file_paths)
    except Exception as e:
        logger.error(f"[conflict-detection] 创建批量任务失败: {e}", exc_info=True)
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=CREATE_BATCH_TASK_FAILED_MSG_TEMPLATE.format(error=e))
    return BaseResponse(code=HTTP_OK, msg=BATCH_CONFLICT_TASK_CREATED_MSG,
                        data={"task_id": task_uuid, "status": "QUEUED", "file_count": len(file_paths)})


def get_conflict_detection_auto_switch(knowledge_base_name: str = None) -> BaseResponse:
    """查询入库自动冲突检测开关（按知识库，默认关）。不带参数返回全部知识库的开关列表。"""
    if knowledge_base_name:
        if not validate_kb_name(knowledge_base_name):
            return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
        return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG,
                            data={"knowledge_base_name": knowledge_base_name,
                                  "enabled": repo.get_switch(knowledge_base_name)})
    return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG, data={"items": repo.list_switches()})


def set_conflict_detection_auto_switch(
        knowledge_base_name: str = Body(..., description="知识库名称（kbId 或注册名）",
                                        examples=["kbfccb654a43f241cbb520174f30951e81"]),
        enabled: bool = Body(..., description="开关：true=上传入库后自动冲突检测；false=不检测（默认）"),
) -> BaseResponse:
    """设置入库自动冲突检测开关（按知识库，默认关；供前端页面调用）。"""
    if not validate_kb_name(knowledge_base_name):
        return BaseResponse(code=HTTP_FORBIDDEN, msg=SECURITY_ATTACK_MSG)
    if not isinstance(enabled, bool):
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=ENABLED_MUST_BE_BOOL_MSG)
    try:
        repo.set_switch(knowledge_base_name, enabled)
    except Exception as e:
        logger.error(f"[conflict-detection] 设置开关失败: {e}", exc_info=True)
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=SET_SWITCH_FAILED_MSG_TEMPLATE.format(error=e))
    logger.info(f"[conflict-detection] auto_switch kb={knowledge_base_name} enabled={enabled}")
    return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG,
                        data={"knowledge_base_name": knowledge_base_name, "enabled": enabled})


def trigger_auto_conflict_detection(kb_name: str, file_path: str) -> str | None:
    """入库链路自动触发入口（kb_doc_api 挂钩调用）：按库开关控制，默认关。

    绝不抛异常（best-effort，不影响入库主流程）。返回 task_id 或 None。
    """
    try:
        if not repo.get_switch(kb_name):
            return None
        if not file_path or not os.path.isfile(file_path):
            return None
        task_uuid = _start_detection_task(kb_name, [file_path])
        logger.info(f"[conflict-detection] 自动触发 {os.path.basename(file_path)} -> task={task_uuid[:8]}")
        return task_uuid
    except Exception as e:
        logger.warning(f"[conflict-detection] 自动触发失败（不影响入库）: {e}")
        return None


def get_conflict_detection_task(task_id: str) -> BaseResponse:
    """查询冲突检测任务状态、进度与冲突结果概要。"""
    _CONFLICTS_KEY = 'conflicts'
    task = repo.get_task(task_id)
    if not task:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=TASK_NOT_FOUND_MSG_TEMPLATE.format(task_id=task_id))
    data = dict(task)
    data["progress"] = {"done": task["done_chunks"], "total": task["total_chunks"]}
    if task["status"] == "SUCCEEDED" and task["result_file"]:
        try:
            with open(task["result_file"], encoding="utf-8") as f:
                result = json.load(f)
            data[_CONFLICTS_KEY] = result.get(_CONFLICTS_KEY, [])
            data["skipped_pairs"] = result.get("skipped_pairs", 0)
            data["files"] = result.get("files", [])
        except Exception as e:
            data[_CONFLICTS_KEY] = []
            logger.warning(f"[conflict-detection] 读取结果文件失败 {task['result_file']}: {e}")
    return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG, data=data)


def get_conflict_detection_task_results(task_id: str, file_path: str | None = None) -> BaseResponse:
    """查询冲突检测任务的结果明细（完整结果 JSON，按文件分组；file_path 可过滤单文件）。"""
    task = repo.get_task(task_id)
    if not task:
        return BaseResponse(code=HTTP_NOT_FOUND, msg=TASK_NOT_FOUND_MSG_TEMPLATE.format(task_id=task_id))
    if task["status"] != "SUCCEEDED" or not task["result_file"]:
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=RESULT_FILE_MISSING_MSG_TEMPLATE.format(status=task['status']))
    try:
        with open(task["result_file"], encoding="utf-8") as f:
            result = json.load(f)
    except Exception as e:
        logger.error(f"[conflict-detection] 读取结果文件失败 {task['result_file']}: {e}")
        return BaseResponse(code=HTTP_INTERNAL_ERROR, msg=READ_RESULT_FAILED_MSG_TEMPLATE.format(error=e))
    if file_path:
        files = [x for x in result.get("files", []) if x.get("file_path") == file_path]
        if not files:
            return BaseResponse(code=HTTP_NOT_FOUND, msg=RESULT_FILE_NOT_FOUND_MSG_TEMPLATE.format(file_path=file_path))
        result = {**result, "files": files,
                  "conflicts": files[0].get("conflicts", []),
                  "conflict_count": files[0].get("conflict_count", 0)}
    return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG, data=result)


def list_conflict_detection_tasks(
        page: int = 1,
        page_size: int = 10,
        status: str | None = None,
) -> BaseResponse:
    """冲突检测任务列表（分页，可按状态筛选：QUEUED/RUNNING/SUCCEEDED/FAILED）。"""
    page = max(1, page)
    page_size = 10 if page_size < 1 or page_size > 100 else page_size
    items, total = repo.list_tasks(page=page, page_size=page_size, status=status)
    return BaseResponse(code=HTTP_OK, msg=SUCCESS_MSG,
                        data={"total": total, "page": page, "page_size": page_size, "items": items})


# ==================== 检测流水线 ====================

def _start_detection_task(kb_name: str, file_paths: list[str]) -> str:
    """落库 QUEUED 并启动后台检测线程（REST 端点与入库自动触发共用）。"""
    task_uuid = repo.create_task(kb_name, file_paths[0], file_paths=file_paths)
    threading.Thread(target=_run_detection, args=(task_uuid,), daemon=True,
                     name=f"conflict-detect-{task_uuid[:8]}").start()
    return task_uuid


def _parse_file_to_chunks(file_path: str, kb_name: str) -> list[str]:
    """复用入库解析链路（KnowledgeFile loader + 切分）拿到文本 chunks。"""
    kw = KnowledgeFile(filename=file_path, knowledge_base_name=kb_name)
    kw.filepath = file_path  # 本地路径检测，filename 即 filepath
    docs = kw.file2text()
    return [d.page_content.strip() for d in (docs or [])
            if d.page_content and len(d.page_content.strip()) >= _CHUNK_MIN_LEN]


def _mempalace_base() -> str:
    return MEMPALACE_BASE_URL or os.environ.get("MEMPALACE_REST_URL", "http://haisi-mempalace-rest:8777")


def _search_candidates(query: str, kb_name: str, top_k: int) -> list[dict]:
    """MemPalace 混合检索（向量 + BM25）。失败抛 ConflictDetectionError（主流程显式失败）。"""
    # 空查询防御：MemPalace /search 对 q 有 min_length=1 校验，空串直返 422
    # （空 chunk 常见于 md 表格/图片段/空标题——跳过而非失败，见 _detect_chunk）
    query = (query or "").strip()
    if not query:
        return []
    params = urllib.parse.urlencode({"q": query, "palace": kb_name,
                                     "limit": top_k, "detail": "full"})
    url = f"{_mempalace_base()}/search?{params}"
    try:
        with urllib.request.urlopen(url, timeout=HTTP_UPLOAD_TIMEOUT_SEC) as r:
            data = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise ConflictDetectionError(f"MemPalace 检索 HTTP {e.code}: {url.split('?')[0]}") from e
    except Exception as e:
        raise ConflictDetectionError(f"MemPalace 检索失败: {e}") from e
    results = []
    for h in data.get("results") or []:
        text = (h.get("text") or "").strip()
        if not text:
            continue
        sim = h.get("similarity")
        results.append({
            "text": text,
            "source_file": h.get("source_file") or "",
            "source_path": h.get("source_path") or "",
            "similarity": float(sim) if sim is not None else 0.0,
        })
    return results


def _as_confidence(v) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return max(0.0, min(1.0, f))


def _judge_pair_llm(new_text: str, old_text: str, kb_name: str) -> dict | None:
    """LLM 精判一对文本是否冲突。失败/输出非法返回 None（跳过该对，不失败任务）。"""
    _API_KEY_FIELD = 'api_key'
    _API_BASE_URL_FIELD = 'api_base_url'
    from server.utils import get_model_worker_config
    config = get_model_worker_config(CONFLICT_LLM_MODEL)
    model_name = CONFLICT_LLM_MODEL
    if not config.get(_API_KEY_FIELD) or not config.get(_API_BASE_URL_FIELD):
        from configs import LLM_MODELS
        for _m in LLM_MODELS:
            _c = get_model_worker_config(_m)
            if _c.get(_API_KEY_FIELD) and _c.get(_API_BASE_URL_FIELD):
                config, model_name = _c, _m
                break
        else:
            logger.warning("[conflict-detection] 无可用在线 LLM 配置，跳过判定")
            return None

    prompt = CONFLICT_JUDGE_PROMPT.format(kb=kb_name,
                                          new_text=new_text[:2000],
                                          old_text=old_text[:2000])
    content = ""
    for attempt in range(2):  # 整体重试 1 次
        try:
            from openai import OpenAI
            client = OpenAI(api_key=config.get(_API_KEY_FIELD),
                            base_url=config.get(_API_BASE_URL_FIELD), timeout=HTTP_INDEX_TIMEOUT_SEC)
            resp = client.chat.completions.create(
                model=config.get("model_id", model_name),
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1,
                max_tokens=512,
            )
            content = resp.choices[0].message.content or ""
            break
        except Exception as e:
            if attempt > 0:
                logger.warning(f"[conflict-detection] LLM 判定失败: {e}")
                return None

    m = re.search(r"<json>\s*(\{.*?\})\s*</json>", content, re.S) \
        or re.search(r"(\{.*\})", content, re.S)
    if not m:
        return None
    try:
        r = json.loads(m.group(1))
    except json.JSONDecodeError:
        return None
    return r if isinstance(r, dict) and "is_conflict" in r else None


def _detect_chunk(idx: int, chunk: str, kb_name: str, file_basename: str,
                  abort: threading.Event) -> tuple[list[dict], int]:
    """检测单个 chunk：检索 → 自匹配过滤 + 粗筛 → LLM 逐对精判。"""
    _TEXT_KEY = 'text'
    _CONFIDENCE_KEY = 'confidence'
    _SIMILARITY_KEY = 'similarity'
    if abort.is_set():
        return [], 0
    # 空 chunk（纯空白/无正文段）无检索意义，跳过不计冲突——否则空 q 触发
    # MemPalace /search 422 导致整个任务 FAILED
    if not (chunk or "").strip():
        return [], 0
    candidates = _search_candidates(chunk[:CONFLICT_QUERY_TRUNC], kb_name, CONFLICT_TOP_K)
    _filtered = []
    for c in candidates:
        if (c["source_file"] != file_basename      # 同名文件已入库时排除自身
                and c[_TEXT_KEY] != chunk                    # 文本全等的自匹配
                and c[_SIMILARITY_KEY] >= CONFLICT_SIM_THRESHOLD):
            _filtered.append(c)
    candidates = _filtered
    conflicts, skipped = [], 0
    for c in candidates[:CONFLICT_MAX_PAIRS]:
        r = _judge_pair_llm(chunk, c[_TEXT_KEY], kb_name)
        if r is None:
            skipped += 1
            continue
        if r.get("is_conflict"):
            suggestion = r.get("suggestion")
            conflicts.append({
                "type": r.get("type"),
                "conflict_point": r.get("conflict_point") or "",
                _CONFIDENCE_KEY: _as_confidence(r.get(_CONFIDENCE_KEY)),
                "suggestion": suggestion if suggestion in _SUGGESTIONS else None,
                "reason": r.get("reason") or "",
                "new_text_excerpt": chunk[:_TEXT_EXCERPT_LEN],
                "old_text_excerpt": c[_TEXT_KEY][:_TEXT_EXCERPT_LEN],
                "old_source_file": c["source_file"],
                "old_source_path": c["source_path"],
                "new_chunk_index": idx,
                _SIMILARITY_KEY: round(c[_SIMILARITY_KEY], 4),
            })
    return conflicts, skipped


def _dedupe_conflicts(conflicts: list[dict]) -> list[dict]:
    """不同 chunk 可能召回同一旧片段，按 类型+旧来源+冲突点 去重（保留置信度更高的一条）。"""
    _CONFIDENCE_KEY = 'confidence'
    seen: dict[tuple, dict] = {}
    for c in conflicts:
        key = (c.get("type"), c.get("old_source_file"), c.get("conflict_point"))
        cur = seen.get(key)
        if cur is None or (c.get(_CONFIDENCE_KEY) or 0) > (cur.get(_CONFIDENCE_KEY) or 0):
            seen[key] = c
    return sorted(seen.values(), key=lambda c: -(c.get(_CONFIDENCE_KEY) or 0))


def _write_result(task_uuid: str, payload: dict) -> str:
    out_dir = os.path.join(KW_ROOT_PATH, "conflict-detections")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{task_uuid}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    return path


def _finish(task_uuid: str, status: str, error: str | None, task: dict,
            **kwargs) -> None:
    """收尾：更新 task 状态、写结果文件、记日志。

    后 4 个参数（通过 kwargs 传入，避免签名参数过多）:
        file_paths: list[str] - 涉及的文件路径列表（必传）
        file_states: list[dict] - 每个文件的状态记录（必传）
        done: int - 已完成 chunk 数（必传）
        skipped_pairs: int - 跳过的冲突对数（必传）
    """
    _STATUS_KEY = 'status'
    _FILE_PATH_KEY = 'file_path'
    _ERROR_KEY = 'error'
    _TOTAL_CHUNKS_KEY = 'total_chunks'
    _DONE_CHUNKS_KEY = 'done_chunks'
    _CONFLICTS_KEY = 'conflicts'
    _CONFIDENCE_KEY = 'confidence'
    file_paths = kwargs.get("file_paths", [])
    file_states = kwargs.get("file_states", [])
    done = kwargs.get("done", 0)
    skipped_pairs = kwargs.get("skipped_pairs", 0)
    conflicts_all: list[dict] = []
    files_out = []
    for f in file_states:
        f_conflicts = _dedupe_conflicts(f.get(_CONFLICTS_KEY) or [])
        for c in f_conflicts:
            c[_FILE_PATH_KEY] = f[_FILE_PATH_KEY]
        files_out.append({
            _FILE_PATH_KEY: f[_FILE_PATH_KEY],
            _STATUS_KEY: f.get(_STATUS_KEY, "ok"),
            _ERROR_KEY: f.get(_ERROR_KEY),
            _TOTAL_CHUNKS_KEY: f.get(_TOTAL_CHUNKS_KEY, 0),
            _DONE_CHUNKS_KEY: f.get(_DONE_CHUNKS_KEY, 0),
            "conflict_count": len(f_conflicts),
            _CONFLICTS_KEY: f_conflicts,
        })
        conflicts_all.extend(f_conflicts)
    conflicts_all.sort(key=lambda c: -(c.get(_CONFIDENCE_KEY) or 0))
    result_file = None
    if status == "SUCCEEDED":
        payload = {
            "task_id": task_uuid,
            "knowledge_base_name": task["knowledge_base_name"],
            _FILE_PATH_KEY: file_paths[0] if file_paths else None,
            "file_paths": file_paths,
            "file_count": len(file_paths),
            _STATUS_KEY: status,
            "created_at": task.get("created_at"),
            "finished_at": str(datetime.now(timezone.utc).replace(tzinfo=None)),
            _TOTAL_CHUNKS_KEY: task.get(_TOTAL_CHUNKS_KEY, 0),
            _DONE_CHUNKS_KEY: done,
            "skipped_pairs": skipped_pairs,
            "conflict_count": len(conflicts_all),
            "files": files_out,
            _CONFLICTS_KEY: conflicts_all,
        }
        try:
            result_file = _write_result(task_uuid, payload)
        except Exception as e:
            logger.error(f"[conflict-detection] 写结果文件失败: {e}", exc_info=True)
            status, error = "FAILED", f"写结果文件失败: {e}"
    repo.update_task(task_uuid, status=status, error=error,
                     done_chunks=done, conflict_count=len(conflicts_all),
                     result_file=result_file, finished_at=datetime.now(timezone.utc).replace(tzinfo=None))
    logger.info(f"[conflict-detection] task={task_uuid} 终态={status} files={len(file_paths)} "
                f"conflicts={len(conflicts_all)} error={error}")


def _run_detection(task_uuid: str) -> None:
    _STATUS_KEY = 'status'
    _FILE_PATH_KEY = 'file_path'
    _ERROR_KEY = 'error'
    _TOTAL_CHUNKS_KEY = 'total_chunks'
    _DONE_CHUNKS_KEY = 'done_chunks'
    _CONFLICTS_KEY = 'conflicts'
    _RUNNING_KEY = 'RUNNING'
    _FAILED_KEY = 'FAILED'
    task = repo.get_task(task_uuid)
    if not task:
        return
    kb_name = task["knowledge_base_name"]
    file_paths = task.get("file_paths") or [task[_FILE_PATH_KEY]]
    repo.update_task(task_uuid, status=_RUNNING_KEY, started_at=datetime.now(timezone.utc).replace(tzinfo=None))
    # 每文件状态：解析失败记 per-file error 并跳过，不影响其它文件；全部失败才任务 FAILED
    file_states = [{_FILE_PATH_KEY: p, _STATUS_KEY: "ok", _ERROR_KEY: None,
                    _TOTAL_CHUNKS_KEY: 0, _DONE_CHUNKS_KEY: 0, _CONFLICTS_KEY: []}
                   for p in file_paths]
    skipped_pairs, done = 0, 0
    try:
        chunk_jobs = []  # (file_idx, chunk_idx, chunk)
        for fi, p in enumerate(file_paths):
            try:
                chunks = _parse_file_to_chunks(p, kb_name)
            except Exception as e:
                file_states[fi][_STATUS_KEY] = _ERROR_KEY
                file_states[fi][_ERROR_KEY] = str(e)
                logger.warning(f"[conflict-detection] task={task_uuid} 文件解析失败 {p}: {e}")
                continue
            file_states[fi][_TOTAL_CHUNKS_KEY] = len(chunks)
            chunk_jobs.extend((fi, ci, ch) for ci, ch in enumerate(chunks))
        if file_paths and all(f[_STATUS_KEY] == _ERROR_KEY for f in file_states):
            err = "；".join(f"{f[_FILE_PATH_KEY]}: {f[_ERROR_KEY]}" for f in file_states)[:1900]
            _finish(task_uuid, _FAILED_KEY, err, task,
                    file_paths=file_paths, file_states=file_states,
                    done=done, skipped_pairs=skipped_pairs)
            return
        task[_TOTAL_CHUNKS_KEY] = len(chunk_jobs)
        repo.update_task(task_uuid, total_chunks=len(chunk_jobs))
        abort = threading.Event()
        lock = threading.Lock()
        if chunk_jobs:
            with ThreadPoolExecutor(max_workers=CONFLICT_WORKERS) as ex:
                futures = {ex.submit(_detect_chunk, ci, ch, kb_name,
                                     os.path.basename(file_paths[fi]), abort): (fi, ci)
                           for fi, ci, ch in chunk_jobs}
                for fut in as_completed(futures):
                    fi, _ = futures[fut]
                    try:
                        chunk_conflicts, chunk_skipped = fut.result()
                    except ConflictDetectionError as e:
                        abort.set()
                        logger.error(f"[conflict-detection] task={task_uuid} 失败: {e}")
                        _finish(task_uuid, _FAILED_KEY, str(e), task,
                                file_paths=file_paths, file_states=file_states,
                                done=done, skipped_pairs=skipped_pairs)
                        return
                    with lock:
                        file_states[fi][_CONFLICTS_KEY].extend(chunk_conflicts)
                        file_states[fi][_DONE_CHUNKS_KEY] += 1
                        skipped_pairs += chunk_skipped
                        done += 1
                        repo.update_task(task_uuid, done_chunks=done)
        _finish(task_uuid, "SUCCEEDED", None, task,
                file_paths=file_paths, file_states=file_states,
                done=done, skipped_pairs=skipped_pairs)
    except Exception as e:
        logger.error(f"[conflict-detection] task={task_uuid} 失败: {e}", exc_info=True)
        _finish(task_uuid, _FAILED_KEY, str(e), task,
                file_paths=file_paths, file_states=file_states,
                done=done, skipped_pairs=skipped_pairs)
