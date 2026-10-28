#!/usr/bin/env python3
"""
nl_query.py — 自然语言查询代码知识图谱（极速版）

直接读 SQLite 知识图谱（只读），无需启动 CBM daemon/CLI。
查询耗时从 ~90 秒降到 <50 毫秒。

用法:
    python3 nl_query.py "数据库连接初始化" -p data-hunan-bms
    python3 nl_query.py "账户权限校验" -p data-hunan-acc --top 8
    python3 nl_query.py --list-projects
    python3 nl_query.py "配置加载" -p data-hunan-bms --json

原理:
    自然语言 -> 关键词/符号提取(含中->英翻译) -> SQLite REGEXP 检索 ->
    命中节点的 callers/callees 展开 -> 知识子图返回
"""

import argparse
import json
import logging
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("nl_query")
_K_NAME = "name"        # 扫描 G.TYP.03：重复字典 key 常量化
_K_LABEL = "label"
_K_FILE = "file"
_K_MATCHED = "matched"
_EN_SAVE = "save"
_EN_VERIFY = "verify"
_EN_READ = "read"
_STORE_TRUE = "store_true"

# ── 配置 ────────────────────────────────────────────────────────────

CACHE_DIR = os.environ.get(
    "CBM_CACHE_DIR",
    os.path.expanduser("~/.cache/codebase-memory-mcp"),
)


# ── SQLite 知识图谱存储（极速只读）────────────────────────────────


def _regexp(pattern: str, value) -> int:
    """SQLite REGEXP 函数（SQLite 默认无正则，需注册）。"""
    if value is None:
        return 0
    try:
        return 1 if re.search(pattern, str(value)) else 0
    except re.error:
        return 0


class KBStore:
    """直接读 CBM 的 SQLite 知识图谱（只读模式，毫秒级查询）。"""

    def __init__(self, project: str):
        # 项目名 -> db 路径：data-hunan-bms -> ~/.cache/.../data-hunan-bms.db
        db_path = os.path.join(CACHE_DIR, f"{project}.db")
        if not os.path.exists(db_path):
            raise FileNotFoundError(
                f"项目 '{project}' 的图谱不存在: {db_path}\n"
                f"先用 --list-projects 查看已索引项目，或检查 CBM_CACHE_DIR。"
            )
        # 只读 URI 打开，避免误写
        self.conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        self.conn.create_function("REGEXP", 2, _regexp)
        self.project = project

    def close(self):
        self.conn.close()

    def search(self, keywords: list[str], limit: int = 8) -> list[dict]:
        """关键词联合正则检索（按出入度排序）。单条 SQL。"""
        if not keywords:
            return []
        pats = [re.escape(k) for k in keywords if k][:12]
        if not pats:
            return []
        regex = "|".join(pats)
        sql = (
            "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
            "n.start_line, "
            "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id AND e.type='CALLS') AS in_deg, "
            "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id AND e.type='CALLS') AS out_deg "
            "FROM nodes n "
            "WHERE n.label IN ('Function','Method','Class','Variable') "
            f"AND n.name REGEXP '.*({regex}).*' "
            "ORDER BY (in_deg + out_deg) DESC "
            "LIMIT ?"
        )
        rows = self.conn.execute(sql, (limit,)).fetchall()
        return [
            {
                "id": r[0], "label": r[1], _K_NAME: r[2],
                "qualified_name": r[3] or "", "file_path": r[4] or "",
                "start_line": r[5] or 0, "in_degree": r[6] or 0,
                "out_degree": r[7] or 0,
            }
            for r in rows
        ]

    def neighbors(self, name: str, limit: int = 10) -> dict:
        """按 name 定位节点，返回 callers + callees（含 JOIN）。"""
        esc = name.replace("\"", "\"\"")
        # 先拿 id（name 可能重名，取结构上最热的那个）
        nid = self.conn.execute(
            "SELECT n.id FROM nodes n WHERE n.name=? "
            "ORDER BY (SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) DESC LIMIT 1",
            (name,),
        ).fetchone()
        if not nid:
            return {"callers": [], "callees": []}
        nid = nid[0]
        callers = self._neighbor_rows(
            "SELECT n.name, n.label, n.file_path "
            "FROM edges e JOIN nodes n ON n.id=e.source_id "
            "WHERE e.target_id=? AND e.type='CALLS' LIMIT ?",
            (nid, limit),
        )
        callees = self._neighbor_rows(
            "SELECT n.name, n.label, n.file_path "
            "FROM edges e JOIN nodes n ON n.id=e.target_id "
            "WHERE e.source_id=? AND e.type='CALLS' LIMIT ?",
            (nid, limit),
        )
        return {"callers": callers, "callees": callees}

    def _neighbor_rows(self, sql: str, params: tuple) -> list[dict]:
        out: list[dict] = []
        for r in self.conn.execute(sql, params):
            out.append({_K_NAME: r[0], "label": r[1], "file": r[2]})
        return out


