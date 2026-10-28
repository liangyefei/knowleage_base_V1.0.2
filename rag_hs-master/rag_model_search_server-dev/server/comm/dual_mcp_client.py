#!/usr/bin/env python3
"""
dual-mcp 容器客户端（RAG 服务侧共享模块）。

约定：kbId == dual-mcp 项目名。新建知识库（CreateKnowledgeTask）后触发建索引，
上传代码文件（_UploadDocumentTask）后触发重索引；检索侧（knowledge_base_chat）
用 resolve_project 把 kbId 解析成 dual-mcp 项目名，命中才走代码检索路。

所有调用都是后台线程 + 静默降级：dual-mcp 挂掉或项目未挂载，只记日志，
绝不阻塞/中断 RAG 主链路。
"""

import os
import re
import shutil
import threading
import time

from configs import kbs_config, logger
from server.comm.callback_utils import safe_str

_CODE_EXTS = {
    ".c", ".h", ".cc", ".cpp", ".hpp", ".cxx", ".py", ".pyx", ".java", ".kt",
    ".js", ".ts", ".jsx", ".tsx", ".go", ".rs", ".rb", ".php", ".sh", ".bash",
    ".cs", ".swift", ".m", ".mm", ".scala", ".pl", ".lua", ".r", ".sql",
    ".vim", ".zsh", ".fish", ".proto", ".vue", ".svelte", ".asm", ".s",
}

_BASE_URL: str | None = None


def get_base_url() -> str:
    """dual-mcp 地址：kbs_config.dual_mcp.base_url → kbs_config dict → 默认值。"""
    global _BASE_URL
    if _BASE_URL:
        return _BASE_URL
    try:
        from configs.kb_loader import get_config
        v = get_config("kbs_config.dual_mcp.base_url", None)
        if v:
            _BASE_URL = v
            return _BASE_URL
    except Exception as e:
        logger.warning(f"[dual-mcp] kb_loader 不可用: {safe_str(e)}")
    try:
        v = kbs_config.get("dual_mcp", {}).get("base_url")
        if v:
            _BASE_URL = v
            return _BASE_URL
    except Exception as e:
        logger.warning(f"[dual-mcp] failed reading base_url from kbs_config: {safe_str(e)}, use default")
        pass
    _BASE_URL = "http://localhost:9789"
    return _BASE_URL


def _http(method: str, path: str, **kwargs):
    """同步 HTTP 调用（复用 server.utils 的 httpx 封装，自动绕本地代理）。"""
    from server.utils import get_httpx_client
    with get_httpx_client(use_async=False, timeout=15) as client:
        return getattr(client, method)(get_base_url() + path, **kwargs)


def normalize(s: str) -> str:
    """规范化：小写 + 去掉非字母数字（projects-hi3516cv610 → projectshi3516cv610）。"""
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def resolve_project(kb_id: str, project_path: str | None = None) -> str | None:
    """kbId → dual-mcp 项目名。

    四级解析：精确相等 → root_path 精确相等（project_path 给定时）→
    规范化包含且唯一命中 → None（跳过代码路）。
    """
    _NAME_KEY = 'name'
    try:
        r = _http("get", "/projects")
        if r.status_code != 200:
            logger.warning(f"[dual-mcp] GET /projects -> {r.status_code}")
            return None
        projects = r.json().get("projects", [])
    except Exception as e:
        logger.warning(f"[dual-mcp] GET /projects 失败: {safe_str(e)}")
        return None

    exact = [p[_NAME_KEY] for p in projects if p[_NAME_KEY] == kb_id]
    if exact:
        return exact[0]

    if project_path:
        by_path = [p[_NAME_KEY] for p in projects
                   if p.get("root_path") == project_path]
        if len(by_path) == 1:
            return by_path[0]

    nkb = normalize(kb_id)
    hits = [p for p in projects if nkb and nkb in normalize(p[_NAME_KEY])]
    if len(hits) > 1:
        # 双项目并存（如建库空壳 projects-kb<kbId> 与 zip 变体
        # projects-kb<kbId>-code）：按图谱节点数择优，空壳让位给有数据的项目
        hits.sort(key=lambda p: p.get("nodes") or 0, reverse=True)
        best = hits[0]
        logger.warning(f"[dual-mcp] kbId '{kb_id}' 模糊命中多个项目 "
                       f"{[p[_NAME_KEY] for p in hits]}，按节点数取 {best[_NAME_KEY]}"
                       f"({best.get('nodes')})")
        return best[_NAME_KEY]
    if len(hits) == 1:
        return hits[0][_NAME_KEY]
    return None


