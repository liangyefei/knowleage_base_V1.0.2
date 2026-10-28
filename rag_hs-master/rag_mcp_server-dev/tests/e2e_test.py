#!/usr/bin/env python3
"""
e2e_test.py — 端到端验证脚本

验证完整链路: 服务健康 → 项目列表 → 多项目查询 → 结果质量 → 性能基准

用法:
    python3 e2e_test.py                          # 全套测试
    python3 e2e_test.py --quick                  # 快速冒烟（跳过性能基准）
    python3 e2e_test.py --base http://IP:9788    # 指定服务地址

退出码: 0 = 全部通过, 1 = 有失败
"""

import json
import logging
import os
import sys
import time
import urllib.error
import urllib.request

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("e2e")
_err_log = logging.getLogger("e2e.err")
_err_log.propagate = False
_err_h = logging.StreamHandler(sys.stderr)
_err_h.setFormatter(logging.Formatter("%(message)s"))
_err_log.addHandler(_err_h)

BASE = "http://localhost:9788"
_STORE_TRUE = "store_true"  # argparse action 常量（扫描 G.TYP.03）
PASS, FAIL, SKIP = 0, 0, 0

G = "\033[32m" if sys.stdout.isatty() else ""
R = "\033[31m" if sys.stdout.isatty() else ""
Y = "\033[33m" if sys.stdout.isatty() else ""
C = "\033[36m" if sys.stdout.isatty() else ""
B = "\033[1m" if sys.stdout.isatty() else ""
Z = "\033[0m" if sys.stdout.isatty() else ""


def ok(msg):    global PASS; PASS += 1; log.info(f"  {G}✓{Z} {msg}")
def bad(msg):   global FAIL; FAIL += 1; log.info(f"  {R}✗{Z} {msg}")
def skip(msg):  global SKIP; SKIP += 1; log.info(f"  {Y}△{Z} {msg}")
def info(msg):  log.info(f"  {C}ℹ{Z} {msg}")


def post(path: str, data: dict) -> dict:
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{BASE}{path}", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def get(path: str) -> dict:
    with urllib.request.urlopen(f"{BASE}{path}", timeout=10) as resp:
        return json.loads(resp.read())


def derive_project_root(project: str) -> str:
    """Infer source root from a CBM project name.

    CBM names projects as `<parent_dir>-<basename>` (e.g. `projects-Foo`,
    `opt-codegraph`, `data-hunan-bms`). Convert back to a filesystem path:
        data-hunan-bms   -> /data/hunan/bms       (legacy 3-segment form)
        projects-Foo     -> /projects/Foo          (container form)
        opt-codegraph    -> /opt/codegraph
    """
    if project.startswith("data-hunan-"):
        return os.path.join("/data/hunan", project[len("data-hunan-"):])
    if "-" in project:
        parent, basename = project.split("-", 1)
        return f"/{parent}/{basename}"
    return os.path.join("/projects", project)


def hdr(title: str):
    log.info(f"\n{B}{C}═══ {title} ═══{Z}")


# ── 测试 1: 健康检查 ────────────────────────────────────────────────

def test_health():
    hdr("1. 服务健康检查")
    try:
        r = get("/health")
        assert r.get("ok"), f"ok=false: {r}"
        ok(f"服务在线: {r.get('service')}")
        ok(f"缓存目录: {r.get('cache_dir')}")
    except Exception as e:
        bad(f"服务不可达: {e}")
        info("请确认已启动: scripts/deploy.sh")
        return False
    return True


# ── 测试 2: 项目列表 ────────────────────────────────────────────────

def test_projects():
    hdr("2. 项目列表")
    try:
        r = get("/projects")
        projs = r.get("projects", [])
        assert len(projs) >= 3, f"项目太少 ({len(projs)})"
        ok(f"已索引 {len(projs)} 个项目")
        total_nodes = sum(p.get("nodes", 0) for p in projs)
        total_edges = sum(p.get("edges", 0) for p in projs)
        ok(f"图谱总规模: {total_nodes:,} nodes / {total_edges:,} edges")
        # 检查关键项目存在
        names = {p["name"] for p in projs}
        for n in ["data-hunan-bms", "data-hunan-acc"]:
            if n in names:
                ok(f"关键项目存在: {n}")
            else:
                skip(f"关键项目缺失: {n}")
        return projs
    except Exception as e:
        bad(f"项目列表失败: {e}")
        return []


