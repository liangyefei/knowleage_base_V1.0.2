"""nlq_mempalace — mempalace 文档知识图谱代理（/graph_kg）。"""
import json
import os
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from nlq_constants import (
    log, _K_CONFIDENCE, _K_CURRENT, _K_EDGES, _K_ID, _K_LABEL, _K_MATCHED,
    _K_NAME, _K_NODES, _K_SOURCE, _K_TARGET, _K_TYPE, _K_VALID_FROM,
    _K_VALID_TO,
)

# ── mempalace 文档知识图谱（/graph_kg，代理 mempalace REST /kg）──────

MEMPALACE_KG_BASE = os.environ.get(
    "MEMPALACE_KG_BASE_URL", "http://haisi-mempalace-rest:8777")
_KG_LONG = 80        # 超长实体名（has_content 全文值等）截断显示阈值
_KG_MAX_EDGES = 300  # 单实体返回边数上限（防爆图）

# KG 实体类型分类规则 —— 固定 7 类：芯片/传感器/外设接口/模块软件/文档/知识库/实体。
# mempalace entities.type 全库恒为 unknown 不可用，故按实体名规则分类。
# 自定义新增类型：在 _KG_KIND_RULES 加一行 ("类型名", 正则)，并在前端
# KG_COLORS 加同名配色即可（图例/着色/面板徽标自动生效），无需改其它代码。
_KG_DOC_EXT = re.compile(
    r"\.(md|markdown|pdf|docx?|xlsx?|pptx?|txt|html?|htm|csv|c|h|cpp|hpp|cc|py|sh|json|ya?ml|ini|cfg|conf|mk|cmake|dts|dtsi)$", re.I)
_KG_KIND_RULES = [
    ("芯片", re.compile(
        r"(hi\s?3\d{3}[a-z0-9]*|hi386[13]|hi3881|cv6\d\d|dv\d{3}|ev2\d\d|ws6\d|ss92[68]|sd3403"
        r"|^soc$|芯片|处理器|mcu)", re.I)),
    ("传感器", re.compile(
        r"(_cmos|os0[1-9]\d{2}|imx\d{3}|sc\d{3,4}|ar0\d{3}|gc\d{4}m?|sensor|传感器|镜头)", re.I)),
    ("外设接口", re.compile(
        r"(hispark|pcb|开发板|单板|ev板|板卡|摄像头|屏幕|显示屏|lcd|oled|touch|按键|键盘|鼠标|风扇|网卡"
        r"|wi-?fi|蓝牙|bluetooth|emmc|nand|ddr|sd卡|tf卡|存储卡|电源模块|电池|外设"
        r"|^(i2c|i3c|i2s|spi|qspi|uart|usart|usb\d?|typec|gpio|pwm|adc|dac|mipi|pcie|eth|rmii|rgmii"
        r"|canfd?|sdio|jtag|lvds|bt656|bt1120|rgb888|rgb|mclk|pclk|gnd|vcc|vdd|vio|vbat|pwr|reset|clk\d*)$)", re.I)),
    ("模块软件", re.compile(
        r"^(vi|vpss|venc|vdec|vgs|gdc|isp|aiisp|npu|ive|svp|mpp|mmz|vb|rgn|region|ai|ao|aenc|adec|avs|tde"
        r"|photo|snap|mc|vda|cipher|h264|h265|jpeg)[\s_\-0-9]*$"
        r"|(u-?boot|uboot|linux|kernel|micropython|python|burntool|zeroclaw|docker|cmake|makefile|gcc|"
        r"git\b|hispark_studio|openharmony|liteos|rtos|vue|react|nginx|fastapi|工具|编译器|操作系统)", re.I)),
]
_KG_KB = re.compile(r"^kb[0-9a-f]{20,}$", re.I)


def _kg_kind(nid: str) -> str:
    """mempalace KG 实体名 → 展示类型（7 类，规则可扩展）。"""
    if _KG_KB.match(nid):
        return "知识库"
    if _KG_DOC_EXT.search(nid):
        return "文档"
    for kind, rx in _KG_KIND_RULES:
        if rx.search(nid):
            return kind
    return "实体"


def _kg_fetch(entity: str, palace: str | None = None, timeout: float = 8.0) -> dict:
    """调 mempalace GET /kg?format=graph（一跳邻接，ECharts 友好结构）。
    palace 非空时按库过滤（mempalace 侧按该库文档集过滤三元组）。"""
    params = {"entity": entity, "direction": "both", "format": "graph"}
    if palace:
        params["palace"] = palace
    qs = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{MEMPALACE_KG_BASE}/kg?{qs}",
                                 headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _kg_entities_fetch(q: str, palace: str | None = None,
                       timeout: float = 3.0) -> list[str]:
    """调 mempalace GET /kg/entities（实体名包含匹配；palace 按库过滤）。"""
    params = {"q": q, "limit": 10}
    if palace:
        params["palace"] = palace
    qs = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{MEMPALACE_KG_BASE}/kg/entities?{qs}",
                                 headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return (json.loads(r.read().decode("utf-8")) or {}).get("entities") or []


def _kg_fragment_fetch(subject: str, predicate: str, object: str,
                       palace: str | None = None, timeout: float = 15.0) -> dict:
    """调 mempalace GET /kg/fragment（按三元组关系定位来源文档片段）。"""
    params = {"subject": subject, "limit": 5}
    if predicate:
        params["predicate"] = predicate
    if object:
        params["object"] = object
    if palace:
        params["palace"] = palace
    qs = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{MEMPALACE_KG_BASE}/kg/fragment?{qs}",
                                 headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _kg_safefetch(entity: str, palace: str | None = None) -> dict:
    """KG 邻居抓取（网络化用）：2s 超时 + 异常吞成空结果，可并行调用。"""
    try:
        return _kg_fetch(entity, palace=palace, timeout=2.0)
    except Exception:
        return {}


