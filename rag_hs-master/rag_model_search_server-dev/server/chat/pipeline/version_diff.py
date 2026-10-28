"""版本对比快路径：识别差异意图，调用 file_version.diff_versions 生成上下文。"""
import re

from configs import logger

_VERDIFF_SIGNAL = re.compile(r"(差异|对比|区别|不同|变化|改了什么|修改了什么|更新了什么|变更)")
_VERSION_TOKEN = re.compile(r"[vV]?\d+\.\d+\.\d+")
# 单版本检索：三点（v1.2.3 / 1.2.3）或两点带 v 前缀（v1.2），避免误匹配纯数值（1.2 米）
_VF_TOKEN = re.compile(r"[vV]?\d+\.\d+\.\d+|[vV]\d+\.\d+")


def _detect_version_diff(query: str) -> dict | None:
    """识别版本对比意图，抽取 {file, from, to}。

    触发条件：差异类信号词 + （版本串 v1.0.x 或"版本"字样）。文件名按
    反引号/引号包裹 > 含扩展名 token 抽取（排除版本串本身）；版本串按
    出现顺序：两个 → from/to，一个 → to=该版 from=其前一版，零个 →
    最新 vs 次新。识别不出文件名仍返回（diff 层给提示）。
    """
    if not _VERDIFF_SIGNAL.search(query):
        return None
    vt = _VERSION_TOKEN.findall(query)
    if not vt and "版本" not in query:
        return None
    file_name = None
    m = re.search(r"[`'\"]([^`'\"]+\.[A-Za-z0-9]{1,10})[`'\"]", query)
    if not m:
        m = re.search(r"([\w./\-]+\.[A-Za-z0-9]{1,10})(?!\S*\.\d)", query)
    if m:
        cand = m.group(1).rstrip("。？！，,;；)）")
        if not _VERSION_TOKEN.fullmatch(cand) and not cand.isdigit():
            file_name = cand
    if len(vt) >= 2:
        frm, to = vt[0], vt[1]
    elif len(vt) == 1:
        frm, to = None, vt[0]
    else:
        frm = to = None
    return {"file": file_name, "from": frm, "to": to}


def _detect_version_filter(query: str) -> str | None:
    """识别"指定单版本检索"意图，返回版本串 label 或 None。

    触发条件：含版本串 + 无对比信号词 + 恰好 1 个版本串。
    - 有对比信号词 → 让 verdiff 接管（返回 None）
    - ≥2 版本串 → 多版本默认对比意图，交 verdiff（返回 None）
    - 0 版本串 → 无指定版本（返回 None）
    - 恰好 1 个 → 返回该串作为 version filter

    版本串 pattern：三点（v1.2.3 / 1.2.3）或 两点带 v 前缀（v1.2），
    避免误匹配纯数值（1.2 米）。
    """
    if _VERDIFF_SIGNAL.search(query):
        return None
    vt = _VF_TOKEN.findall(query)
    if len(vt) != 1:
        return None
    return vt[0]


def _search_version_chunks(kb: str, query: str, version_label: str,
                           top_k: int = 5,
                           precomputed_embedding: list = None) -> list:
    """P2 语义检索：按 version_label 过滤检索 Milvus 相关 chunk。fail-open 返回 []。

    支持传入 precomputed_embedding 复用 query 向量（避免两版本各算一次）。
    返回 [{"content": ..., "source": ...}] 列表，便于 _build_verdiff_context 拼接。
    """
    try:
        from server.chat.pipeline.milvus_retrieval import _search_milvus_sync
        from server.knowledge_base.search_filter import SearchFilter
        docs = _search_milvus_sync(query, top_k, kb, 0.0,
                                   precomputed_embedding=precomputed_embedding,
                                   filter_opts=SearchFilter(version=version_label))
        return [{"content": d.page_content, "source": d.metadata.get("source", "")}
                for d in (docs or [])]
    except Exception as e:
        logger.warning(f"[verdiff] 语义检索失败 kb={kb} label={version_label}: {e}")
        return []


def _run_verdiff_for_kbs(kbs: list[str], vd: dict,
                         query: str | None = None) -> dict | None:
    """逐库执行：blob difflib（行级精确）+ Milvus 两版本 chunk（语义聚焦）。fail-open。

    有 query 且 diff 命中两版本 label 时，按 query 分别检索 from/to 版本 chunk；
    query 复用同一 embedding（算 1 次，传 precomputed_embedding 给两次检索）。
    Milvus 异常 → from_chunks/to_chunks 降级为 []，不阻塞 blob diff 主路。
    """
    _FROM_KEY = 'from'
    _TO_KEY = 'to'
    _FROM_CHUNKS_KEY = 'from_chunks'
    _TO_CHUNKS_KEY = 'to_chunks'
    try:
        from server.knowledge_base.file_version import diff_versions
    except ImportError:
        logger.warning("[verdiff] file_version 模块不可用（镜像未含/未挂载），跳过版本对比")
        return None
    for kb in kbs:
        try:
            r = diff_versions(kb, vd.get("file"), vd.get(_FROM_KEY), vd.get(_TO_KEY))
            if r.get("found") and r.get(_FROM_KEY) and r.get(_TO_KEY):
                # 版本可用才 return；from/to=None 时继续遍历下一个库（Bug F 多库 fallback）
                r["knowledge_base_name"] = kb
                r["from_label"] = r.get(_FROM_KEY)
                r["to_label"] = r.get(_TO_KEY)
                # P2 语义增强：有 query 且两版本 label 均在时检索两版本 chunk
                if query and r.get(_FROM_KEY) and r.get(_TO_KEY):
                    try:
                        precomputed = None
                        try:
                            from server.chat.pipeline.embeddings import get_embeddings
                            precomputed = get_embeddings([query]).tolist()[0]
                        except Exception as e:
                            logger.warning(f"[verdiff] query embedding 失败（降级各算一次）: {e}")
                        r[_FROM_CHUNKS_KEY] = _search_version_chunks(
                            kb, query, r[_FROM_KEY], precomputed_embedding=precomputed)
                        r[_TO_CHUNKS_KEY] = _search_version_chunks(
                            kb, query, r[_TO_KEY], precomputed_embedding=precomputed)
                    except Exception as e:
                        logger.warning(f"[verdiff] 语义检索异常 kb={kb}（降级为空）: {e}")
                        r[_FROM_CHUNKS_KEY] = []
                        r[_TO_CHUNKS_KEY] = []
                else:
                    r[_FROM_CHUNKS_KEY] = []
                    r[_TO_CHUNKS_KEY] = []
                return r
        except Exception as e:
            logger.warning(f"[verdiff] diff_versions 异常 kb={kb}: {e}")
    return None