def _poll_job(job_id: str, label: str) -> bool:
    """轮询 job 直到 done/failed。返回是否成功。"""
    last = None
    for _ in range(360):  # 5s * 360 = 30 分钟上限
        try:
            r = _http("get", f"/jobs/{job_id}")
            if r.status_code != 200:
                return False
            job = r.json()
            status = job.get("status")
            if status != last:
                stages = " ".join(f"{k}={safe_str(v)}" for k, v in job.get("stages", {}).items())
                logger.info(f"[dual-mcp] job {job_id} ({label}): {status} {stages}")
                last = status
            if status == "done":
                return True
            if status == "failed":
                logger.warning(f"[dual-mcp] job {job_id} ({label}) failed: {safe_str(job.get('error'))}")
                return False
        except Exception as e:
            logger.warning(f"[dual-mcp] polling job {job_id} encountered error, will retry: {safe_str(e)}")
            pass
        time.sleep(5)
    logger.warning(f"[dual-mcp] job {job_id} ({label}) 轮询超时")
    return False


def _sanitize_files_version(fv):
    """dual-mcp 端 schema 为 dict[str,int]；sha 字符串值（如 'd6e2b486'）会被
    422 拒绝（整个建索引/重索引调用随之失败）。值非全 int 时省略该字段，
    图谱照常重建（file_version 透出是增强项，不应阻断触发）。"""
    if not isinstance(fv, dict) or not fv:
        return None
    if all(isinstance(v, int) and not isinstance(v, bool) for v in fv.values()):
        return fv
    logger.warning("[dual-mcp] files_version 含非 int 值，已省略（dual-mcp schema=dict[str,int]）")
    return None


def trigger_index_async(kb_id: str, project_path: str | None = None,
                        files_version: dict | None = None):
    """后台线程触发建索引：POST /projects {"path", "name": kbId} + 轮询 job。

    项目路径默认 /projects/<kbId>（与容器挂载约定一致）；未挂载时服务端返 400，
    这里记日志后静默返回。files_version 为 per-file 版本映射
    （{相对路径: 版本号}，文件级多版本管理透出给 /query 结果）。
    """

    def _run():
        try:
            path = project_path or f"/projects/{kb_id}"
            body = {"path": path, "name": kb_id}
            fv = _sanitize_files_version(files_version)
            if fv:
                body["files_version"] = fv
            r = _http("post", "/projects", json=body)
            job_id = None
            if r.status_code == 200:
                job_id = r.json().get("job_id")
                logger.info(f"[dual-mcp] 建索引 job 已排队: {kb_id} -> {job_id}")
            elif r.status_code == 409:
                # 已有 running job：捡回来接着轮询
                r2 = _http("get", "/jobs", params={"status": "running"})
                for j in r2.json().get("jobs", []):
                    if j.get("project_name") == kb_id or j.get("project_path") == path:
                        job_id = j.get("job_id")
                        logger.info(f"[dual-mcp] 捡回 running job: {kb_id} -> {job_id}")
                        break
            else:
                logger.warning(
                    f"[dual-mcp] 建索引触发失败 {kb_id} (path={path}): "
                    f"{r.status_code} {safe_str(r.text[:200])}")
                return
            if job_id:
                _poll_job(job_id, kb_id)
        except Exception as e:
            logger.warning(f"[dual-mcp] 建索引异常 {kb_id}: {safe_str(e)}")

    threading.Thread(target=_run, daemon=True,
                     name=f"dual-mcp-index-{kb_id}").start()


