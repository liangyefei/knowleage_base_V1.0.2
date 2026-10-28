#!/usr/bin/env python3
"""
test_doc_code_bidirect.py — 验证文档↔代码双向检索

用法:
    python3 test_doc_code_bidirect.py                                    # 测试 Hi3516CV610
    python3 test_doc_code_bidirect.py --project data-hunan-bms          # 指定项目
    python3 test_doc_code_bidirect.py --list                            # 列出项目
"""

import argparse
import logging
import os
import sqlite3
import sys

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("bidirect_test")

CACHE_DIR = os.environ.get("CBM_CACHE_DIR",
                           os.path.expanduser("~/.cache/codebase-memory-mcp"))

G = "\033[32m" if sys.stdout.isatty() else ""
R = "\033[31m" if sys.stdout.isatty() else ""
Y = "\033[33m" if sys.stdout.isatty() else ""
C = "\033[36m" if sys.stdout.isatty() else ""
M = "\033[35m" if sys.stdout.isatty() else ""
B = "\033[1m" if sys.stdout.isatty() else ""
Z = "\033[0m" if sys.stdout.isatty() else ""

PASS, FAIL = 0, 0

# ── 源码读取 ──────────────────────────────────────────────────────

def get_root(conn, project: str) -> str:
    row = query(conn, "SELECT root_path FROM projects WHERE name=? LIMIT 1", (project,))
    return row[0][0] if row else ""

