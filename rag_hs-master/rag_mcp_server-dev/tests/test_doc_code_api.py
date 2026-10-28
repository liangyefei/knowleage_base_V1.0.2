#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_doc_code_api.py — POST /doc_code 文档↔代码映射 API 测试（assertion 型）。

每个用例打印请求与响应的完整 JSON，便于人工核验输入/输出；exit 0=全过。

用法:
    python3 test_doc_code_api.py                       # 默认公网 http://139.9.104.64:9789
    DOCCODE_BASE=http://127.0.0.1:9788 python3 test_doc_code_api.py            # pod 内
    DOCCODE_BASE=http://haisi-dual-mcp.default.svc.cluster.local:9789 \
      DOCCODE_KB=kbf1dc0cdb2ea844f7be38deca06843f52 python3 test_doc_code_api.py  # 集群内
    DOCCODE_JSON_MAX=0 python3 test_doc_code_api.py   # 不截断响应 JSON（默认截 12000 字符）

接口文档见 API-DOCCODE.md。
"""
import json
import logging
import os
import sys

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("doc_code_test")
import urllib.error
import urllib.request

BASE = os.environ.get("DOCCODE_BASE", "http://139.9.104.64:9789").rstrip("/")
KB = os.environ.get("DOCCODE_KB", "kbf1dc0cdb2ea844f7be38deca06843f52")
JSON_MAX = int(os.environ.get("DOCCODE_JSON_MAX", "12000"))

_results = []
_K_DIRECTION = "direction"   # 扫描 G.TYP.03：重复字典 key 常量化
_K_SYMBOLS = "symbols"
_K_START_LINE = "start_line"


def api(path, body=None):
    """调用 API，打印请求与响应 JSON；返回 (http_status, 解析后的 dict)。"""
    if body is None:
        req = urllib.request.Request(BASE + path, method="GET")
        log.info(f"  请求: GET {path}")
    else:
        req = urllib.request.Request(
            BASE + path, data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json"})
        log.info(f"  请求: POST {path}  " + json.dumps(body, ensure_ascii=False))
    try:
        resp = urllib.request.urlopen(req, timeout=60)
        status, text = resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode("utf-8", "replace")
    try:
        pretty = json.dumps(json.loads(text), ensure_ascii=False, indent=2)
    except ValueError:
        pretty = text
    if JSON_MAX and len(pretty) > JSON_MAX:
        pretty = (pretty[:JSON_MAX]
                  + f"\n… [JSON 截断：共 {len(pretty)} 字符，DOCCODE_JSON_MAX=0 显示全量]")
    log.info("  响应:")
    for ln in pretty.splitlines():
        log.info("  | " + ln)
    try:
        return status, json.loads(text)
    except ValueError:
        return status, {}


def check(name, fn):
    log.info("─" * 72)
    log.info(name)
    try:
        fn()
        _results.append((name, True, ""))
        log.info(f"[PASS] {name}")
    except Exception as e:
        _results.append((name, False, str(e)[:200]))
        log.info(f"[FAIL] {name}: {e}")


def t01_health():
    s, d = api("/health")
    assert s == 200 and d.get("ok") is True, (s, d)


def t02_auto_code2doc():
    s, d = api("/doc_code", {"file": "osal_device.c", "project": KB, "limit": 5})
    assert s == 200, s
    assert d[_K_DIRECTION] == "code2doc", d[_K_DIRECTION]
    assert d["matched"] == "code_file", d["matched"]
    assert d["count"] > 0 and d["results"], "count=0"
    g = d["results"][0]
    assert g["doc"] and g["doc_name"], g
    assert isinstance(g[_K_SYMBOLS], list) and g[_K_SYMBOLS], g
    s0 = g[_K_SYMBOLS][0]
    assert s0["symbol"] and s0["file"], s0
    assert isinstance(s0["doc_ref"], dict) and "ref_name" in s0["doc_ref"], s0


def t03_with_source():
    s, d = api("/doc_code", {"file": "osal_device.c", "project": KB,
                             "with_source": 2, "limit": 5})
    assert s == 200, s
    srcs = [sy.get("source") for g in d["results"] for sy in g[_K_SYMBOLS]]
    assert any(srcs), "无任何 source"
    src = next(x for x in srcs if x)
    assert isinstance(src, str) and len(src) > 10, src[:50]
    assert not src.endswith("[truncated]") or len(src) <= 2600 + 20, "截断越界"


def t04_with_doc_fragment():
    s, d = api("/doc_code", {"file": "osal_device.c", "project": KB,
                             "with_doc": 2, "limit": 5})
    assert s == 200, s
    frags = [g.get("doc_fragment") for g in d["results"]]
    assert any(frags), "无任何 doc_fragment"
    f = next(x for x in frags if x)
    assert f["file"] and f["content"], f
    assert isinstance(f[_K_START_LINE], int) and f[_K_START_LINE] >= 1, f
    assert isinstance(f["end_line"], int) and f["end_line"] >= f[_K_START_LINE], f
    assert len(f["content"]) <= 2000 + 20, "文档块超限"


def t05_doc2code_exact():
    s, d = api("/doc_code", {"file": "SKILL.md", "project": KB,
                             _K_DIRECTION: "doc2code", "exact": True, "limit": 6})
    assert s == 200, s
    assert d[_K_DIRECTION] == "doc2code" and d["matched"] == "document", \
        (d[_K_DIRECTION], d["matched"])
    assert d["count"] > 0, "SKILL.md 无映射"
    for r in d["results"]:
        assert r["label"] in ("Function", "Method"), r["label"]
        assert r["symbol"] and r["file"], r
        assert isinstance(r["start_line"], int), r
        assert isinstance(r["doc_ref"].get("line", 0), int), r


def t06_path_fragment():
    s, d = api("/doc_code", {"file": "hi-aiot-build", "project": KB,
                             "limit": 10})
    assert s == 200, s
    assert d[_K_DIRECTION] == "doc2code", d[_K_DIRECTION]
    assert d["count"] > 0
    for r in d["results"]:
        assert "hi-aiot-build" in r["doc"], r["doc"]


def t07_explicit_direction_respected():
    s, d = api("/doc_code", {"file": "SKILL.md", "project": KB,
                             _K_DIRECTION: "code2doc"})
    assert s == 200, s
    assert d["matched"] != "document", "显式 code2doc 却判向 document"
    assert d[_K_DIRECTION] in ("code2doc", None), d[_K_DIRECTION]


def t08_empty_is_data_truth():
    s, d = api("/doc_code", {"file": "no_such_file_xyz.c", "project": KB})
    assert s == 200, s
    assert d["count"] == 0 and d["results"] == [], d
    assert d["matched"] is None and d[_K_DIRECTION] is None, d
    assert d["hint"], "空结果应有 hint"


def t09_404_unknown_project():
    s, d = api("/doc_code", {"file": "x.c", "project": "kb-no-such-xyz"})
    assert s == 404, s


def t10_400_bad_direction():
    s, d = api("/doc_code", {"file": "x.c", "project": KB, _K_DIRECTION: "bad"})
    assert s == 400, s


def t11_regression_query_projects():
    s, d = api("/query", {"q": "osal_dump_stack", "project": KB, "top": 3,
                          "expand": False})
    assert s == 200 and d["matches"], "/query 回归失败"
    s, p = api("/projects")
    names = [x["name"] for x in p.get("projects", [])]
    assert any("kbf1dc0" in n for n in names), "kbf1dc0 不在 /projects"


def main():
    log.info(f"目标: {BASE}  库: {KB}  (响应 JSON 截断: {JSON_MAX or '关'})\n")
    for name, fn in [
        ("T01 GET /health", t01_health),
        ("T02 auto 判向 → code2doc（osal_device.c）", t02_auto_code2doc),
        ("T03 with_source 源码段", t03_with_source),
        ("T04 with_doc 文档块（锚点窗口）", t04_with_doc_fragment),
        ("T05 doc2code + exact（SKILL.md）", t05_doc2code_exact),
        ("T06 路径片段（hi-aiot-build）", t06_path_fragment),
        ("T07 显式 direction 被尊重", t07_explicit_direction_respected),
        ("T08 空结果=数据真值（hint）", t08_empty_is_data_truth),
        ("T09 未知 project → 404", t09_404_unknown_project),
        ("T10 非法 direction → 400", t10_400_bad_direction),
        ("T11 回归 /query + /projects", t11_regression_query_projects),
    ]:
        check(name, fn)
    ok = sum(1 for _, p, _ in _results if p)
    log.info("─" * 72)
    log.info(f"结果: {ok}/{len(_results)} 通过")
    if ok != len(_results):
        for name, p, err in _results:
            if not p:
                log.info(f"  FAIL {name}: {err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
