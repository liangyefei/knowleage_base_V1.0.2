#!/usr/bin/env python3
"""
test_nl_server.py — 测试自然语言查询常驻服务

用法:
    python3 test_nl_server.py                                  # 默认全套测试
    python3 test_nl_server.py --base http://localhost:9788     # 指定服务地址
    python3 test_nl_server.py --bench                          # 只跑性能基准
    python3 test_nl_server.py -q "账户权限校验" -p data-hunan-acc  # 单次查询

依赖: 仅标准库（urllib），无需 pip install。
"""

import argparse
import json
import logging
import os
import sys
import time

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("nl_server_test")
import urllib.parse
import urllib.request

DEFAULT_BASE = "http://localhost:9788"

# ANSI 颜色（非 TTY 时自动关闭）
C = {
    "GREEN": "\033[32m", "RED": "\033[31m", "YELLOW": "\033[33m",
    "CYAN": "\033[36m", "MAGENTA": "\033[35m", "DIM": "\033[2m",
    "BOLD": "\033[1m", "RESET": "\033[0m",
}
if not sys.stdout.isatty():
    C = {k: "" for k in C}


def ok(msg):   log.info(f"  {C['GREEN']}✓{C['RESET']} {msg}")
def fail(msg): log.info(f"  {C['RED']}✗{C['RESET']} {msg}")
def info(msg): log.info(f"  {C['CYAN']}ℹ{C['RESET']} {msg}")


def http_get(base: str, path: str, params: dict | None = None, timeout: float = 30) -> dict | str:
    """发起 GET 请求，返回解析后的 JSON dict 或原始文本。"""
    url = base.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        ctype = resp.headers.get("Content-Type", "")
        if "json" in ctype:
            return json.loads(body)
        return body


