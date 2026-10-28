"""nlq_doc_code — 文档↔代码映射（/doc_code）。"""
import os
import re
import time
from dataclasses import dataclass

from nlq_constants import (
    _K_DOC, _K_DOC_NAME, _K_DOC_REF, _K_END_LINE, _K_FILE, _K_LABEL, _K_LINE,
    _K_MATCHED, _K_REF_NAME, _K_SOURCE, _K_START_LINE, _K_SYMBOLS,
    _MATCH_DOCUMENT,
)
from nlq_store import (
    DocCodeEdgeQuery, KBStore, _bad_range, _read_lines_cached, get_store,
    _load_files_version, _lookup_files_version,
)

# ── 文档↔代码映射（/doc_code）────────────────────────────────────────

def _fuzzy_tier(name: str, col: str):
    """模糊匹配层：输入按 / 切段（剥 kb/<库名>/ 前缀、去空段），每段
    都须在路径中出现（AND LIKE）。段数≥1 即有效；支持部分路径输入
    如 'image_tool/oem/oem_main' 或散段 'dmeb oem_main.py'。"""
    m = re.match(r"^kb/[^/]+/(.+)$", name)
    if m:
        name = m.group(1)
    toks = [t for t in re.split(r"[/\\\s]+", name) if t]
    if not toks:
        return None, []
    where = " AND ".join([f"{col} LIKE ?"] * len(toks))
    return where, [f"%{t}%" for t in toks]


def _doc_side_matches(store: KBStore, name: str, exact: bool, limit: int):
    """文档侧匹配：名精确 → '/名' 段边界（含根目录整串）→ 整串包含 →
    模糊分段。带 Function/Method 过滤（符号级映射；只提到文件的文档不
    算命中，那类需求由代码侧 basename 匹配覆盖）。"""
    tiers = [("d.name = ?", [name])]
    if not exact:
        tiers += [("(d.file_path = ? OR d.file_path LIKE ?)", [name, "%/" + name]),
                  ("d.file_path LIKE ?", ["%" + name + "%"])]
        fz = _fuzzy_tier(name, "d.file_path")
        if fz[0]:
            tiers.append(fz)
    for where, params in tiers:
        rows = store.doc_code_edges(DocCodeEdgeQuery(
            doc_where=where, doc_params=params, symbols_only=True, limit=limit))
        if rows:
            return rows
    return []


def _code_side_matches(store: KBStore, name: str, limit: int):
    """代码侧匹配（文件名语义）：'/名' 段边界 → 整串包含 → 模糊分段。
    不加 label 过滤——文件整体被文档提及时边目标是 File 节点，同样算引用。"""
    tiers = [("(f.file_path = ? OR f.file_path LIKE ?)", [name, "%/" + name]),
             ("f.file_path LIKE ?", ["%" + name + "%"])]
    fz = _fuzzy_tier(name, "f.file_path")
    if fz[0]:
        tiers.append(fz)
    for where, params in tiers:
        rows = store.doc_code_edges(DocCodeEdgeQuery(
            code_where=where, code_params=params, limit=limit))
        if rows:
            return rows
    return []


def _file_input_candidates(file_name: str) -> list[str]:
    """输入文件名归一化候选（按优先级）：
    原样 → 剥平台路径前缀 kb/<库名或kbId>/ → basename。
    平台/前端常传 documents.file_path 完整路径（kb/CV610ALL/hi_aiot/…），
    该前缀不在图谱项目树内，需剥除后再匹配；basename 兜底。"""
    cands = [file_name]
    m = re.match(r"^kb/[^/]+/(.+)$", file_name)
    if m and m.group(1) not in cands:
        cands.append(m.group(1))
    base = file_name.rsplit("/", 1)[-1]
    if base not in cands:
        cands.append(base)
    return cands


@dataclass
class DocCodeParams:
    """_doc_code_mapping 参数封装（扫描 G.FNM.03：7 参数超限）。"""
    file_name: str
    project: str
    direction: str = "auto"
    limit: int = 20
    with_source: int = 0
    with_doc: int = 0
    exact: bool = False


def _code_source_block(store: KBStore, file_path: str,
                       start_line, end_line, max_chars: int = 2600):
    """读源码块：按节点 start_line..end_line 直读文件（截断约定同 snippet）。"""
    if _bad_range(file_path, start_line, end_line):
        return None
    if not store.root_path:
        return None
    lines = _read_lines_cached(os.path.join(store.root_path, file_path))
    if lines is None or start_line < 1 or start_line > len(lines):
        return None
    src = "".join(lines[start_line - 1:end_line])
    if len(src) > max_chars:
        src = src[:max_chars] + "\n... [truncated]"
    return src


