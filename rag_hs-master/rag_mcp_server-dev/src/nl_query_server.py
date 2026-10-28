#!/usr/bin/env python3
"""nl_query_server — 自然语言代码查询常驻 HTTP 服务（FastAPI）。

服务层：请求模型 + 端点 + 入口。功能实现按域拆分：
nlq_store（图谱存储/项目解析/文件版本）、nlq_jobs（项目导入 job）、
nlq_query（关键词提取与 /query 逻辑）、nlq_doc_code（文档↔代码映射）、
nlq_graph（图谱浏览）、nlq_mempalace（文档 KG 代理）、nlq_pages（原文查看/页面）。
"""
import argparse
import asyncio
import json
import os
import re
import signal
import subprocess
import time
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel, Field

from nlq_constants import (
    log, CACHE_DIR, CBM_BIN, CODEGRAPH_BIN, DOC_VIEW_BASE_URL, LOG_DIR,
    _K_DATA, _K_JOB_ID, _K_MATCHES, _K_STATUS,
)
from nlq_doc_code import _doc_code_mapping, DocCodeParams
from nlq_graph import _do_graph_node, _do_graph_search, _do_graph_suggest
from nlq_jobs import (
    _executor, _has_running_job_for, _jobs, _jobs_lock, _kill_codegraph_daemon_for,
    reap_daemon_procs,
    _new_job, _run_add_project_job, derive_project_name,
)
from nlq_mempalace import _do_graph_kg, _kg_fragment_fetch
from nlq_pages import (
    _GRAPH_HTML, _DOCMAP_HTML, _DOC_DOWNLOAD, _DOC_MIME, KB_FILES_ROOT,
    _doc_find, _doc_msg, _doc_text_html,
)
from nlq_query import _graph_payload, do_query
from nlq_store import (
    _STORES, _files_version_path, get_store, list_projects, list_projects_cached,
    resolve_project_name, _save_files_version,
)

# ── FastAPI 应用 ─────────────────────────────────────────────────────


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """启动/关闭时打印信息。"""
    log.info(f"图谱缓存目录: {CACHE_DIR}")
    projs = list_projects_cached()  # 走缓存版：启动即预热，首个请求不付 ~3.5s 冷查
    log.info(f"已索引项目: {len(projs)} 个")

    async def _daemon_reaper():
        while True:
            await asyncio.sleep(60)
            try:
                reap_daemon_procs()
            except Exception as e:
                log.warning(f"daemon 子进程回收异常: {e}")

    reaper_task = asyncio.get_event_loop().create_task(_daemon_reaper())
    yield
    reaper_task.cancel()
    for s in _STORES.values():
        s.close()


app = FastAPI(
    title="nl-query-server",
    description="自然语言查询代码知识图谱 API（直接读 CBM SQLite，毫秒级延迟）",
    version="1.0.0",
    lifespan=lifespan,
    openapi_url=None,
    docs_url=None,
    redoc_url=None,
)


@app.get("/health")
async def health():
    """健康检查：服务状态、已缓存连接数。"""
    return {
        "ok": True,
        "service": "nl-query",
        "cache_dir": CACHE_DIR,
        "cached_projects": list(_STORES.keys()),
    }


@app.get("/projects")
async def projects():
    """列出所有已索引的项目及其图谱规模。"""
    return {"projects": await asyncio.to_thread(list_projects_cached)}


from pydantic import BaseModel, Field

class QueryRequest(BaseModel):
    q: str = Field(description="自然语言问题（中文或英文）")
    project: str = Field("data-hunan-bms", description="项目名")
    top: int = Field(6, ge=1, le=50, description="返回节点数上限")
    expand: bool = Field(True, description="是否展开调用关系子图")
    format: str = Field("json", description="输出格式: json | text | graph")
    include_source: int = Field(0, ge=0, le=20,
                                description="前 N 个命中附完整源码（读源文件，0=关闭）")