def _do_graph_kg(q: str, palace: str | None = None) -> dict:
    """mempalace 文档知识图谱查询：实体（文档名/kbId/普通实体，精确匹配）
    的一跳邻接子图。palace 非空时只返回该库文档抽取的三元组（按库隔离）。"""
    t0 = time.time()
    try:
        d = _kg_fetch(q, palace=palace)
    except Exception as e:
        return {"query": q, "palace": palace, _K_SOURCE: "mempalace-kg", "center": None,
                "nodes": [], "edges": [], "count": 0,
                "error": f"mempalace /kg 不可达: {type(e).__name__}: {e}",
                "elapsed_ms": round((time.time() - t0) * 1000, 1)}
    nodes: list = []
    edges: list = []
    nseen: set = set()
    eseen: set = set()
    _kind = _kg_kind

    for e in (d.get(_K_EDGES) or [])[:_KG_MAX_EDGES]:
        s, t = str(e.get(_K_SOURCE, "")), str(e.get(_K_TARGET, ""))
        if not s or not t:
            continue
        k = s + "|" + t + "|" + str(e.get(_K_TYPE, ""))
        if k in eseen:
            continue
        eseen.add(k)
        edges.append({_K_SOURCE: s, _K_TARGET: t,
                      _K_TYPE: str(e.get(_K_TYPE, "")),
                      _K_LABEL: str(e.get(_K_LABEL) or e.get(_K_TYPE) or ""),
                      _K_CONFIDENCE: e.get(_K_CONFIDENCE),
                      _K_VALID_FROM: e.get(_K_VALID_FROM),
                      _K_VALID_TO: e.get(_K_VALID_TO),
                      _K_CURRENT: e.get(_K_CURRENT)})
        for nid in (s, t):
            if nid in nseen:
                continue
            nseen.add(nid)
            nodes.append({
                _K_ID: nid,
                _K_NAME: nid if len(nid) <= _KG_LONG
                        else nid[:_KG_LONG] + f"…({len(nid)}字)",
                "full_len": len(nid),
                _K_TYPE: _kind(nid),
                _K_MATCHED: nid == q,
            })
    # 网络化：对高度数邻居再取一跳，仅合并在册节点间的互连边（KG 一跳
    # 查询只含触达中心的边，纯星形）。cap 6 个邻居防慢。
    if nseen:
        ndeg: dict = {}
        for e in edges:
            ndeg[e[_K_SOURCE]] = ndeg.get(e[_K_SOURCE], 0) + 1
            ndeg[e[_K_TARGET]] = ndeg.get(e[_K_TARGET], 0) + 1
        ranked = sorted((n for n in ndeg if n != q),
                        key=lambda n: -ndeg[n])
        tops = []
        for n in ranked[:6]:
            if len(n) <= _KG_LONG:
                tops.append(n)
        if tops:
            with ThreadPoolExecutor(max_workers=len(tops)) as ex:
                d2s = list(ex.map(lambda n: _kg_safefetch(n, palace), tops))
        else:
            d2s = []
        for d2 in d2s:
            for e2 in (d2.get(_K_EDGES) or [])[:200]:
                s2, t2 = str(e2.get(_K_SOURCE, "")), str(e2.get(_K_TARGET, ""))
                if s2 in nseen and t2 in nseen:
                    k2 = s2 + "|" + t2 + "|" + str(e2.get(_K_TYPE, ""))
                    if k2 not in eseen:
                        eseen.add(k2)
                        edges.append({_K_SOURCE: s2, _K_TARGET: t2,
                                      _K_TYPE: str(e2.get(_K_TYPE, "")),
                                      _K_LABEL: str(e2.get(_K_LABEL) or ""),
                                      _K_CONFIDENCE: e2.get(_K_CONFIDENCE),
                                      _K_VALID_FROM: e2.get(_K_VALID_FROM),
                                      _K_VALID_TO: e2.get(_K_VALID_TO),
                                      _K_CURRENT: e2.get(_K_CURRENT)})
        # 剪枝：重算度数，悬挂点（度 1 非中心）超 40 节点时只留 top15
        ndeg = {}
        for e in edges:
            ndeg[e[_K_SOURCE]] = ndeg.get(e[_K_SOURCE], 0) + 1
            ndeg[e[_K_TARGET]] = ndeg.get(e[_K_TARGET], 0) + 1
        if len(nseen) > 40:
            pendants_c = []
            for n in nseen:
                if n != q and ndeg.get(n, 0) <= 1:
                    pendants_c.append(n)
            pendants_c.sort(key=lambda n: -ndeg.get(n, 0))
            pendants = pendants_c[15:]
            if pendants:
                drop = set(pendants)
                nodes = [n for n in nodes if n[_K_ID] not in drop]
                kept_edges = []
                for e in edges:
                    if e[_K_SOURCE] not in drop and e[_K_TARGET] not in drop:
                        kept_edges.append(e)
                edges = kept_edges
    hint = None
    if not nseen:
        hint = (f"文档知识图谱无 '{q}' 的关系边（KG 实体名需精确匹配；"
                f"文档名可先在代码图谱搜到全名后自动查询）")
    return {"query": q, "palace": palace, _K_SOURCE: "mempalace-kg",
            "center": {_K_ID: q, _K_NAME: q} if nseen else None,
            _K_NODES: nodes, _K_EDGES: edges, "count": len(edges),
            "hint": hint,
            "elapsed_ms": round((time.time() - t0) * 1000, 1)}