def list_projects() -> list[dict]:
    """扫描缓存目录的所有 .db，读取项目元数据。"""
    out = []
    for p in sorted(Path(CACHE_DIR).glob("*.db")):
        name = p.stem
        if name.startswith("_"):
            continue
        try:
            conn = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
            cnt = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
            edges = conn.execute("SELECT COUNT(*) FROM edges").fetchone()[0]
            root = conn.execute(
                "SELECT root_path FROM projects LIMIT 1"
            ).fetchone()
            conn.close()
            out.append({
                _K_NAME: name, "nodes": cnt, "edges": edges,
                "root_path": root[0] if root else "", "size_bytes": p.stat().st_size,
            })
        except sqlite3.Error:
            continue
    return out


# ── 自然语言 -> 关键词提取（含中->英翻译表）────────────────────────


def extract_keywords(question: str) -> dict:
    """从自然语言提问中提取搜索信号。"""
    stopwords = {
        "的", "了", "是", "在", "和", "与", "或", "如何", "怎么", "怎样",
        "哪些", "哪个", "什么", "为什么", "哪里", "请", "帮", "我", "看",
        "查找", "查询", "搜索", "找", "显示", "列出", "关于",
        "the", "a", "an", "how", "to", "what", "which", "where", "who",
        "is", "are", "do", "does", "for", "in", "of", "and", "or",
        "function", "method", "class", "call", "调用", "函数", "方法", "类",
    }

    cn_to_en = {
        "初始化": ["init", "initialize", "setup", "open"],
        "初始": ["init"],
        "连接": ["connect", "conn", "link"],
        "数据库": ["db", "database", "sql", "ora", "oracle", "mysql"],
        "配置": ["config", "cfg", "setting", "conf"],
        "设置": ["set", "config", "setup"],
        "读取": [_EN_READ, "get", "fetch", "load"],
        "读": [_EN_READ, "get"],
        "写入": ["write", "set", _EN_SAVE, "store"],
        "写": ["write", _EN_SAVE],
        "保存": [_EN_SAVE, "store", "persist"],
        "加载": ["load", "fetch"],
        "解析": ["parse", "decode"],
        "发送": ["send", "dispatch", "emit"],
        "接收": ["recv", "receive", _EN_READ],
        "校验": ["check", _EN_VERIFY, "validate"],
        "检查": ["check", _EN_VERIFY],
        "验证": [_EN_VERIFY, "validate", "auth"],
        "权限": ["privilege", "perm", "auth", "acl"],
        "账户": ["account", "acc", "user"],
        "计费": ["bill", "charge", "fee", "balance"],
        "余额": ["balance", "remain"],
        "日志": ["log", "trace", "debug"],
        "错误": ["error", "err", "fail"],
        "异常": ["exception", "error"],
        "关闭": ["close", "shutdown", "stop", "fini"],
        "启动": ["start", "run", "main", "boot"],
        "消息": ["message", "msg"],
        "处理": ["handle", "process", "proc"],
        "回调": ["callback", "cb"],
        "线程": ["thread"],
        "锁": ["lock", "mutex"],
        "缓存": ["cache", "buffer"],
        "内存": ["mem", "alloc", "malloc"],
        "释放": ["free", "release", "destroy"],
        "创建": ["create", "new", "make", "alloc"],
        "删除": ["delete", "remove", "del", "drop"],
        "更新": ["update", "modify"],
        "查询": ["query", "search", "find", "select"],
        "监听": ["listen", "listener"],
        "通知": ["notify", "notice"],
        "同步": ["sync"],
        "心跳": ["heartbeat", "hb"],
        "超时": ["timeout"],
        "重试": ["retry"],
        "签名": ["sign", "signature"],
        "加密": ["encrypt", "cipher"],
        "解码": ["decode"],
        "编码": ["encode"],
    }
    en_from_cn: list[str] = []
    for cn, ens in cn_to_en.items():
        if cn in question:
            en_from_cn.extend(ens)

    # 代码符号：camelCase / PascalCase / snake_case / ALL_CAPS
    symbol_pat = re.compile(
        r"[A-Za-z_][A-Za-z0-9_]*(?:[A-Z][a-z]|[a-z][A-Z]|_[a-z0-9])[A-Za-z0-9_]*"
        r"|[A-Z][A-Z_]{2,}[A-Z0-9_]*"
    )
    symbols = list(set(m.group(0) for m in symbol_pat.finditer(question)))

    tokens: list[str] = []
    for w in re.findall(r"[A-Za-z][A-Za-z0-9_]*", question):
        wl = w.lower()
        if wl not in stopwords and len(wl) >= 2:
            tokens.append(wl)
    for seg in re.findall(r"[一-鿿]+", question):
        tokens.append(seg)
        for i in range(len(seg) - 1):
            tokens.append(seg[i : i + 2])

    seen = set()
    tokens_unique = [t for t in tokens if not (t in seen or seen.add(t))]
    for e in en_from_cn:
        if e not in seen:
            tokens_unique.append(e)
            seen.add(e)

    return {"tokens": tokens_unique, "symbols": symbols, "raw": question,
            "en_from_cn": en_from_cn}