@app.post("/query")
async def query(req: QueryRequest):
    """自然语言查询代码知识图谱。

    接受中文或英文自然语言问题，自动提取关键词（含中文→英文翻译），
    在 SQLite 知识图谱中检索匹配的代码符号，返回命中节点及其调用关系子图。

    示例:
      /query?q=数据库连接初始化&project=data-hunan-bms
      /query?q=配置加载&project=data-hunan-bms&top=8&format=text
    """
    try:
        # SQLite 检索是阻塞调用，必须放线程池——否则单事件循环被独占，
        # 所有并发 /query、/projects 请求全部串行排队。
        def _run():
            project = resolve_project_name(req.project)
            return do_query(req.q, project, req.top, req.expand,
                            req.include_source)
        result = await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e

    if req.format == "text":
        from fastapi.responses import PlainTextResponse
        lines = [f"查询: {req.q}  ({result['elapsed_ms']}ms)\n"]
        if not result[_K_MATCHES]:
            return PlainTextResponse("未找到匹配。\n")
        lines.append(f"命中 {len(result['matches'])} 个:\n")
        for h in result[_K_MATCHES]:
            deg = h["in_degree"] + h["out_degree"]
            star = "★" if deg > 50 else " "
            lines.append(f"{star} [{h['label']}] {h['name']}")
            if h.get("file_path"):
                lines.append(f"    📄 {h['file_path']}:{h.get('start_line','?')}"
                             f"  (in:{h['in_degree']} out:{h['out_degree']})")
        sg = result["subgraph"]
        lines.append(f"\n知识子图 ({len(sg['nodes'])} 节点, {len(sg['edges'])} 边):\n")
        for h in result[_K_MATCHES]:
            nm = h["name"]
            in_e = [e for e in sg["edges"] if e.get("dst") == nm and e["type"] == "CALLS"]
            out_e = [e for e in sg["edges"] if e.get("src") == nm and e["type"] == "CALLS"]
            if in_e or out_e:
                lines.append(f"● {nm}")
                for e in in_e[:6]:
                    lines.append(f"    ← CALLS {e['src']}")
                for e in out_e[:6]:
                    lines.append(f"    → CALLS {e['dst']}")
        return PlainTextResponse("\n".join(lines) + "\n")

    if req.format == "graph":
        return _graph_payload(result)

    return result


class DocCodeRequest(BaseModel):
    file: str = Field(description="文档名或代码文件名（如 README.md / osal_device.c，也接受路径片段）")
    project: str = Field("data-hunan-bms", description="kbId 或已索引项目名")
    direction: str = Field("auto", description="auto=自动判向 | doc2code=文档查代码 | code2doc=代码查文档")
    limit: int = Field(20, ge=1, le=200, description="映射行数上限")
    with_source: int = Field(0, ge=0, le=20, description="附前 N 个符号的源码段（直读源文件，0=不附）")
    with_doc: int = Field(0, ge=0, le=20, description="附前 N 个文档的文档块（按引用行号锚点取窗口，0=不附）")
    exact: bool = Field(False, description="文档名精确匹配（默认路径包含匹配）")


@app.post("/doc_code")
async def doc_code(req: DocCodeRequest):
    """文档↔代码双向映射查询（基于索引时生成的 DOCUMENTS_CODE 边）。

    输入一个文件名自动判向：命中文档 → 返回该文档引用的代码符号清单
    （doc2code）；命中代码文件 → 返回引用它的文档清单（code2doc）。
    可选附源码段（with_source）与文档块（with_doc）。

    示例:
      /doc_code {"file": "osal_device.c", "project": "<kbId>", "with_source": 3, "with_doc": 3}
      /doc_code {"file": "SKILL.md", "project": "<kbId>", "direction": "doc2code", "exact": true}
    """
    if req.direction not in ("auto", "doc2code", "code2doc"):
        raise HTTPException(status_code=400,
                            detail="direction 须为 auto | doc2code | code2doc")
    try:
        def _run():
            project = resolve_project_name(req.project)
            return _doc_code_mapping(DocCodeParams(
                file_name=req.file, project=project, direction=req.direction,
                limit=req.limit, with_source=req.with_source,
                with_doc=req.with_doc, exact=req.exact))
        return await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


class DocCodeFilesRequest(BaseModel):
    project: str = Field("data-hunan-bms", description="kbId 或已索引项目名")
    type: str = Field("code", description="code=已建立映射的代码文件列表 | doc=已建立映射的文档文件列表")