def _doc_fragment_block(store: KBStore, doc_path: str, ref: dict,
                        max_chars: int = 2000):
    """读文档块：优先 doc_ref.line 锚点窗口（前 5 后 20 行）；无锚点找
    ref_name 在正文中的首现行；再退文件头部。读不到返回 None（省略字段）。"""
    if not doc_path or not store.root_path:
        return None
    lines = _read_lines_cached(os.path.join(store.root_path, doc_path))
    if not lines:
        return None
    line = ref.get("line")
    if not (isinstance(line, int) and line >= 1):
        line = None
        ref_name = ref.get("ref_name") or ""
        if ref_name:
            for i, ln in enumerate(lines):
                if ref_name in ln:
                    line = i + 1
                    break
    if line is not None:
        lo, hi = max(1, line - 5), min(len(lines), line + 20)
    else:
        start = next((i for i, ln in enumerate(lines) if ln.strip()), 0)
        lo, hi = start + 1, min(len(lines), start + 40)
    content = "".join(lines[lo - 1:hi])
    if len(content) > max_chars:
        content = content[:max_chars] + "\n... [truncated]"
    return {"file": doc_path, "start_line": lo, "end_line": hi,
            "content": content}


def _pick(row: dict, keys: tuple) -> dict:
    """扫描 G.EXP.04：多行字典推导式收敛为单点 helper。"""
    return {k: row[k] for k in keys}


def _doc_code_mapping(p: "DocCodeParams") -> dict:
    file_name, project = p.file_name, p.project
    direction, limit, exact = p.direction, p.limit, p.exact
    with_source, with_doc = p.with_source, p.with_doc
    t0 = time.time()
    store = get_store(project)
    rows: list = []
    matched = None
    if direction in ("auto", "doc2code"):
        for cand in _file_input_candidates(file_name):
            rows = _doc_side_matches(store, cand, exact, limit)
            if rows:
                matched = _MATCH_DOCUMENT
                break
    if matched is None and direction in ("auto", "code2doc"):
        for cand in _file_input_candidates(file_name):
            rows = _code_side_matches(store, cand, limit)
            if rows:
                matched = "code_file"
                break
    fv_map = _load_files_version(store.project)
    results = []

    def _with_fv(entry: dict, path: str) -> dict:
        v = _lookup_files_version(fv_map, path)
        if v is not None:
            entry["file_version"] = v
        return entry

    if matched == _MATCH_DOCUMENT:
        # doc2code：平铺映射列表；源码块取前 with_source 个符号，
        # 文档块按去重文档取前 with_doc 个（挂在各文档首条映射上）
        src_done, frag_docs = 0, set()
        for r in rows:
            item = _pick(r, (_K_DOC, _K_DOC_NAME, "symbol", _K_LABEL,
                             _K_FILE, _K_START_LINE, _K_END_LINE, _K_DOC_REF))
            _with_fv(item, r[_K_FILE])
            if with_source > 0 and src_done < with_source:
                src = _code_source_block(store, r[_K_FILE],
                                         r[_K_START_LINE], r[_K_END_LINE])
                if src is not None:
                    item[_K_SOURCE] = src
                    src_done += 1
            if (with_doc > 0 and r[_K_DOC] not in frag_docs
                    and len(frag_docs) < with_doc):
                frag = _doc_fragment_block(store, r[_K_DOC], r[_K_DOC_REF])
                if frag is not None:
                    _with_fv(frag, r[_K_DOC])
                    item["doc_fragment"] = frag
                    frag_docs.add(r[_K_DOC])
            results.append(item)
    else:
        # code2doc：按文档分组，组内为引用的符号/文件清单
        groups: dict = {}
        for r in rows:
            g = groups.setdefault(r[_K_DOC], {_K_DOC: r[_K_DOC],
                                             _K_DOC_NAME: r[_K_DOC_NAME],
                                             _K_SYMBOLS: []})
            sym = _pick(r, ("symbol", _K_LABEL, _K_FILE,
                            _K_START_LINE, _K_END_LINE, _K_DOC_REF))
            _with_fv(sym, r[_K_FILE])
            g[_K_SYMBOLS].append(sym)
        results = list(groups.values())
        src_done = 0
        for g in results:
            if with_doc > 0:
                ref = {}
                for s in g[_K_SYMBOLS]:
                    dr = s[_K_DOC_REF]
                    if dr.get(_K_REF_NAME) or dr.get(_K_LINE) is not None:
                        ref = dr
                        break
                frag = _doc_fragment_block(store, g[_K_DOC], ref)
                if frag is not None:
                    _with_fv(frag, g[_K_DOC])
                    g["doc_fragment"] = frag
                with_doc -= 1
            for s in g[_K_SYMBOLS]:
                if with_source <= 0 or src_done >= with_source:
                    break
                src = _code_source_block(store, s[_K_FILE],
                                         s[_K_START_LINE], s[_K_END_LINE])
                if src is not None:
                    s[_K_SOURCE] = src
                    src_done += 1
    hint = None
    if matched is None:
        hint = ("未找到映射：映射边=文档正文实际引用的代码符号，"
                "该文件可能未被任何文档引用（数据真值），或名称不匹配")
    return {"file": file_name, "project": store.project,
            "direction": ("doc2code" if matched == _MATCH_DOCUMENT
                          else "code2doc") if matched else None,
            _K_MATCHED: matched,
            "count": len(results),
            "results": results,
            "hint": hint,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