def _build_verdiff_context(vd: dict, vres: dict | None):
    """版本对比上下文：行级 diff（主）+ 两版本语义 chunk（辅）。fail-open：空则省略语义段。"""
    _FROM_KEY = 'from'
    _TO_KEY = 'to'
    _IDENTICAL_KEY = 'identical'
    _DIFF_KEY = 'diff'
    _CONTENT_KEY = 'content'
    if vres is None:
        ctx = (f"【版本差异】\n未找到文件 {vd.get('file')!r} 的版本记录。请向用户说明：该文件在所选知识库中"
               "没有多版本历史（或文件名未识别），无法对比。")
        return ctx, None
    if vres.get(_FROM_KEY) is None or vres.get(_TO_KEY) is None:
        ctx = (f"【版本差异】\n文件 {vres.get('file_name')} 存在版本记录，但请求的版本不可用：{vres.get('note', '')}\n"
               "请向用户转述可用版本并建议改问可用版本之间的差异。")
        return ctx, None
    from_label = vres.get("from_label") or vres.get(_FROM_KEY)
    to_label = vres.get("to_label") or vres.get(_TO_KEY)
    # prompt 膨胀控制：diff 截 4000 字符，每版本 chunk 限 3 个、每个截 200 字符
    MAX_DIFF = 4000
    MAX_CHUNKS_PER_VER = 3
    MAX_CHUNK_CHARS = 200
    lines = ["【版本差异】（行级 diff 为主、语义 chunk 为辅；行级 diff 为准，不要编造）",
             f"文件：{vres['file_name']}（知识库 {vres.get('knowledge_base_name', '')}）",
             f"对比：{from_label} (sha {vres['from_sha'][:12]}…, {vres['from_size']}B) "
             f"→ {to_label} (sha {vres['to_sha'][:12]}…, {vres['to_size']}B)"]
    if vres.get(_IDENTICAL_KEY):
        lines.append("两版本内容完全相同（无差异）。")
    elif vres.get(_DIFF_KEY):
        lines.append(f"统计：新增 {vres['added']} 行，删除 {vres['removed']} 行"
                     + ("（diff 已截断）" if vres.get("truncated") else ""))
        diff_text = vres[_DIFF_KEY]
        if len(diff_text) > MAX_DIFF:
            diff_text = diff_text[:MAX_DIFF] + "\n... diff truncated ..."
        lines += ["--- 行级 diff ---", diff_text, "--- end diff ---"]
    else:
        lines.append(f"统计级差异：{vres['from_size']}B → {vres['to_size']}B"
                     + (f"（{vres.get('note', '')}）" if vres.get("note") else ""))
    # P2 语义增强段（fail-open：空则省略；每版本限 MAX_CHUNKS_PER_VER 个，每个截 MAX_CHUNK_CHARS）
    from_chunks = (vres.get("from_chunks") or [])[:MAX_CHUNKS_PER_VER]
    to_chunks = (vres.get("to_chunks") or [])[:MAX_CHUNKS_PER_VER]
    if from_chunks:
        lines.append(f"【版本 {from_label} 语义相关片段】")
        for i, c in enumerate(from_chunks):
            content = c.get(_CONTENT_KEY, "") if isinstance(c, dict) else str(c)
            lines.append(f"[{i+1}] {content[:MAX_CHUNK_CHARS]}")
    if to_chunks:
        lines.append(f"【版本 {to_label} 语义相关片段】")
        for i, c in enumerate(to_chunks):
            content = c.get(_CONTENT_KEY, "") if isinstance(c, dict) else str(c)
            lines.append(f"[{i+1}] {content[:MAX_CHUNK_CHARS]}")
    doc = {"file": vres["file_name"], "page": "", "titles": [],
           _CONTENT_KEY: (vres.get(_DIFF_KEY) or vres.get("note") or "")[:2000],
           "doc_type": "version_diff", "relevance_score": None, "used": True,
           "from_version": vres[_FROM_KEY], "to_version": vres[_TO_KEY],
           _FROM_CHUNKS_KEY: from_chunks, _TO_CHUNKS_KEY: to_chunks,
           "added": vres.get("added"), "removed": vres.get("removed"),
           _IDENTICAL_KEY: vres.get(_IDENTICAL_KEY), "knowledge_base_name": vres.get("knowledge_base_name")}
    return "\n".join(lines), doc