def _doc_view_base(request: Request) -> str:
    """「查看原文」链接基址：DOC_VIEW_BASE_URL 显式覆盖，否则按请求
    Host（含 X-Forwarded-Host 代理场景）推导；均缺省时返回相对路径。"""
    if DOC_VIEW_BASE_URL:
        return DOC_VIEW_BASE_URL
    host = (request.headers.get("x-forwarded-host")
            or request.headers.get("host") or "")
    if not host:
        return ""
    scheme = (request.headers.get("x-forwarded-proto")
              or request.url.scheme or "http")
    return f"{scheme}://{host}"


@app.post("/doc_code/files")
async def doc_code_files(req: DocCodeFilesRequest, request: Request):
    """已建立文档↔代码映射关系的文件清单（供 codemap 页浏览映射全貌）。

    输入知识库 id，按类型返回存在 DOCUMENTS_CODE 映射边的文件列表：
      type=code → 被文档引用过的代码文件（含各文件映射边数 refs）
      type=doc  → 引用过代码符号的文档文件
    每项附 url=「查看原文」跳转链接（GET /doc_view）。
    空列表=该库尚无映射（数据真值）。

    示例:
      /doc_code/files {"project": "<kbId>", "type": "code"}
    """
    if req.type not in ("code", "doc"):
        raise HTTPException(status_code=400,
                            detail="type 须为 code | doc")
    try:
        def _run():
            import urllib.parse
            project = resolve_project_name(req.project)
            kb = req.project
            _km = re.search(r"kb[0-9a-f]{20,}", kb or "")
            if _km:
                kb = _km.group(0)
            t0 = time.time()
            files = get_store(project).mapped_files(req.type)
            base = _doc_view_base(request)
            for f in files:
                if req.type == "code":
                    f["url"] = (f"{base}/doc_view?project="
                                f"{urllib.parse.quote(project, safe='')}"
                                f"&file={urllib.parse.quote(f['file'], safe='')}")
                else:
                    f["url"] = (f"{base}/doc_view?doc="
                                f"{urllib.parse.quote(f['name'], safe='')}"
                                f"&kb={urllib.parse.quote(kb, safe='')}")
            return {"project": project, "type": req.type,
                    "count": len(files), "files": files,
                    "elapsed_ms": round((time.time() - t0) * 1000, 1)}
        return await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


class GraphSearchRequest(BaseModel):
    q: str = Field(description="实体关键字（符号名/文档名/文件名，支持部分匹配）")
    project: str = Field("data-hunan-bms", description="kbId 或已索引项目名")
    limit: int = Field(80, ge=1, le=500, description="邻接边数上限")
    candidates: int = Field(6, ge=0, le=20, description="同名的其他匹配实体数")


@app.post("/graph_search")
async def graph_search(req: GraphSearchRequest):
    """知识图谱邻接查询：按关键字定位中心实体，返回其相邻实体与关系
    （任意边类型：CALLS/DOCUMENTS_CODE/IMPORTS/…，含边属性锚点）。

    供 /graph 页面（GET /graph）使用；也可独立调用。
    """
    try:
        def _run():
            project = resolve_project_name(req.project)
            return _do_graph_search(req.q, project, req.limit, req.candidates)
        return await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


class GraphNodeRequest(BaseModel):
    name: str = Field(description="实体名")
    id: int | None = Field(None, description="节点 id（图上点击时携带，优先于名）")
    project: str = Field("data-hunan-bms", description="kbId 或已索引项目名")
    file_path: str = Field("", description="同名消歧用的 file_path 提示（后缀匹配）")
    with_source: bool = Field(True, description="附源码段（直读源文件，0 行节点无）")


@app.post("/graph_node")
async def graph_node(req: GraphNodeRequest):
    """实体详情：节点属性（label/文件/行号/度数）+ 邻边类型统计 + 源码段。
    图上点击节点时由右侧属性面板调用。"""
    try:
        def _run():
            project = resolve_project_name(req.project)
            return _do_graph_node(req.name, project, req.id, req.file_path,
                                  req.with_source)
        return await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


class GraphSuggestRequest(BaseModel):
    q: str = Field(description="实体名片段（如 LED）")
    project: str = Field("data-hunan-bms", description="kbId 或已索引项目名")
    limit: int = Field(10, ge=1, le=30, description="候选条数")
    palace: str | None = Field(None, description="按知识库过滤 KG 联想源（=kbId；空=全局，或从 project 推导）")