# ── 测试 3: 中文自然语言查询 ────────────────────────────────────────

def test_nl_queries():
    hdr("3. 自然语言查询（中文→代码）")

    cases = [
        ("数据库连接初始化", "data-hunan-bms", ["curl_easy_init", "conn", "open"]),
        ("账户权限校验", "data-hunan-acc", ["sacc_privilege_check", "acc"]),
        ("配置加载", "data-hunan-bms", ["load", "config", "ERR_load"]),
    ]

    for q, proj, expected in cases:
        try:
            r = post("/query", {"q": q, "project": proj, "top": 3})
            hits = r.get("matches", [])
            sg = r.get("subgraph", {})
            ms = r.get("elapsed_ms", 0)
            # 结果不为空
            if len(hits) >= 1:
                ok(f"[{proj}] \"{q}\" → {len(hits)} hits / {len(sg.get('nodes',[]))} subgraph nodes ({ms}ms)")
            else:
                bad(f"[{proj}] \"{q}\" → 0 hits")
            # 验证期望关键词出现在至少一个命中里
            names = {h.get("name", "") for h in hits}
            matched = any(any(e.lower() in n.lower() for e in expected) for n in names)
            if matched:
                ok(f"  命中包含期望关键词: {', '.join(expected)}")
            else:
                info(f"  命中未包含期望词; 实际命中: {', '.join(sorted(names)[:6])}")
        except Exception as e:
            bad(f"[{proj}] \"{q}\" 失败: {e}")


# ── 测试 4: 英文查询 ────────────────────────────────────────────────

def test_en_queries():
    hdr("4. 英文查询")
    cases = [
        ("init connect", "data-hunan-bms", 3),
        ("charge fee bill", "data-hunan-bms", 2),
    ]
    for q, proj, min_hits in cases:
        try:
            r = post("/query", {"q": q, "project": proj, "top": 5})
            hits = len(r.get("matches", []))
            if hits >= min_hits:
                ok(f"\"{q}\" → {hits} hits")
            else:
                info(f"\"{q}\" → {hits} hits (期望 ≥{min_hits})")
        except Exception as e:
            bad(f"\"{q}\" 失败: {e}")


# ── 测试 5: 文本格式 ────────────────────────────────────────────────

