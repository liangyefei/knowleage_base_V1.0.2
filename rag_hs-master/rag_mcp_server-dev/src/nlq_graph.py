"""nlq_graph — 图谱浏览（/graph_search、/graph_node、/graph_suggest）。"""
import re
import time

from nlq_constants import (
    log, _K_DEG, _K_EDGES, _K_FILE, _K_FILE_PATH, _K_LABEL, _K_NAME, _K_SOURCE,
    _K_TARGET,
)
from nlq_doc_code import _code_source_block
from nlq_mempalace import _kg_entities_fetch, _kg_fetch, _kg_kind

# 扫描 G.TYP.03：联想源类型常量化
_SRC_KG = "kg"
from nlq_query import extract_keywords
from nlq_store import get_store, _load_files_version, _lookup_files_version

# ── 知识图谱浏览（/graph_search、/graph_node、/graph 页面）──────────

def _do_graph_search(q: str, project: str, limit: int, candidates: int) -> dict:
    t0 = time.time()
    store = get_store(project)
    terms = extract_keywords(q) or [q]
    hits = store.search_entities(terms, limit=max(candidates, 6))
    if not hits and q not in terms:
        hits = store.search_entities([q], limit=max(candidates, 6))
    center = nodes = edges = None
    cand: list = []
    hint = None
    if hits:
        best = hits[0]
        g = store.entity_graph(best["name"], edge_limit=limit)
        if g:
            center, nodes, edges = g["center"], g["nodes"], g["edges"]
        cand = [h for h in hits[1:] if h.get("id") != best.get("id")]
    else:
        hint = (f"未找到与 '{q}' 匹配的实体（符号名/文档名/文件名均可，"
                f"支持部分匹配）")
    if center is not None and not edges:
        hint = f"实体 '{center['name']}' 在图谱中没有邻接关系（孤立节点）"
    fv_map = _load_files_version(store.project)
    if nodes:
        for n in nodes:
            v = _lookup_files_version(fv_map, n.get("file_path", ""))
            if v is not None:
                n["file_version"] = v
    return {"query": q, "project": store.project, "center": center,
            "candidates": cand, "nodes": nodes or [], "edges": edges or [],
            "hint": hint,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


def _do_graph_node(name: str, project: str, node_id, file_hint: str,
                   with_source: bool) -> dict:
    t0 = time.time()
    store = get_store(project)
    d = store.node_detail(name, node_id=node_id, file_hint=file_hint)
    if d is None:
        return {"node": None,
                "hint": f"未找到实体 '{name}'",
                "elapsed_ms": round((time.time() - t0) * 1000, 1)}
    has_pos = bool(d[_K_FILE_PATH] and d["start_line"] and d["end_line"])
    if with_source and has_pos:
        src = _code_source_block(store, d[_K_FILE_PATH], d["start_line"],
                                 d["end_line"])
        if src is not None:
            d["source"] = src
    v = _lookup_files_version(_load_files_version(store.project),
                              d[_K_FILE_PATH])
    if v is not None:
        d["file_version"] = v
    return {"node": d,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


def _do_graph_suggest(q: str, project: str, limit: int,
                      palace: str | None = None) -> dict:
    """搜索框联想（双源聚合）：
    源1 代码图谱——左边界词段匹配（name/file_path），空则子串匹配 +
    Python 侧左边界后滤（enabLED/knowledge 类词内嵌子串剔除）；
    源2 文档图谱（mempalace KG）——q 精确命中实体时返回其自身+高度数
    邻居（KG 无名称搜索 API，只能精确命中取邻域）。
    palace 非空时源2 按库过滤（=kbId）；未显式传入时从 project 推导——
    平台 core-service 把 kbId 映射为 project 转发，推导后联想路无需
    平台改动即按库隔离；project 非 kbId 形态（如 data-hunan-*）则
    保持全局联想（向后兼容）。"""
    if not palace:
        _pm = re.search(r"kb[0-9a-f]{20,}", project or "")
        if _pm:
            palace = _pm.group(0)
    t0 = time.time()
    store = get_store(project)
    hits = store.search_entities_boundary(q, limit=limit)
    if not hits:
        terms = extract_keywords(q) or [q]
        raw = store.search_entities(terms, limit=limit * 3)
        lb = re.compile(r"(?i)(^|[^0-9A-Za-z])" + re.escape(q))
        hits = [h for h in raw
                if lb.search(h[_K_NAME]) or lb.search(h.get(_K_FILE_PATH) or "")]
    seen: dict = {}
    for h in hits:
        k = (h[_K_NAME], h[_K_LABEL])
        deg = h["in_degree"] + h["out_degree"]
        if k not in seen or deg > seen[k][_K_DEG]:
            seen[k] = {_K_NAME: h[_K_NAME], _K_LABEL: h[_K_LABEL], _K_SOURCE: "code",
                       _K_FILE: (h.get(_K_FILE_PATH) or "").rsplit("/", 1)[-1],
                       _K_DEG: deg}
    out = sorted(seen.values(), key=lambda x: -x["deg"])[:limit]
    # 源2：KG 精确命中 → 自身 + 高度数邻居。代码项优先，KG 只补少量
    # （有代码候选时 cap 3，代码侧为空时 KG 填满——LED 场景）
    try:
        d = _kg_fetch(q, palace=palace, timeout=3.0)
        if d.get(_K_EDGES):
            deg: dict = {}
            for e in d[_K_EDGES]:
                deg[e.get(_K_SOURCE, "")] = deg.get(e.get(_K_SOURCE, ""), 0) + 1
                deg[e.get(_K_TARGET, "")] = deg.get(e.get(_K_TARGET, ""), 0) + 1
            cap = min(limit, 7) if not out else 3
            kg_out = [{_K_NAME: q, _K_LABEL: _kg_kind(q), _K_SOURCE: _SRC_KG,
                       _K_FILE: "", _K_DEG: deg.get(q, len(d[_K_EDGES]))}]
            for n, dg in sorted(deg.items(), key=lambda x: -x[1]):
                if n != q and len(kg_out) < cap:
                    kg_out.append({_K_NAME: n, _K_LABEL: _kg_kind(n),
                                   _K_SOURCE: _SRC_KG, _K_FILE: "", _K_DEG: dg})
            if out:
                out = out[:max(0, limit - len(kg_out))] + kg_out
            else:
                out = kg_out[:limit]
        else:
            # 无精确命中（库内没有该实体名）→ 实体名包含匹配兜底，
            # 如 q=610 命中该库文档集里的 Hi3516CV610-* 实体
            ents = _kg_entities_fetch(q, palace=palace)
            if ents:
                cap = min(limit, 7) if not out else 3
                kg_out = [{_K_NAME: e, _K_LABEL: _kg_kind(e), _K_SOURCE: _SRC_KG,
                           _K_FILE: "", _K_DEG: 0} for e in ents[:cap]]
                if out:
                    out = out[:max(0, limit - len(kg_out))] + kg_out
                else:
                    out = kg_out[:limit]
    except Exception as e:
        log.debug("kg suggest 降级: %s", e)
    return {"query": q, "project": store.project, "suggestions": out,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