@app.post("/graph_suggest")
async def graph_suggest(req: GraphSuggestRequest):
    """搜索框联想：按片段返回实体候选（名称/类型/文件/度数）。
    供 /graph 页面输入时下拉提示，点击候选项即以该实体为中心查询。"""
    try:
        def _run():
            project = resolve_project_name(req.project)
            return _do_graph_suggest(req.q, project, req.limit, palace=req.palace)
        return await asyncio.to_thread(_run)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


class GraphKgRequest(BaseModel):
    q: str = Field(description="mempalace KG 实体名（精确匹配：文档名/kbId/普通实体）")
    palace: str | None = Field(None, description="知识库 kbId；提供时只查该库文档抽取的三元组（不提供=全局图）")


@app.post("/graph_kg")
async def graph_kg(req: GraphKgRequest):
    """文档知识图谱查询（代理 mempalace REST /kg?format=graph）。

    返回实体的一跳邻接（nodes+edges，ECharts 可直接消费）。
    KG 为 mempalace 全局单图；实体名精确匹配，如 "BSP FAQ.md"、
    kbId、或抽取出的普通实体。超长实体名（如 has_content 全文）截断展示。
    """
    return await asyncio.to_thread(_do_graph_kg, req.q, req.palace)


class GraphFragmentRequest(BaseModel):
    subject: str = Field(description="关系主体实体名")
    predicate: str = Field("", description="关系谓词（可空）")
    object: str = Field("", description="关系客体实体名")
    palace: str | None = Field(None, description="按知识库查找（=kbId）")


