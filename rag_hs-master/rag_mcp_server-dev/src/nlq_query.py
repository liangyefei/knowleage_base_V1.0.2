"""nlq_query — 自然语言关键词提取与 /query 逻辑。"""
import re
import time
from nlq_constants import (
    _K_FILE, _K_FILE_PATH, _K_ID, _K_LABEL, _K_MATCHED, _K_NAME, _K_SOURCE,
    _K_START_LINE, _K_TYPE,
)
from nlq_store import get_store, _load_files_version, _lookup_files_version

# ── 自然语言关键词提取 ────────────────────────────────────────────────

CN_TO_EN = {
    "初始化": ["init", "initialize", "setup", "open"], "初始": ["init"],
    "连接": ["connect", "conn", "link"],
    "数据库": ["db", "database", "sql", "ora", "oracle", "mysql"],
    "配置": ["config", "cfg", "setting", "conf"], "设置": ["set", "config", "setup"],
    "读取": ["read", "get", "fetch", "load"], "读": ["read", "get"],
    "写入": ["write", "set", "save", "store"], "写": ["write", "save"],
    "保存": ["save", "store", "persist"], "加载": ["load", "fetch"],
    "解析": ["parse", "decode"],
    "发送": ["send", "dispatch", "emit"], "接收": ["recv", "receive", "read"],
    "校验": ["check", "verify", "validate"], "检查": ["check", "verify"],
    "验证": ["verify", "validate", "auth"],
    "权限": ["privilege", "perm", "auth", "acl"],
    "账户": ["account", "acc", "user"],
    "计费": ["bill", "charge", "fee", "balance"], "余额": ["balance", "remain"],
    "日志": ["log", "trace", "debug"],
    "错误": ["error", "err", "fail"], "异常": ["exception", "error"],
    "关闭": ["close", "shutdown", "stop", "fini"],
    "启动": ["start", "run", "main", "boot"],
    "消息": ["message", "msg"], "处理": ["handle", "process", "proc"],
    "回调": ["callback", "cb"], "线程": ["thread"], "锁": ["lock", "mutex"],
    "缓存": ["cache", "buffer"], "内存": ["mem", "alloc", "malloc"],
    "释放": ["free", "release", "destroy"],
    "创建": ["create", "new", "make", "alloc"],
    "删除": ["delete", "remove", "del", "drop"], "更新": ["update", "modify"],
    "查询": ["query", "search", "find", "select"],
    "监听": ["listen", "listener"], "通知": ["notify", "notice"],
    "同步": ["sync"], "心跳": ["heartbeat", "hb"],
    "超时": ["timeout"], "重试": ["retry"],
    "签名": ["sign", "signature"], "加密": ["encrypt", "cipher"],
    "解码": ["decode"], "编码": ["encode"],
}

# 芯片/多媒体领域词（拍照→snap 等），与 src/mcp/mcp.c 的 BM25_CN_TERMS
# 保持同步——修改任一侧必须同步另一侧。编码/解码在此扩展 venc/vdec 变体。
CN_TO_EN.update({
    "拍照": ["snap", "capture"], "抓拍": ["snap", "capture"],
    "照相": ["snap", "capture"], "样例": ["sample"],
    "示例": ["sample"], "录像": ["record", "venc"],
    "录制": ["record"], "预览": ["preview"],
    "传感器": ["sensor"], "相机": ["camera"],
    "摄像": ["camera", "video"], "视频": ["video", "vpss"],
    "图像": ["image", "vgs"], "音频": ["audio", "aenc"],
    "语音": ["audio"], "显示": ["display", "vo"],
    "叠加": ["region", "osd"], "水印": ["osd", "region"],
    "通道": ["chn", "channel"], "串口": ["uart"],
    "帧": ["frame", "vb"], "缓冲": ["buffer", "vb"],
    "编码": ["encode", "venc"], "解码": ["decode", "vdec"],
})


def extract_keywords(question: str) -> list[str]:
    en: list[str] = []
    for cn, ens in CN_TO_EN.items():
        if cn in question:
            en.extend(ens)
    symbol_pat = re.compile(
        r"[A-Za-z_][A-Za-z0-9_]*(?:[A-Z][a-z]|[a-z][A-Z]|_[a-z0-9])[A-Za-z0-9_]*"
        r"|[A-Z][A-Z_]{2,}[A-Z0-9_]*"
    )
    syms = [m.group(0) for m in symbol_pat.finditer(question)]
    tokens = [w.lower() for w in re.findall(r"[A-Za-z][A-Za-z0-9_]*", question)
              if len(w) >= 2]
    out, seen = [], set()
    for t in tokens + en + syms:
        if t not in seen and re.fullmatch(r"[a-z][a-z0-9_]*", t.lower()):
            out.append(t)
            seen.add(t)
    return out