# ── 主查询 ──────────────────────────────────────────────────────────


def nl_query(question: str, project: str, top: int = 8, expand: bool = True) -> dict:
    """自然语言查询 -> 知识子图。全部走直接 SQLite，毫秒级。"""
    t0 = time.time()
    kw = extract_keywords(question)

    # 搜索信号：英文代码词（含中文翻译）+ 代码符号
    search_terms: list[str] = []
    for t in kw["tokens"]:
        if re.fullmatch(r"[a-z][a-z0-9_]*", t):
            search_terms.append(t)
    search_terms.extend(kw["symbols"])

    store = KBStore(project)
    hits = store.search(search_terms, limit=top) if search_terms else []

    # 构建知识子图
    nodes: list[dict] = []
    edges: list[dict] = []
    node_names: set[str] = set()
    for h in hits:
        nm = h[_K_NAME]
        nodes.append({**h, _K_MATCHED: True})
        node_names.add(nm)
        if expand:
            nb = store.neighbors(nm)
            for c in nb["callers"]:
                edges.append({"src": c[_K_NAME], "dst": nm, "type": "CALLS"})
                if c[_K_NAME] not in node_names:
                    nodes.append({_K_NAME: c[_K_NAME], _K_LABEL: c.get(_K_LABEL, ""),
                                  _K_FILE: c.get(_K_FILE, ""), _K_MATCHED: False})
                    node_names.add(c[_K_NAME])
            for c in nb["callees"]:
                edges.append({"src": nm, "dst": c[_K_NAME], "type": "CALLS"})
                if c[_K_NAME] not in node_names:
                    nodes.append({_K_NAME: c[_K_NAME], _K_LABEL: c.get(_K_LABEL, ""),
                                  _K_FILE: c.get(_K_FILE, ""), _K_MATCHED: False})
                    node_names.add(c[_K_NAME])
    store.close()

    return {
        "question": question, "project": project, "keywords": kw,
        "search_terms_used": search_terms, "matches": hits,
        "subgraph": {"nodes": nodes, "edges": edges},
        "elapsed_ms": round((time.time() - t0) * 1000, 1),
    }


# ── 格式化输出 ──────────────────────────────────────────────────────

C = {
    "GREEN": "\033[32m", "CYAN": "\033[36m", "YELLOW": "\033[33m",
    "MAGENTA": "\033[35m", "DIM": "\033[2m", "BOLD": "\033[1m",
    "RED": "\033[31m", "RESET": "\033[0m",
}
if not sys.stdout.isatty():
    for k in C:
        C[k] = ""