def http_post(base: str, path: str, data: dict | None = None, timeout: float = 30) -> dict | str:
    """发起 POST 请求，body 为 JSON。"""
    url = base.rstrip("/") + path
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data else b"{}"
    req = urllib.request.Request(url, data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        b = resp.read().decode("utf-8")
        ctype = resp.headers.get("Content-Type", "")
        return json.loads(b) if "json" in ctype else b


def header(title: str):
    log.info(f"\n{C['BOLD']}{C['CYAN']}═══ {title} ═══{C['RESET']}")


# ── 测试用例 ────────────────────────────────────────────────────────


def test_health(base: str) -> bool:
    """测试健康检查端点。"""
    header("1. 健康检查")
    try:
        r = http_get(base, "/health")
        if r.get("ok"):
            ok(f"服务在线: {r.get('service')}  缓存项目: {r.get('cached_projects')}")
            return True
        fail(f"健康检查返回异常: {r}")
    except Exception as e:
        fail(f"无法连接服务（{e}）。确认服务已启动: python3 nl_query_server.py")
    return False


def test_projects(base: str) -> list[dict]:
    """测试项目列表端点。"""
    header("2. 项目列表")
    try:
        r = http_get(base, "/projects")
        projs = r.get("projects", [])
        ok(f"已索引 {len(projs)} 个项目")
        for p in sorted(projs, key=lambda x: x.get("nodes", 0), reverse=True)[:5]:
            log.info(f"      {p['name']:<28} {p.get('nodes',0):>7} nodes  {p.get('edges',0):>7} edges")
        if len(projs) > 5:
            log.info(f"      {C['DIM']}... 共 {len(projs)} 个{C['RESET']}")
        return projs
    except Exception as e:
        fail(f"获取项目列表失败: {e}")
        return []


def test_query(base: str, question: str, project: str, top: int = 5) -> dict | None:
    """单次查询测试，返回结果 dict。"""
    t0 = time.time()
    try:
        r = http_post(base, "/query", {"q": question, "project": project, "top": top})
    except Exception as e:
        fail(f"查询失败（{project} / {question}）: {e}")
        return None
    wall = (time.time() - t0) * 1000

    if not isinstance(r, dict):
        fail(f"返回格式异常: {type(r)}")
        return None

    matches = r.get("matches", [])
    server_ms = r.get("elapsed_ms", 0)
    sg = r.get("subgraph", {})
    color = C["GREEN"] if matches else C["YELLOW"]
    log.info(f"  {color}[{project}]{C['RESET']} \"{question}\"")
    log.info(f"      命中 {len(matches)} 节点, 子图 {len(sg.get('nodes',[]))} 节点/"
          f"{len(sg.get('edges',[]))} 边  "
          f"{C['DIM']}服务端 {server_ms}ms / 端到端 {wall:.0f}ms{C['RESET']}")
    for h in matches[:3]:
        deg = h.get("in_degree", 0) + h.get("out_degree", 0)
        star = f"{C['YELLOW']}★{C['RESET']}" if deg > 50 else " "
        log.info(f"      {star} [{h.get('label')}] {C['BOLD']}{h.get('name')}{C['RESET']}"
              f"  {C['DIM']}@ {h.get('file_path','')}{C['RESET']}")
    return r


PROJ_BMS = "data-hunan-bms"  # 扫描 G.TYP.03：重复字面量常量化


def test_query_suite(base: str, projects: list[str]):
    """跨项目多问题查询套件。"""
    header("3. 自然语言查询套件")
    cases = [
        ("数据库连接初始化", PROJ_BMS),
        ("配置加载", PROJ_BMS),
        ("账户权限校验", "data-hunan-acc"),
        ("计费余额计算", PROJ_BMS),
        ("消息处理回调", "data-hunan-pis_code"),
    ]
    results = []
    for q, p in cases:
        if projects and p not in projects:
            info(f"跳过 {p}（项目不存在）")
            continue
        r = test_query(base, q, p, top=4)
        if r:
            results.append((q, p, r))
    return results


def test_formats(base: str):
    """测试 JSON / text 两种输出格式。"""
    header("4. 输出格式")
    # text 格式
    try:
        txt = http_post(base, "/query",
                       {"q": "init", "project": "data-hunan-bms", "top": 2, "format": "text"})
        if isinstance(txt, str) and "命中" in txt:
            ok("text 格式正常")
            for line in txt.split("\n")[:4]:
                log.info(f"      {C['DIM']}{line}{C['RESET']}")
        else:
            fail("text 格式异常")
    except Exception as e:
        fail(f"text 格式失败: {e}")
    # json 格式
    try:
        js = http_post(base, "/query",
                      {"q": "init", "project": "data-hunan-bms", "top": 2, "format": "json"})
        if isinstance(js, dict) and "matches" in js:
            ok("json 格式正常")
        else:
            fail("json 格式异常")
    except Exception as e:
        fail(f"json 格式失败: {e}")


def test_error_handling(base: str):
    """测试错误处理。"""
    header("5. 错误处理")
    # 缺少 q 参数
    try:
        r = http_post(base, "/query", {"project": "data-hunan-bms"})
        if isinstance(r, dict) and "error" in r:
            ok("缺少 q 参数返回 400 错误")
        else:
            fail(f"缺少 q 参数未正确报错: {r}")
    except urllib.error.HTTPError as e:
        if e.code == 400:
            ok("缺少 q 参数返回 HTTP 400")
        else:
            fail(f"缺少 q 参数返回 {e.code}")
    # 不存在的项目
    try:
        r = http_post(base, "/query", {"q": "test", "project": "nonexistent-xyz"})
        if isinstance(r, dict) and "error" in r:
            ok("不存在项目返回错误提示")
        else:
            fail(f"不存在项目未报错: {r}")
    except urllib.error.HTTPError as e:
        ok(f"不存在项目返回 HTTP {e.code}")
    except Exception as e:
        fail(f"错误处理异常: {e}")


_BENCH_QUERY = "init"


def test_benchmark(base: str, project: str = "data-hunan-acc", n: int = 20):
    """性能基准：对同一项目连续查询 N 次，统计延迟分布。"""
    header(f"6. 性能基准（{project}, {n} 次）")
    info("首次查询（冷启动，含 DB 打开）...")
    t0 = time.time()
    http_post(base, "/query", {"q": _BENCH_QUERY, "project": project, "top": 6, "expand": "0"})
    info(f"  冷启动: {(time.time()-t0)*1000:.0f}ms")

    info(f"连续 {n} 次热查询（连接已缓存）...")
    times = []
    for _ in range(n):
        t0 = time.time()
        http_post(base, "/query", {"q": _BENCH_QUERY, "project": project, "top": 6, "expand": "0"})
        times.append((time.time() - t0) * 1000)

    times.sort()
    avg = sum(times) / len(times)
    p50 = times[len(times) // 2]
    p95 = times[int(len(times) * 0.95)]
    log.info(f"      {C['GREEN']}avg {avg:.1f}ms | p50 {p50:.1f}ms | p95 {p95:.1f}ms | "
          f"min {times[0]:.1f}ms | max {times[-1]:.1f}ms{C['RESET']}")

    # 对比：subprocess 脚本方式
    info("对比：subprocess 脚本方式（每次启动 Python）...")
    try:
        t0 = time.time()
        import subprocess
        # 用脚本所在目录推导路径，不要写死 /data/deploy_code/...
        script_dir = os.path.dirname(os.path.abspath(__file__))
        nl_query_path = os.path.join(script_dir, "..", "tools", "nl_query.py")
        subprocess.run(
            [sys.executable, nl_query_path,
             _BENCH_QUERY, "-p", project, "--no-graph"],
            capture_output=True, timeout=30,
        )
        script_ms = (time.time() - t0) * 1000
        speedup = script_ms / avg if avg > 0 else 0
        log.info(f"      subprocess 脚本: {script_ms:.0f}ms  "
              f"{C['MAGENTA']}→ 常驻服务快 {speedup:.0f} 倍{C['RESET']}")
    except Exception as e:
        info(f"      (subprocess 对比跳过: {e})")


def main():
    ap = argparse.ArgumentParser(description="测试自然语言查询常驻服务")
    ap.add_argument("--base", default=DEFAULT_BASE, help=f"服务地址（默认 {DEFAULT_BASE}）")
    ap.add_argument("--bench", action="store_true", help="只跑性能基准")
    ap.add_argument("--bench-project", default="data-hunan-acc", help="基准测试项目")
    ap.add_argument("--bench-n", type=int, default=20, help="基准测试次数")
    ap.add_argument("-q", "--query", help="单次查询（指定问题）")
    ap.add_argument("-p", "--project", default="data-hunan-bms", help="单次查询项目")
    args = ap.parse_args()

    log.info(f"{C['BOLD']}自然语言查询服务测试{C['RESET']}")
    log.info(f"  服务: {args.base}")

    # 连通性
    if not test_health(args.base):
        log.info(f"\n{C['RED']}服务未就绪。启动方式:{C['RESET']}")
        log.info(f"  python3 {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nl_query_server.py')}")
        return 1

    # 单次查询模式
    if args.query:
        header("单次查询")
        test_query(args.base, args.query, args.project, top=6)
        return 0

    # 基准模式
    if args.bench:
        test_benchmark(args.base, args.bench_project, args.bench_n)
        return 0

    # 全套测试
    projs = [p["name"] for p in test_projects(args.base)]
    test_query_suite(args.base, projs)
    test_formats(args.base)
    test_error_handling(args.base)
    test_benchmark(args.base, args.bench_project, args.bench_n)

    header("总结")
    ok("所有测试完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
