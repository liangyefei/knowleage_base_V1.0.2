#!/usr/bin/env python3
"""
code_search.py — 自然语言 → 相关代码

用中文自然语言描述你的问题，直接返回相关代码文件和调用关系。
集成了 CBM（结构检索）+ CodeGraph（源码体返回）。

用法:
    python3 code_search.py "数据库连接初始化"
    python3 code_search.py "账户权限校验" -p data-hunan-acc
    python3 code_search.py "计费余额计算" -p data-hunan-bms --top 8
    python3 code_search.py "消息处理回调" -p data-hunan-pis_code --json
    python3 code_search.py --list
    python3 code_search.py "uart_init" -p data-hunan-aiutil --code   # 含源码

依赖: 仅标准库。需要 nl_query_server.py 在 9788 端口运行；
      CodeGraph 为可选增强（--code 时启用）。
"""

import argparse
import json
import logging
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("code_search")
_err_log = logging.getLogger("code_search.err")
_err_log.propagate = False
_err_h = logging.StreamHandler(sys.stderr)
_err_h.setFormatter(logging.Formatter("%(message)s"))
_err_log.addHandler(_err_h)

BASE = "http://localhost:9788"
_K_CG_SOURCE = "codegraph_source"  # 扫描 G.TYP.03：重复字典 key 常量化
_STORE_TRUE = "store_true"

# ── 查询 ────────────────────────────────────────────────────────────


def derive_project_root(project: str) -> str:
    """Infer source root from a CBM project name `<parent_dir>-<basename>`.

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


def do_query(q: str, project: str, top: int) -> dict:
    t0 = time.time()
    body = json.dumps({"q": q, "project": project, "top": top, "expand": True}).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}/query", data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    result["wall_ms"] = round((time.time() - t0) * 1000)
    return result


def list_projects() -> list[dict]:
    with urllib.request.urlopen(f"{BASE}/projects", timeout=10) as resp:
        return json.loads(resp.read()).get("projects", [])


# ── 格式化输出 ──────────────────────────────────────────────────────


def enrich_with_codegraph(result: dict, root_path: str):
    """用 CodeGraph 给命中节点补充源码体。"""
    try:
        from codegraph_client import get_client
        cg = get_client()
        for h in result.get("matches", []):
            code = cg.get_source(h["name"], project_path=root_path, file=h["file_path"] or None)
            h[_K_CG_SOURCE] = code if code else None
    except Exception as e:
        # CodeGraph 不可用时静默降级
        log.debug("codegraph enrich 降级: %s", e)


def print_result(result: dict):
    matches = result.get("matches", [])
    sg = result.get("subgraph", {})
    wall_ms = result.get("wall_ms", result.get("elapsed_ms", 0))

    if not matches:
        log.info("未找到匹配。请换更具体的描述或代码符号名。")
        return

    has_cg = any(_K_CG_SOURCE in h and h[_K_CG_SOURCE] for h in matches)
    log.info(f"查询: {result['question']}  ({wall_ms}ms)")
    log.info(f"项目: {result['project']}"
          f"{'  + CodeGraph 源码' if has_cg else ''}")
    log.info("")

    for i, h in enumerate(matches, 1):
        deg = h["in_degree"] + h["out_degree"]
        star = "★" if deg > 50 else " "
        log.info(f"{i}. {star} [{h['label']}] {h['name']}")
        log.info(f"   📄 {h['file_path']}:{h.get('start_line','?')}"
              f"  (调用了 {h['in_degree']} 次 / 被 {h['out_degree']} 个调用)")
        callees = [e for e in sg["edges"] if e.get("src") == h["name"] and e["type"] == "CALLS"]
        if callees:
            log.info(f"   → 调用: {', '.join(sorted(set(e['dst'] for e in callees))[:10])}")
        callers = [e for e in sg["edges"] if e.get("dst") == h["name"] and e["type"] == "CALLS"]
        if callers:
            log.info(f"   ← 被调: {', '.join(sorted(set(e['src'] for e in callers))[:10])}")
        # CodeGraph 源码体
        src = h.get(_K_CG_SOURCE)
        if src:
            lines = src.strip().split("\n")[:25]
            log.info(f"   ┌─ 源码 (CodeGraph) {'─'*40}")
            for ln in lines:
                log.info(f"   │ {ln}")
            if len(src.strip().split("\n")) > 25:
                log.info(f"   │ ... ({len(src.strip().split(chr(10)))} lines total)")
            log.info(f"   └{'─'*59}")
        log.info("")


def print_json(result: dict):
    log.info(json.dumps(result, ensure_ascii=False, indent=2))


# ── 入口 ────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(
        description="自然语言 → 相关代码",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n"
               "  %(prog)s \"数据库连接初始化\"\n"
               "  %(prog)s \"账户权限校验\" -p data-hunan-acc\n"
               "  %(prog)s --list",
    )
    ap.add_argument("question", nargs="?", help="自然语言问题（中文或英文）")
    ap.add_argument("-p", "--project", default=os.environ.get("CBM_PROJECT", "data-hunan-bms"),
                    help="项目名（默认读 $CBM_PROJECT，再退回 data-hunan-bms）")
    ap.add_argument("--top", type=int, default=5, help="返回代码片段数（默认 5）")
    ap.add_argument("--code", action=_STORE_TRUE, help="启用 CodeGraph 返回完整源码体")
    ap.add_argument("--root", default=None,
                    help="项目源码根路径（CodeGraph 增强）。默认从项目名推导。")
    ap.add_argument("--json", action=_STORE_TRUE, help="输出 JSON 格式")
    ap.add_argument("--list", action=_STORE_TRUE, help="列出所有项目")
    args = ap.parse_args()

    if args.list:
        try:
            projs = list_projects()
            log.info(f"已索引 {len(projs)} 个项目:\n")
            for p in sorted(projs, key=lambda x: x.get("nodes", 0), reverse=True):
                log.info(f"  {p['name']:<28} {p.get('nodes',0):>7} nodes  "
                      f"{p.get('edges',0):>7} edges")
        except Exception as e:
            log.info(f"服务连接失败: {e}")
            log.info(f"请确认服务已启动: python3 nl_query_server.py")
            return 1
        return 0

    if not args.question:
        ap.print_help()
        return 1

    try:
        result = do_query(args.question, args.project, args.top)
    except urllib.error.HTTPError as e:
        err = json.loads(e.read().decode()) if e.fp else {"detail": str(e)}
        log.info(f"查询失败 ({e.code}): {err.get('detail', str(e))}")
        return 1
    except Exception as e:
        log.info(f"服务连接失败: {e}")
        log.info(f"请确认服务已启动: python3 nl_query_server.py")
        return 1

    # CodeGraph 源码增强（可选）
    if args.code:
        root = args.root or derive_project_root(args.project)
        try:
            enrich_with_codegraph(result, root)
        except Exception as e:
            _err_log.warning(f"[CodeGraph 增强失败: {e}，显示 CBM 结果]")

    if args.json:
        print_json(result)
    else:
        print_result(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