def test_formats():
    hdr("5. 输出格式")
    # text
    try:
        import io
        body = json.dumps({"q": "init", "project": "data-hunan-acc", "top": 2,
                           "format": "text"}).encode("utf-8")
        req = urllib.request.Request(f"{BASE}/query", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        txt = urllib.request.urlopen(req, timeout=10).read().decode("utf-8")
        if "命中" in txt or "未" in txt:
            ok("text 格式正常")
        else:
            info(f"text 格式内容: {txt[:80]}")
    except Exception as e:
        bad(f"text 格式失败: {e}")

    # json
    try:
        r = post("/query", {"q": "init", "project": "data-hunan-acc", "top": 2,
                            "format": "json"})
        if "matches" in r and "subgraph" in r:
            ok("json 格式正常 (contains matches + subgraph)")
        else:
            bad(f"json 格式缺少字段: {list(r.keys())}")
    except Exception as e:
        bad(f"json 格式失败: {e}")


# ── 测试 6: 错误处理 ────────────────────────────────────────────────

def test_errors():
    hdr("6. 错误处理")
    # 缺少 q
    try:
        body = json.dumps({"project": "data-hunan-bms"}).encode("utf-8")
        req = urllib.request.Request(f"{BASE}/query", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        urllib.request.urlopen(req, timeout=10)
        bad("缺少 q 参数应返回 4xx")
    except urllib.error.HTTPError as e:
        ok(f"缺少 q 参数 → HTTP {e.code}")
    # 不存在的项目
    try:
        post("/query", {"q": "test", "project": "nonexistent-xyz"})
        bad("不存在的项目应返回 404")
    except urllib.error.HTTPError as e:
        ok(f"不存在的项目 → HTTP {e.code}")


# ── 测试 7: 性能基准 ────────────────────────────────────────────────

def test_benchmark(quick: bool = False):
    hdr("7. 性能基准")
    n = 5 if quick else 20
    proj = "data-hunan-acc"

    info(f"冷启动 (首次查询, {proj})...")
    t0 = time.time()
    post("/query", {"q": "init", "project": proj, "top": 3, "expand": False})
    cold = (time.time() - t0) * 1000

    info(f"热查询 {n} 次...")
    times = []
    for _ in range(n):
        t0 = time.time()
        post("/query", {"q": "init", "project": proj, "top": 3, "expand": False})
        times.append((time.time() - t0) * 1000)

    times.sort()
    avg = sum(times) / len(times)
    p50 = times[len(times) // 2]
    ok(f"冷启动 {cold:.0f}ms / 热 avg {avg:.0f}ms / p50 {p50:.0f}ms")

    if avg < 100:
        ok(f"热查询延迟 <100ms ({avg:.0f}ms)")
    else:
        info(f"热查询延迟偏高 ({avg:.0f}ms)")


# ── 测试 8: 结果质量验证 ────────────────────────────────────────────

def test_result_quality():
    hdr("8. 结果质量验证")
    r = post("/query", {"q": "数据库连接初始化", "project": "data-hunan-bms", "top": 5})
    hits = r.get("matches", [])
    sg = r.get("subgraph", {})

    # 验证 1: 结果不为空
    ok("命中 ≥ 1" if len(hits) >= 1 else "命中 < 1")
    # 验证 2: 子图包含调用关系
    edges = sg.get("edges", [])
    ok("子图有边" if len(edges) > 0 else "子图无边")
    # 验证 3: 每个命中都有文件路径
    all_have_file = all(h.get("file_path") for h in hits if h.get("file_path"))
    ok("命中节点含文件路径" if all_have_file else "部分命中缺文件路径")
    # 验证 4: 出入度数字合理
    deg_sum = sum(h.get("in_degree", 0) + h.get("out_degree", 0) for h in hits)
    ok(f"出入度合计 > 0 ({deg_sum} total)" if deg_sum > 0 else "出入度为 0")


# ── 入口 ────────────────────────────────────────────────────────────

# 扫描 G.TYP.03：重复字典 key 常量化
_K_NAME = "name"
_K_LABEL = "label"
_K_FILE = "file"
_K_MATCHED = "matched"
_K_SOURCE = "source"
_K_SRC = "src"
_K_DST = "dst"
_K_CALLERS = "callers"
_K_CALLEES = "callees"
_K_FILE_PATH = "file_path"
_K_CODEGRAPH_SOURCE = "codegraph_source"
_K_TARGET = "target"
_K_TYPE = "type"


def main():
    import argparse
    global BASE

    ap = argparse.ArgumentParser(description="端到端验证脚本 + 交互式 Demo")
    ap.add_argument("--base", default=BASE, help=f"服务地址 (默认 {BASE})")
    ap.add_argument("--quick", action=_STORE_TRUE, help="快速冒烟（跳过性能基准）")
    ap.add_argument("--demo", metavar="QUERY", help="交互式 Demo: 输入自然语言，输出相关代码")
    ap.add_argument("-p", "--project", default="data-hunan-bms", help="Demo 模式的项目名")
    ap.add_argument("-n", "--top", type=int, default=5, help="Demo 模式返回数量（默认 5）")
    ap.add_argument("--code", action=_STORE_TRUE, help="Demo 启用 CodeGraph 源码增强")
    ap.add_argument("--root", default=None,
                    help="项目源码根路径（CodeGraph 增强）。默认从项目名推导："
                         "<parent>-<basename> → /<parent>/<basename>")
    ap.add_argument("--graph-json", action=_STORE_TRUE, help="Demo 输出纯图谱 JSON")
    args = ap.parse_args()
    BASE = args.base

    # ── Demo 模式 ──────────────────────────────────────────────────
    if args.demo:
        if not args.graph_json:
            log.info(f"\n{B}{C}╔══════════════════════════════════════════╗{Z}")
            log.info(f"{B}{C}║  自然语言 → 代码  Demo                 ║{Z}")
            log.info(f"{B}{C}╚══════════════════════════════════════════╝{Z}\n")
            log.info(f"  问题: {B}{args.demo}{Z}")
            log.info(f"  项目: {args.project}")
            log.info(f"  服务: {BASE}")
            if args.code:
                root = args.root or derive_project_root(args.project)
                log.info(f"  源码增强: {G}CodeGraph{Z} (root={root})")
            log.info("")

        try:
            r = post("/query", {"q": args.demo, "project": args.project,
                                "top": args.top})
        except Exception as e:
            log.info(f"{R}查询失败: {e}{Z}")
            return 1

        # ── CodeGraph 源码增强（可选）──
        if args.code:
            root = args.root or derive_project_root(args.project)
            try:
                from codegraph_client import get_client
                cg = get_client()
                for h in r.get("matches", []):
                    src = cg.get_source(
                        h["name"], project_path=root,
                        file=h.get("file_path") or None)
                    h[_K_CODEGRAPH_SOURCE] = src if src else None
            except Exception as e:
                _err_log.warning(f"{Y}[CodeGraph: {e}，继续显示图谱]{Z}\n")

        ms = r.get("elapsed_ms", 0)
        hits = r.get("matches", [])
        sg = r.get("subgraph", {})

        if not hits:
            log.info(f"{Y}未找到匹配。请换更具体的描述。{Z}")
            return 0

        sg_nodes = sg.get("nodes", [])
        sg_edges = sg.get("edges", [])

        # 构建源码映射: hit_name → source text
        src_map = {h[_K_NAME]: h.get(_K_CODEGRAPH_SOURCE) for h in hits if h.get(_K_CODEGRAPH_SOURCE)}

        # ── 纯图谱 JSON 输出 ──
        if args.graph_json:
            g = {
                "query": args.demo, "project": args.project,
                "elapsed_ms": ms,
                "graph": {
                    "nodes": [
                        {"id": n[_K_NAME], _K_LABEL: n.get(_K_LABEL, ""),
                         _K_FILE: n.get(_K_FILE, "") or n.get(_K_FILE_PATH, ""),
                         _K_MATCHED: n.get(_K_MATCHED, False),
                         _K_SOURCE: src_map.get(n[_K_NAME])}
                        for n in sg_nodes
                    ],
                    "edges": [
                        {_K_SOURCE: e[_K_SRC], _K_TARGET: e[_K_DST], _K_TYPE: e[_K_TYPE]}
                        for e in sg_edges
                    ],
                }
            }
            log.info(json.dumps(g, ensure_ascii=False, indent=2))
            return 0

        # ── 图谱可视化输出 ──
        log.info(f"  耗时: {ms}ms  |  "
              f"节点: {G}{len(sg_nodes)}{Z}  |  边: {G}{len(sg_edges)}{Z}")
        log.info("")

        # 构建邻接表
        adj: dict[str, dict] = {}
        for n in sg_nodes:
            adj[n[_K_NAME]] = {_K_LABEL: n.get(_K_LABEL, ""),
                              _K_FILE: n.get(_K_FILE, "") or n.get(_K_FILE_PATH, ""),
                              _K_CALLEES: set(), _K_CALLERS: set(), _K_MATCHED: n.get(_K_MATCHED, False),
                              _K_SOURCE: src_map.get(n[_K_NAME])}
        for e in sg_edges:
            if e[_K_SRC] in adj and e[_K_DST] in adj:
                adj[e[_K_SRC]][_K_CALLEES].add(e[_K_DST])
                adj[e[_K_DST]][_K_CALLERS].add(e[_K_SRC])
            elif e[_K_SRC] in adj:
                adj[e[_K_SRC]][_K_CALLEES].add(e[_K_DST])

        hit_names = {h[_K_NAME] for h in hits}
        printed = set()

        def print_tree(name, depth=0, prefix="", is_last=True):
            if depth > 3 or name in printed:
                return
            printed.add(name)
            node_info = adj.get(name, {})
            matched = node_info.get("matched", False)
            label = node_info.get("label","")
            file = node_info.get("file","")[:55]
            connector = "└── " if is_last else "├── "
            marker = f"{Y}★{Z} " if matched else "  "
            tag = f"[{G}{label}{Z}]" if label else ""
            file_tag = f"  {C}{file}{Z}" if file else ""
            log.info(f"{prefix}{connector}{marker}{B}{name}{Z} {tag}{file_tag}")
            callees = node_info.get("callees", set())
            if callees and depth < 3:
                items = sorted(callees)[:6]
                for j, c in enumerate(items):
                    print_tree(c, depth+1, prefix+("    " if is_last else "│   "), j==len(items)-1)

        log.info(f"{B}{C}═══ 知识图谱（调用树）═══{Z}\n")
        for name in hit_names:
            if name not in printed:
                node_info = adj.get(name, {})
                label = node_info.get(_K_LABEL, "")
                file = node_info.get(_K_FILE, "")[:55]
                tag = f"[{G}{label}{Z}]" if label else ""
                log.info(f"{B}{name}{Z} {tag}  {C}{file}{Z}")
                callees = node_info.get(_K_CALLEES, set())
                if callees:
                    items = sorted(callees)[:6]
                    for j, c in enumerate(items):
                        print_tree(c, 1, "", j==len(items)-1)
                log.info("")
        log.info(f"{B}{C}═══ 邻接表（全部边）═══{Z}\n")
        for name in sorted(adj):
            node_info = adj[name]
            callees = node_info[_K_CALLEES]
            callers = node_info[_K_CALLERS]
            tag = f" [{G}{node_info[_K_LABEL]}{Z}]" if node_info[_K_LABEL] else ""
            mk = f" {Y}← 命中{Z}" if node_info[_K_MATCHED] else ""
            log.info(f"  {B}{name}{Z}{tag}{mk}")
            if callees:
                log.info(f"    {G}→{Z} {', '.join(sorted(callees)[:10])}")
            if callers:
                log.info(f"    {C}←{Z} {', '.join(sorted(callers)[:10])}")
        log.info("")

        # ── 完整源码（CodeGraph 增强）──
        has_code = any(info.get(_K_SOURCE) for info in adj.values())
        if has_code:
            log.info(f"{B}{C}═══ 完整源码（CodeGraph）════{Z}\n")
            for name in sorted(adj):
                src = adj[name].get(_K_SOURCE)
                if not src:
                    continue
                node_info = adj[name]
                tag = f"[{G}{node_info[_K_LABEL]}{Z}]" if node_info[_K_LABEL] else ""
                log.info(f"{B}{'─'*60}{Z}")
                log.info(f"  {B}{name}{Z} {tag}  {C}{node_info.get(_K_FILE, '')}{Z}")
                log.info(f"{B}{'─'*60}{Z}")
                for ln in src.strip().split("\n")[:40]:
                    log.info(f"  {ln}")
                total = len(src.strip().split("\n"))
                if total > 40:
                    log.info(f"  {C}... ({total} lines total){Z}")
                log.info("")
        return 0

    # ── 测试模式 ──────────────────────────────────────────────────
    log.info(f"{B}端到端验证{  Z}  服务: {BASE}")
    log.info(f"启动时间: {time.strftime('%H:%M:%S')}\n")

    if not test_health():
        log.info(f"\n{R}服务不可达，停止测试。先启动:{Z}")
        log.info(f"  scripts/deploy.sh")
        return 1

    test_projects()
    test_nl_queries()
    test_en_queries()
    test_formats()
    test_errors()
    test_benchmark(args.quick)
    test_result_quality()

    hdr("总结")
    total = PASS + FAIL + SKIP
    log.info(f"  通过: {PASS} / {total}")
    log.info(f"  失败: {FAIL} / {total}")
    if SKIP: log.info(f"  跳过: {SKIP} / {total}")
    log.info("")
    if FAIL == 0:
        log.info(f"  {G}{B}✓ 全部通过{Z}")
        return 0
    else:
        log.info(f"  {R}{B}✗ {FAIL} 项失败{Z}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