def print_result(result: dict, show_graph: bool) -> None:
    kw = result["keywords"]
    matches = result["matches"]
    log.info(f"\n{C['BOLD']}{C['CYAN']}╔══ 自然语言查询 ({result['elapsed_ms']}ms) ══╗{C['RESET']}")
    log.info(f"{C['BOLD']}║ 问题:{C['RESET']} {result['question']}")
    log.info(f"{C['BOLD']}║ 项目:{C['RESET']} {result['project']}")
    if kw.get("en_from_cn"):
        log.info(f"{C['DIM']}║ 中文->英文: {', '.join(kw['en_from_cn'][:10])}{C['RESET']}")
    if kw["symbols"]:
        log.info(f"{C['DIM']}║ 识别符号: {', '.join(kw['symbols'])}{C['RESET']}")
    log.info(f"{C['BOLD']}{C['CYAN']}╚══════════════════════════════╝{C['RESET']}\n")

    if not matches:
        log.info(f"{C['YELLOW']}未找到匹配。建议换更具体的关键词或代码符号名。{C['RESET']}")
        return

    log.info(f"{C['BOLD']}命中节点 ({len(matches)}):{C['RESET']}\n")
    for h in matches:
        deg = h["in_degree"] + h["out_degree"]
        star = f"{C['YELLOW']}★{C['RESET']}" if deg > 50 else " "
        log.info(f"  {star} {C['GREEN']}[{h['label']}]{C['RESET']} {C['BOLD']}{h['name']}{C['RESET']}")
        if h.get("qualified_name"):
            log.info(f"      {C['DIM']}{h['qualified_name']}{C['RESET']}")
        if h.get("file_path"):
            log.info(f"      📄 {h['file_path']}:{h.get('start_line', '?')}"
                  f"  {C['DIM']}(in:{h['in_degree']} out:{h['out_degree']}){C['RESET']}")

    if show_graph:
        sg = result["subgraph"]
        log.info(f"\n{C['BOLD']}知识子图 ({len(sg['nodes'])} 节点, {len(sg['edges'])} 边):{C['RESET']}\n")
        for h in matches:
            name = h[_K_NAME]
            in_e = [e for e in sg["edges"] if e.get("dst") == name and e["type"] == "CALLS"]
            out_e = [e for e in sg["edges"] if e.get("src") == name and e["type"] == "CALLS"]
            if not (in_e or out_e):
                continue
            log.info(f"  {C['MAGENTA']}● {name}{C['RESET']}")
            for e in in_e[:6]:
                log.info(f"      {C['GREEN']}← CALLS{C['RESET']} {e['src']}")
            for e in out_e[:6]:
                log.info(f"      {C['CYAN']}→ CALLS{C['RESET']} {e['dst']}")


# ── 入口 ────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(
        description="自然语言查询代码知识图谱（极速版，直接读 SQLite）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n"
               "  %(prog)s '数据库连接初始化' -p data-hunan-bms\n"
               "  %(prog)s '账户权限校验' -p data-hunan-acc --top 8\n"
               "  %(prog)s --list-projects",
    )
    ap.add_argument("question", nargs="?", help="自然语言问题")
    ap.add_argument("-p", "--project", default="data-hunan-bms", help="项目名")
    ap.add_argument("--top", type=int, default=6, help="返回节点数（默认 6）")
    ap.add_argument("--graph", action=_STORE_TRUE, default=True, help="展开知识子图（默认开）")
    ap.add_argument("--no-graph", dest="graph", action="store_false", help="只返回命中列表")
    ap.add_argument("--json", action=_STORE_TRUE, help="输出原始 JSON")
    ap.add_argument("--list-projects", action=_STORE_TRUE, help="列出所有已索引项目")
    args = ap.parse_args()

    if args.list_projects:
        projs = list_projects()
        log.info(f"已索引 {len(projs)} 个项目:\n")
        for p in sorted(projs, key=lambda x: x.get("nodes", 0), reverse=True):
            log.info(f"  {p['name']:<28} {p.get('nodes',0):>7} nodes  {p.get('edges',0):>7} edges")
        return 0

    if not args.question:
        ap.print_help()
        return 1

    result = nl_query(args.question, args.project, top=args.top, expand=args.graph)
    if args.json:
        log.info(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_result(result, args.graph)
    return 0


if __name__ == "__main__":
    sys.exit(main())