def read_source(root: str, file_path: str, start_line: int, end_line: int) -> str:
    """读文件的指定行范围，返回带行号的源码。"""
    full = os.path.join(root, file_path)
    if not os.path.exists(full):
        return "(文件不存在)"
    try:
        with open(full, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        if start_line > 0:
            lo = max(1, start_line - 2)                    # 上下文：往前 2 行
            hi = min(len(lines), max(start_line, end_line) + 3)  # 往后 3 行
        else:
            lo, hi = 1, min(len(lines), 35)
        out = []
        for i in range(lo - 1, hi):
            marker = f"{M}▸{Z}" if i + 1 >= max(1, start_line) and i + 1 <= max(start_line, end_line) else " "
            out.append(f"    {marker} {C}{i+1:4d}{Z}  {lines[i].rstrip()}")
        return "\n".join(out)
    except Exception as e:
        return f"(读取失败: {e})"


def ok(msg):   global PASS; PASS += 1; log.info(f"  {G}✓{Z} {msg}")
def bad(msg):  global FAIL; FAIL += 1; log.info(f"  {R}✗{Z} {msg}")
def hdr(msg):  log.info(f"\n{B}{C}═══ {msg} ═══{Z}")


def open_db(project: str) -> sqlite3.Connection:
    path = os.path.join(CACHE_DIR, f"{project}.db")
    if not os.path.exists(path):
        raise FileNotFoundError(f"项目 '{project}' 不存在: {path}")
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def query(conn, sql, params=()):
    return conn.execute(sql, params).fetchall()


# ── 测试 1: 文档→代码 ──────────────────────────────────────────────


def test_doc_to_code(conn, doc_filter=None, show_code=False, root=""):
    hdr("1. 文档→代码：文档引用了哪些函数")

    if doc_filter:
        docs = query(conn, """
            SELECT DISTINCT d.id, d.name, d.file_path
            FROM nodes d JOIN edges e ON e.source_id = d.id
            WHERE d.label = 'Document' AND e.type = 'DOCUMENTS_CODE'
              AND (LOWER(d.name) LIKE ? OR LOWER(d.file_path) LIKE ?)
            LIMIT 50
        """, (f"%{doc_filter.lower()}%", f"%{doc_filter.lower()}%"))
    else:
        docs = query(conn, """
            SELECT DISTINCT d.id, d.name, d.file_path
            FROM nodes d JOIN edges e ON e.source_id = d.id
            WHERE d.label = 'Document' AND e.type = 'DOCUMENTS_CODE'
            LIMIT 20
        """)
    assert docs, "没有找到任何文档-代码链接"
    ok(f"共 {len(docs)} 个文档有代码链接")

    for doc_id, doc_name, doc_path in docs:
        if doc_filter and doc_filter.lower() not in (doc_name + doc_path).lower():
            continue
        funcs = query(conn, """
            SELECT f.name, f.label, f.file_path, f.start_line, f.end_line
            FROM edges e JOIN nodes f ON f.id = e.target_id
            WHERE e.source_id = ? AND e.type = 'DOCUMENTS_CODE'
            LIMIT 15
        """, (doc_id,))
        if not funcs:
            continue
        log.info(f"\n  {B}📄 {doc_name}{Z}  {C}{doc_path}{Z}")
        for fname, flabel, fpath, sline, eline in funcs:
            log.info(f"    → [{G}{flabel}{Z}] {B}{fname}{Z}  {C}{fpath}:{sline}{Z}")
            if show_code and root and fpath:
                src = read_source(root, fpath, sline, eline)
                log.info(src)
        ok(f"  '{doc_name}' → {len(funcs)} 个函数/方法")
    return True


# ── 测试 2: 代码→文档 ──────────────────────────────────────────────


def test_code_to_doc(conn, func_filter=None, show_code=False, root=""):
    hdr("2. 代码→文档：函数被哪些文档描述")

    # 如果给了 func_filter，在 SQL 里直接过滤，避免 LIMIT 截断把目标函数切掉
    if func_filter:
        funcs = query(conn, """
            SELECT DISTINCT f.id, f.name, f.label, f.file_path, f.start_line, f.end_line
            FROM nodes f JOIN edges e ON e.source_id = f.id
            WHERE f.label IN ('Function','Method') AND e.type = 'DOCUMENTED_BY'
              AND LOWER(f.name) LIKE ?
            LIMIT 50
        """, (f"%{func_filter.lower()}%",))
    else:
        funcs = query(conn, """
            SELECT DISTINCT f.id, f.name, f.label, f.file_path, f.start_line, f.end_line
            FROM nodes f JOIN edges e ON e.source_id = f.id
            WHERE f.label IN ('Function','Method') AND e.type = 'DOCUMENTED_BY'
            LIMIT 20
        """)
    assert funcs, "没有函数被文档引用"
    ok(f"共 {len(funcs)} 个函数被文档描述（filter=%r）" % func_filter)

    for fid, fname, flabel, fpath, sline, eline in funcs:
        if func_filter and func_filter.lower() not in fname.lower():
            continue
        docs = query(conn, """
            SELECT d.name, d.file_path
            FROM edges e JOIN nodes d ON d.id = e.target_id
            WHERE e.source_id = ? AND e.type = 'DOCUMENTED_BY'
            LIMIT 10
        """, (fid,))
        if not docs:
            continue
        log.info(f"\n  {B}{fname}{Z} [{G}{flabel}{Z}]  {C}{fpath}:{sline}{Z}")
        for dname, dpath in docs:
            log.info(f"    ← {C}📄 {dname}{Z}  ({dpath})")
        if show_code and root and fpath:
            src = read_source(root, fpath, sline, eline)
            log.info(src)
        ok(f"  '{fname}' 被 {len(docs)} 个文档描述")
    return True


# ── 测试 3: 往返一致性 ──────────────────────────────────────────────


def test_roundtrip(conn):
    hdr("3. 往返一致性：DOCUMENTS_CODE ↔ DOCUMENTED_BY 对称")

    # DOCUMENTS_CODE 边数应等于 DOCUMENTED_BY 边数（一一对称）
    fwd = query(conn, "SELECT count(*) FROM edges WHERE type='DOCUMENTS_CODE'")[0][0]
    rev = query(conn, "SELECT count(*) FROM edges WHERE type='DOCUMENTED_BY'")[0][0]
    if fwd == rev:
        ok(f"DOCUMENTS_CODE ({fwd}) = DOCUMENTED_BY ({rev}) — 往返对称")
    else:
        bad(f"不对称: DOCUMENTS_CODE={fwd}, DOCUMENTED_BY={rev}")

    # 验证每对边都有对应反向边
    orphans = query(conn, """
        SELECT e.id FROM edges e
        WHERE e.type = 'DOCUMENTS_CODE'
        AND NOT EXISTS (
            SELECT 1 FROM edges r
            WHERE r.type = 'DOCUMENTED_BY'
            AND r.source_id = e.target_id AND r.target_id = e.source_id
        )
        LIMIT 3
    """)
    if not orphans:
        ok("所有 DOCUMENTS_CODE 边都有对应的 DOCUMENTED_BY 反向边")
    else:
        bad(f"存在 {len(orphans)} 条没有反向边的 DOCUMENTS_CODE 边")
    return True


# ── 测试 4: 图谱统计 ────────────────────────────────────────────────


def test_stats(conn):
    hdr("4. 文档-代码图谱统计")
    docs = query(conn, "SELECT count(*) FROM nodes WHERE label='Document'")[0][0]
    funcs = query(conn, "SELECT count(*) FROM nodes WHERE label IN ('Function','Method')")[0][0]
    edges_fwd = query(conn, "SELECT count(*) FROM edges WHERE type='DOCUMENTS_CODE'")[0][0]
    unique_funcs = query(conn, """
        SELECT count(DISTINCT e.target_id) FROM edges e
        WHERE e.type='DOCUMENTS_CODE'
    """)[0][0]
    ok(f"Document 节点: {docs}")
    ok(f"Function/Method 节点: {funcs}")
    ok(f"DOCUMENTS_CODE 边: {edges_fwd}")
    ok(f"被文档引用的独特函数: {unique_funcs}")

    # 找出链接最多的文档
    top_docs = query(conn, """
        SELECT d.name, d.file_path, count(*) as cnt
        FROM edges e JOIN nodes d ON d.id = e.source_id
        WHERE e.type='DOCUMENTS_CODE'
        GROUP BY e.source_id ORDER BY cnt DESC LIMIT 3
    """)
    log.info(f"\n  {B}链接最多的文档:{Z}")
    for dname, dpath, cnt in top_docs:
        log.info(f"    {dname} ({dpath}) → {cnt} 个代码符号")

    # 被引用最多的函数
    top_funcs = query(conn, """
        SELECT f.name, f.file_path, count(*) as cnt
        FROM edges e JOIN nodes f ON f.id = e.target_id
        WHERE e.type='DOCUMENTS_CODE'
        GROUP BY e.target_id ORDER BY cnt DESC LIMIT 3
    """)
    log.info(f"\n  {B}被引用最多的函数:{Z}")
    for fname, fpath, cnt in top_funcs:
        log.info(f"    {fname} ({fpath}) ← {cnt} 个文档")
    return True


# ── 入口 ────────────────────────────────────────────────────────────


def main():
    ap = argparse.ArgumentParser(description="验证文档↔代码双向检索")
    ap.add_argument("--project", "-p",
                    default=os.environ.get("CBM_PROJECT", "projects-Hi3516CV610"),
                    help="项目名（默认读 $CBM_PROJECT，再退回 projects-Hi3516CV610）")
    ap.add_argument("--list", action="store_true", help="列出所有包含文档链接的项目")
    ap.add_argument("--doc", default=None, help="筛选特定文档名（文档→代码模式）")
    ap.add_argument("--func", default=None, help="筛选特定函数名（代码→文档模式）")
    ap.add_argument("--code", action="store_true", help="展示匹配函数的源代码片段")
    ap.add_argument("--root", default=None, help="项目根路径（读取源码用，默认从 projects 表推导）")
    args = ap.parse_args()

    if args.list:
        for p in sorted(os.listdir(CACHE_DIR)):
            if p.endswith(".db") and not p.startswith("_"):
                try:
                    conn = open_db(p[:-3])
                    cnt = query(conn, "SELECT count(*) FROM edges WHERE type='DOCUMENTS_CODE'")[0][0]
                    if cnt > 0:
                        log.info(f"  {p[:-3]:<45} {cnt} doc-code links")
                    conn.close()
                except Exception as e:
                    log.debug("skip %s: %s", p, e)
        return 0

    log.info(f"{B}文档↔代码双向检索验证{Z}")
    log.info(f"  项目: {args.project}")
    log.info(f"  缓存: {CACHE_DIR}")

    try:
        conn = open_db(args.project)
    except FileNotFoundError as e:
        log.info(f"{R}{e}{Z}")
        return 2
    root = args.root or get_root(conn, args.project)
    if args.code and root:
        log.info(f"  项目根路径: {root}")

    test_doc_to_code(conn, args.doc, args.code, root)
    test_code_to_doc(conn, args.func, args.code, root)
    test_roundtrip(conn)
    test_stats(conn)

    hdr("总结")
    total = PASS + FAIL
    log.info(f"  通过: {G}{PASS}{Z} / {total}")
    if FAIL:
        log.info(f"  失败: {R}{FAIL}{Z} / {total}")
    log.info("")
    if FAIL == 0:
        log.info(f"  {G}{B}✓ 双向检索全部验证通过{Z}")
        return 0
    else:
        log.info(f"  {R}{B}✗ {FAIL} 项失败{Z}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