@app.post("/graph_fragment")
async def graph_fragment(req: GraphFragmentRequest):
    """按文档知识图谱关系定位来源文档片段（/graph 页面边「片段」按钮）。

    代理 mempalace GET /kg/fragment：三元组 → source_file → room 匹配
    drawer → 文本含主体/客体的 chunk，返回片段文本与全路径来源。
    """
    t0 = time.time()
    try:
        d = await asyncio.to_thread(
            _kg_fragment_fetch, req.subject, req.predicate, req.object,
            req.palace)
    except Exception as e:
        return {"subject": req.subject, "predicate": req.predicate,
                "object": req.object,
                "count": 0, "fragments": [],
                "error": f"mempalace /kg/fragment 不可达: {type(e).__name__}: {e}",
                "elapsed_ms": round((time.time() - t0) * 1000, 1)}
    return {**d,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


@app.get("/doc_view")
async def doc_view(project: str = "", file: str = "", doc: str = "",
                   kb: str = "", line: int = 0):
    """原文查看页（图谱节点「查看原文」的跳转目标）。

    两种入口：
      project+file —— 代码图谱节点（项目内相对路径，可带 line 定位行）
      doc+kb       —— KG 文档实体（按文件名在知识库目录树定位）

    pdf/图片浏览器原生预览；office 文档触发下载；文本类渲染行号页
    （URL 加 #L<行号> 锚点可直接定位高亮）。
    """
    path = None
    title = file or doc
    if project and file:
        try:
            def _run():
                store = get_store(resolve_project_name(project))
                root = os.path.realpath(store.root_path or KB_FILES_ROOT)
                cand = os.path.realpath(os.path.join(root, file))
                return root, cand
            root, cand = await asyncio.to_thread(_run)
        except FileNotFoundError as e:
            return HTMLResponse(_doc_msg(str(e)), status_code=404)
        if not cand.startswith(root + os.sep) or not os.path.isfile(cand):
            return HTMLResponse(_doc_msg(f"文件不存在或越界: {file}"), status_code=404)
        path = cand
    elif doc:
        path = await asyncio.to_thread(_doc_find, doc, kb)
        if path is None:
            return HTMLResponse(
                _doc_msg(f"未在知识库目录中找到文档 '{doc}'（kb={kb or '未指定'}）"),
                status_code=404)
    else:
        return HTMLResponse(_doc_msg("参数缺失：需要 project+file 或 doc+kb"),
                            status_code=400)

    ext = os.path.splitext(path)[1].lower()
    if ext in _DOC_DOWNLOAD:
        return FileResponse(path, filename=os.path.basename(path),
                            media_type="application/octet-stream")
    if ext in _DOC_MIME:
        return FileResponse(path, media_type=_DOC_MIME[ext])
    try:
        text = await asyncio.to_thread(
            lambda: open(path, encoding="utf-8", errors="replace").read(2_000_000))
    except OSError as e:
        return HTMLResponse(_doc_msg(f"读取失败: {e}"), status_code=500)
    return HTMLResponse(_doc_text_html(title, path, text, line))


@app.get("/graph", response_class=HTMLResponse)
async def graph_page():
    """知识图谱交互页面（单文件 HTML，同源调用 /graph_search、/graph_node、
    /graph_kg）。

    双视图：代码图谱（CBM SQLite，符号/文档/文件）+ 文档图谱（mempalace KG，
    文档→实体关系）。搜索时两图并行查询；输入为文档时自动切到文档图谱；
    点击节点 → 右侧面板展示属性；双击节点 → 展开其邻居（增量合并）。
    """
    return _GRAPH_HTML


@app.get("/docmap", response_class=HTMLResponse)
async def docmap_page():
    """文档↔代码映射测试页面（单文件 HTML，同源调用 /doc_code）。

    输入一个文件名自动判向，双向展示映射：命中文档 → 引用的代码符号清单
    （doc2code 平铺卡片）；命中代码文件 → 引用它的文档清单（code2doc 按
    文档分组）。查到结果后左右分栏：左侧 iframe 调 /doc_view 展示**输入
    文件本身**的原文（code2doc=命中的代码文件、doc2code=命中的文档，
    多命中可 chips 切换），右侧展示映射关系。左侧原文中有映射关系的
    片段（函数/锚点行）高亮为绿色区段——**点击片段，右侧自动只展示
    该片段对应的文档**（再点取消，状态栏 chip 可清除）；点击右侧符号行
    在左侧原文定位；文档路径/锚点新窗口打开；点击代码路径交叉反查。
    右栏与左栏互补：doc2code（左=文档）右栏只展示代码片段（源码段，
    命中过滤的行默认展开）；code2doc（左=代码）右栏只展示文档
    （文档组头 + 文档块）。
    """
    return _DOCMAP_HTML


# ── 项目管理 / Job 异步端点 ─────────────────────────────────────────


class ProjectCreateRequest(BaseModel):
    path: str = Field(description="容器内项目根目录绝对路径（如 /projects/foo）")
    name: str | None = Field(None, description="可选自定义项目名（默认自动派生）")
    force_reindex: bool = Field(False, description="为 True 时强制重索引，即使已存在")
    files_version: dict[str, int] = Field({}, description="per-file 版本映射（{相对路径: 版本号}，文件级多版本透出；/query matches 据此附 file_version)")


@app.post("/projects")
async def create_project(req: ProjectCreateRequest):
    """导入新项目并异步建立 CBM + codegraph 索引。

    立即返回 job_id，客户端通过 GET /jobs/{job_id} 轮询直到 status=done/failed。
    """
    path = os.path.abspath(req.path)
    if not os.path.isdir(path):
        raise HTTPException(400, f"path is not a directory: {path}")

    project_name = req.name or derive_project_name(path)

    if _has_running_job_for(project_name):
        raise HTTPException(409, f"project '{project_name}' already has a running job")

    if req.files_version:
        _save_files_version(project_name, req.files_version)
    job = _new_job("add_project", project_name, path)
    _executor.submit(_run_add_project_job, job[_K_JOB_ID], path, req.force_reindex)
    return {
        _K_JOB_ID: job[_K_JOB_ID],
        "project_name": project_name,
        "project_path": path,
        _K_STATUS: job[_K_STATUS],
        "log_path": job["log_path"],
        "poll": f"/jobs/{job[_K_JOB_ID]}",
    }


@app.get("/jobs/{job_id}")
async def get_job(job_id: str):
    """查 job 状态。"""
    with _jobs_lock:
        job = _jobs.get(job_id)
    if not job:
        raise HTTPException(404, f"job not found: {job_id}")
    return dict(job)


@app.get("/jobs")
async def list_jobs(status: str | None = None, limit: int = Query(50, ge=1, le=500)):
    """列出 job，可按 status 过滤。最新在前。"""
    with _jobs_lock:
        jobs = list(_jobs.values())
    if status:
        jobs = [j for j in jobs if j[_K_STATUS] == status]
    jobs.sort(key=lambda j: j["created_at"], reverse=True)
    return {"jobs": [dict(j) for j in jobs[:limit]]}


@app.delete("/projects/{name}")
async def delete_project(name: str):
    """删除项目：CBM index + codegraph index + 杀 daemon。同步执行。"""
    # 先查项目信息（root_path）
    info = None
    for p in list_projects():
        if p["name"] == name:
            info = p
            break
    if not info:
        raise HTTPException(404, f"project not found: {name}")

    path = info.get("root_path") or ""
    steps_log = []
    # 1) 杀 codegraph daemon
    if path:
        killed = _kill_codegraph_daemon_for(path, timeout_sec=5)
        steps_log.append(f"killed {killed} codegraph daemon(s)")
    # 2) 删 .codegraph 目录
    if path and os.path.isdir(os.path.join(path, ".codegraph")):
        import shutil
        shutil.rmtree(os.path.join(path, ".codegraph"), ignore_errors=True)
        steps_log.append(f"removed {path}/.codegraph/")
    # 3) 删 CBM index
    r = subprocess.run(
        [CBM_BIN, "cli", "--json", "delete_project", json.dumps({"project": name})],
        capture_output=True, text=True, timeout=60,
    )
    if r.returncode != 0:
        raise HTTPException(500, f"CBM delete_project failed: {r.stderr or r.stdout}")
    steps_log.append(f"CBM project '{name}' deleted")

    # 4) 从进程级 KBStore 缓存里清掉（如果有）
    _STORES.pop(name, None)

    # 5) 清 per-file 版本映射
    try:
        os.remove(_files_version_path(name))
    except OSError:
        pass

    return {"deleted": True, "name": name, "steps": steps_log}


class ReindexRequest(BaseModel):
    files_version: dict[str, int] = Field({}, description="per-file 版本映射（同 POST /projects）")


@app.post("/projects/{name}/reindex")
async def reindex_project(name: str, req: ReindexRequest | None = None):
    """对已存在项目触发重索引。返回 job_id。可带 per-file 版本映射。"""
    info = None
    for p in list_projects():
        if p["name"] == name:
            info = p
            break
    if not info:
        raise HTTPException(404, f"project not found: {name}")
    path = info.get("root_path") or ""
    if not path or not os.path.isdir(path):
        raise HTTPException(400, f"project root_path invalid: {path}")

    if _has_running_job_for(name):
        raise HTTPException(409, f"project '{name}' already has a running job")

    # 先删旧索引（让 job 里 CBM/codegraph 重新建）
    # 这里不直接调 DELETE 端点（避免杀进程复杂度），交给 job force_reindex=True 处理
    if req and req.files_version:
        _save_files_version(name, req.files_version)
    job = _new_job("reindex", name, path)
    _executor.submit(_run_add_project_job, job[_K_JOB_ID], path, True)
    return {
        _K_JOB_ID: job[_K_JOB_ID],
        "project_name": name,
        "project_path": path,
        _K_STATUS: job[_K_STATUS],
        "log_path": job["log_path"],
        "poll": f"/jobs/{job[_K_JOB_ID]}",
    }


# ── 入口 ────────────────────────────────────────────────────────────


def main():
    ap = argparse.ArgumentParser(description="自然语言代码查询常驻 HTTP 服务 (FastAPI)")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=9788)
    args = ap.parse_args()

    log.info(f"自然语言查询服务启动 (FastAPI): http://{args.host}:{args.port}")
    log.info(f"Swagger 文档: http://localhost:{args.port}/docs")
    log.info(f"图谱缓存目录: {CACHE_DIR}")
    log.info(f"终端点:")
    log.info(f"  GET /health          健康检查")
    log.info(f"  GET /projects        列出已索引项目")
    log.info(f"  POST /query          自然语言查询 (JSON body)")
    log.info(f"示例:")
    log.info(f"  curl -X POST 'http://localhost:{args.port}/query' \\")
    log.info(f"    -H 'Content-Type: application/json' \\")
    log.info(f"    -d '{{\"q\":\"数据库连接初始化\",\"project\":\"data-hunan-bms\"}}'")
    log.info(f"Ctrl+C 停止\n")

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