def do_query(question: str, project: str, top: int, expand: bool,
             include_source: int = 0) -> dict:
    t0 = time.time()
    terms = extract_keywords(question)
    store = get_store(project)
    hits = store.search(terms, limit=top)

    fv_map = _load_files_version(project)
    if fv_map:
        for h in hits:
            v = _lookup_files_version(fv_map, h.get("file_path", ""))
            if v is not None:
                h["file_version"] = v

    if include_source > 0:
        snips = store.snippets_batch([h[_K_NAME] for h in hits[:include_source]])
        for h in hits[:include_source]:
            sn = snips.get(h[_K_NAME])
            if sn:
                h["source"] = sn

    nodes, edges, names = [], [], set()
    for h in hits:
        nm = h[_K_NAME]
        nodes.append({**h, _K_MATCHED: True})
        names.add(nm)
    if expand:
        nbs = store.neighbors_batch([h[_K_NAME] for h in hits])
        for h in hits:
            nm = h[_K_NAME]
            nb = nbs.get(nm, {"callers": [], "callees": []})
            for c in nb["callers"]:
                edges.append({"src": c[_K_NAME], "dst": nm, "type": "CALLS"})
                if c[_K_NAME] not in names:
                    nodes.append({_K_NAME: c[_K_NAME], _K_LABEL: c.get(_K_LABEL, ""),
                                  _K_FILE: c.get(_K_FILE, ""), _K_MATCHED: False})
                    names.add(c[_K_NAME])
            for c in nb["callees"]:
                edges.append({"src": nm, "dst": c[_K_NAME], "type": "CALLS"})
                if c[_K_NAME] not in names:
                    nodes.append({_K_NAME: c[_K_NAME], _K_LABEL: c.get(_K_LABEL, ""),
                                  _K_FILE: c.get(_K_FILE, ""), _K_MATCHED: False})
                    names.add(c[_K_NAME])
    return {"question": question, "project": project, "search_terms": terms,
            "matches": hits, "subgraph": {"nodes": nodes, "edges": edges},
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


def _graph_payload(result: dict) -> dict:
    """把 subgraph 标准化为 nodes+edges 图结构，供可视化插件直接消费。

    结构对齐 MemPalace 的 graph 返回格式（nodes[].id/name/type + edges[] 全字段），
    便于同一套图谱插件同时渲染两个数据源；节点 id 统一用符号名（命中的与邻居
    节点都按 name 关联，保证边可连接），命中节点额外携带文件位置与调用度数，
    用 matched 标记区分。节点 type 默认"代码"（与 MemPalace 默认"文档"对应）。
    """
    nodes, seen = [], set()
    for n in result["subgraph"]["nodes"]:
        nid = n.get("name", "")
        if not nid or nid in seen:
            continue
        seen.add(nid)
        node = {_K_ID: nid, _K_NAME: nid, _K_TYPE: "代码",
                _K_LABEL: n.get(_K_LABEL, ""),
                _K_MATCHED: bool(n.get(_K_MATCHED))}
        for k in (_K_FILE_PATH, _K_START_LINE, "in_degree", "out_degree", "qualified_name"):
            if n.get(k) is not None:
                node[k] = n[k]
        if n.get(_K_FILE) and not node.get(_K_FILE_PATH):
            node[_K_FILE_PATH] = n[_K_FILE]
        nodes.append(node)
    edges = [{"source": e.get("src", ""), "target": e.get("dst", ""),
              _K_TYPE: e.get(_K_TYPE, ""), "label": e.get(_K_TYPE, ""),
              "direction": "outgoing",
              "valid_from": None, "valid_to": None,
              "confidence": 1.0, "current": True, "source_closet": None}
             for e in result["subgraph"]["edges"]]
    return {
        "question": result["question"], "project": result["project"],
        "search_terms": result["search_terms"],
        "count": len(result["matches"]),
        "nodes": nodes, "edges": edges,
        "elapsed_ms": result["elapsed_ms"],
    }