def trigger_reindex_async(kb_id: str, files_version: dict | None = None,
                          project_path: str | None = None):
    """后台线程触发重索引：POST /projects/{name}/reindex（上传代码文件后保持图新鲜）。

    files_version 为 per-file 版本映射，随重索引请求带给 dual-mcp 透出。
    project_path 为库文件树根：文档暂存树布局（/projects/kb/<库名>）与
    /projects/<kbId> 挂载约定不一致时由调用方显式给出，未建图时按该路径首建。
    """

    def _run():
        try:
            project = resolve_project(kb_id, project_path=project_path)
            if not project:
                # 项目尚未建索引 → 按给定/约定 kb 根路径建索引（覆盖 code/ 子目录与根目录文件）
                trigger_index_async(kb_id, project_path=project_path,
                                    files_version=files_version)
                return
            fv = _sanitize_files_version(files_version)
            r = _http("post", f"/projects/{project}/reindex",
                      json={"files_version": fv} if fv else None)
            if r.status_code in (200, 409):
                logger.info(f"[dual-mcp] reindex 已触发: {project} (HTTP {r.status_code})")
            else:
                logger.warning(f"[dual-mcp] reindex 触发失败 {project}: {r.status_code}")
        except Exception as e:
            logger.warning(f"[dual-mcp] reindex 异常 {kb_id}: {safe_str(e)}")

    threading.Thread(target=_run, daemon=True,
                     name=f"dual-mcp-reindex-{kb_id}").start()


def is_code_file(file_path: str) -> bool:
    """按扩展名判断是否代码文件。"""
    return os.path.splitext(file_path or "")[1].lower() in _CODE_EXTS


def kb_tree_root(file_paths: list) -> str | None:
    """从一批服务端文件路径推出知识库文件树根（文档暂存树布局）。

    文档上传/批量导入落在暂存树 kb/<库名>/…（pvc-kb-files 在本服务挂载为
    /kb-files、在 dual-mcp 挂载为 /projects，路径前缀随部署而变），与
    /projects/<kbId> 项目约定不一致。这里按 "/kb/<库名>/" 段识别库名，
    返回 dual-mcp 容器视角的库树根 /projects/kb/<库名>，保证按整棵库树
    建图（而非按批次子目录碎片化）。识别不出或跨多个库根时返回 None
    （调用方退回 /projects/<kbId> 默认约定；路径在 dual-mcp 不存在时
    POST /projects 返 400，仅记日志无副作用）。
    """
    marker = "/kb/"
    names = set()
    for f in file_paths or []:
        p = os.path.abspath(f)
        i = p.find(marker)
        if i == -1:
            continue
        first = p[i + len(marker):].split("/", 1)[0]
        if first and not first.startswith("."):
            names.add(first)
    if len(names) == 1:
        return "/".join(["/projects/kb", names.pop()])
    return None


def _sha256_file(path: str) -> str | None:
    """文件 sha256（读取失败返回 None）。"""
    import hashlib
    try:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def stage_code_files_to_kb_root(kb_id: str, file_paths: list) -> int:
    """把暂存树里的代码文件复制进 /kb-files/<kbId>/（dual-mcp 项目根）。

    use_path 上传的代码落在暂存树 /kb-files/kb/<库名>/…，而 dual-mcp 项目
    root=/projects/<kbId>——复制对齐后按默认路径重索引即可把代码纳入图谱，
    消除「resolve 命中空壳项目 → reindex 空目录」的死结。
    目标已存在且内容相同（sha256）时跳过；路径不在暂存树内时跳过。
    返回复制的文件数（异常降级不阻断主链路）。
    """
    tree = kb_tree_root(file_paths)
    if not tree:
        return 0
    try:
        from configs import KB_ROOT_PATH
    except Exception:
        KB_ROOT_PATH = "/kb-files"
    tree_local = os.path.join(KB_ROOT_PATH,
                              tree[len("/projects/"):].lstrip("/"))
    dst_root = os.path.join(KB_ROOT_PATH, kb_id)
    copied = 0
    for f in file_paths or []:
        if not is_code_file(f):
            continue
        src = os.path.abspath(f)
        rel = os.path.relpath(src, tree_local)
        if rel.startswith("..") or os.path.isabs(rel):
            continue
        dst = os.path.normpath(os.path.join(dst_root, rel))
        if os.path.isfile(dst):
            try:
                if os.path.getsize(dst) == os.path.getsize(src) \
                        and _sha256_file(dst) == _sha256_file(src):
                    continue
            except OSError:
                pass
        try:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            copied += 1
        except OSError as e:
            logger.warning(f"[dual-mcp] 代码暂存复制失败 {src} -> {dst}: {safe_str(e)}")
    if copied:
        logger.info(f"[dual-mcp] {kb_id}: {copied} 个代码文件暂存复制到 {dst_root}")
    return copied
