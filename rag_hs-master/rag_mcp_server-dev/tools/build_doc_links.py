#!/usr/bin/env python3
"""
build_doc_links.py — 为已索引项目后补文档↔代码双向边

对指定项目的所有 .md 文件:
  1. 从 File 节点创建 Document 节点（如果不存在）
  2. 读取文档正文（source_files 表，LZ4 或原始文本）
  3. 提取代码符号引用
  4. 查找匹配的 Function/Method/Class 节点
  5. 创建 DOCUMENTS_CODE + DOCUMENTED_BY 边

用法:
    python3 build_doc_links.py data-deploy_code-Hi3516CV610
    python3 build_doc_links.py data-hunan-bms --dry-run
"""

import argparse
import logging
import os
import re
import sqlite3
import sys

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("build_doc_links")
_err_log = logging.getLogger("build_doc_links.err")
_err_log.propagate = False
_err_h = logging.StreamHandler(sys.stderr)
_err_h.setFormatter(logging.Formatter("%(message)s"))
_err_log.addHandler(_err_h)


CACHE_DIR = os.environ.get("CBM_CACHE_DIR",
                           os.path.expanduser("~/.cache/codebase-memory-mcp"))


def _regexp(pattern: str, value) -> int:
    if value is None: return 0
    try: return 1 if re.search(pattern, str(value)) else 0
    except re.error: return 0


CN_TO_EN = {
    "初始化": ["init", "initialize", "setup"], "初始": ["init"],
    "连接": ["connect", "conn"], "数据库": ["db", "database", "sql"],
    "配置": ["config", "cfg", "setting"], "设置": ["set", "config", "setup"],
    "读取": ["read", "get", "fetch"], "读": ["read", "get"],
    "写入": ["write", "set", "save"], "写": ["write", "save"],
    "保存": ["save", "store"], "加载": ["load", "fetch"],
    "解析": ["parse", "decode"], "发送": ["send", "dispatch", "emit"],
    "接收": ["recv", "receive"], "校验": ["check", "verify", "validate"],
    "检查": ["check", "verify"], "验证": ["verify", "validate", "auth"],
    "权限": ["privilege", "perm", "auth", "acl"],
    "账户": ["account", "acc", "user"],
    "日志": ["log", "trace", "debug"], "错误": ["error", "err"],
    "关闭": ["close", "shutdown", "stop"], "启动": ["start", "run", "main", "boot"],
    "消息": ["message", "msg"], "处理": ["handle", "process", "proc"],
    "回调": ["callback", "cb"], "线程": ["thread"], "锁": ["lock", "mutex"],
    "缓存": ["cache", "buffer"], "内存": ["mem", "alloc"],
    "释放": ["free", "release", "destroy"], "创建": ["create", "new", "make"],
    "删除": ["delete", "remove", "del"], "更新": ["update", "modify"],
    "查询": ["query", "search", "find", "select"],
    "监听": ["listen", "listener"], "通知": ["notify", "notice"],
    "同步": ["sync"], "心跳": ["heartbeat", "hb"],
    "超时": ["timeout"], "重试": ["retry"], "签名": ["sign", "signature"],
    "编码": ["encode"], "解码": ["decode"],
}


def extract_keywords(text: str) -> list[str]:
    en = []
    for cn, ens in CN_TO_EN.items():
        if cn in text: en.extend(ens)
    symbol_pat = re.compile(
        r"[A-Za-z_][A-Za-z0-9_]*(?:[A-Z][a-z]|[a-z][A-Z]|_[a-z0-9])[A-Za-z0-9_]*"
        r"|[A-Z][A-Z_]{2,}[A-Z0-9_]*"
    )
    syms = [m.group(0) for m in symbol_pat.finditer(text)]
    tokens = [w.lower() for w in re.findall(r"[A-Za-z][A-Za-z0-9_]*", text) if len(w) >= 2]
    out, seen = [], set()
    for t in tokens + en + syms:
        if t not in seen and re.fullmatch(r"[a-z][a-z0-9_]*", t.lower()):
            out.append(t); seen.add(t)
    return out


def get_root_path(conn, project: str) -> str:
    """从 projects 表获取仓库根路径。"""
    row = conn.execute("SELECT root_path FROM projects WHERE name=? LIMIT 1",
                       (project,)).fetchone()
    return row[0] if row else ""


def build(project: str, dry_run: bool):
    db_path = os.path.join(CACHE_DIR, f"{project}.db")
    if not os.path.exists(db_path):
        _err_log.error(f"ERROR: 项目 '{project}' 图谱不存在: {db_path}")
        return 1
    conn = sqlite3.connect(db_path)
    conn.create_function("REGEXP", 2, _regexp)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("BEGIN")

    # 1. 找所有 .md 文件节点
    md_files = conn.execute(
        "SELECT id, name, qualified_name, file_path, properties "
        "FROM nodes WHERE label='File' AND file_path LIKE '%.md'"
    ).fetchall()
    log.info(f"Found {len(md_files)} .md files")

    # 2. 预查所有 Function/Method/Class 的 name → id
    code_nodes = conn.execute(
        "SELECT id, name FROM nodes WHERE label IN ('Function','Method','Class')"
    ).fetchall()
    name_to_id: dict[str, list[int]] = {}
    for cid, cname in code_nodes:
        name_to_id.setdefault(cname, []).append(cid)
    log.info(f"  Pre-indexed {len(code_nodes)} code symbols ({len(name_to_id)} unique names)")

    created_docs = 0
    created_edges = 0

    for _fid, fname, _fqn, fpath, _fprops in md_files:
        # 2a. 创建/查找 Document 节点
        doc_qn = f"__doc__{fpath}"
        existing = conn.execute(
            "SELECT id FROM nodes WHERE qualified_name=?", (doc_qn,)).fetchone()
        if existing:
            doc_id = existing[0]
        else:
            cur = conn.execute(
                "INSERT INTO nodes(project, label, name, qualified_name, file_path, start_line, end_line, properties) "
                "VALUES(?,'Document',?,?,?,0,0,'{}')",
                (project, fname, doc_qn, fpath))
            doc_id = cur.lastrowid
            created_docs += 1

        # 2b. 读取文档正文（优先 source_files，fallback 直接从文件系统读）
        content = None
        src_row = conn.execute(
            "SELECT content, size FROM source_files WHERE project=? AND rel_path=?",
            (project, fpath)).fetchone()
        if src_row:
            try:
                content = bytes(src_row[0]).decode("utf-8", errors="replace")
            except Exception as e:
                log.debug("source_files decode 失败 %s: %s", fpath, e)

        # fallback: 从文件系统直接读
        if not content:
            root = get_root_path(conn, project)
            full_path = os.path.join(root, fpath)
            if os.path.exists(full_path):
                try:
                    with open(full_path, "r", encoding="utf-8", errors="replace") as f:
                        content = f.read()
                except Exception as e:
                    log.debug("读文件失败 %s: %s", full_path, e)

        if not content:
            log.info(f"  SKIP {fpath}: no source content")
            continue

        # 2c. 提取关键词
        keywords = extract_keywords(content)
        if not keywords:
            continue

        # 2d. 查找匹配的代码节点
        seen_ids = set()
        for kw in set(keywords):
            matches = name_to_id.get(kw, [])
            # 也尝试大小写变体
            if not matches:
                matches = name_to_id.get(kw.lower(), [])
            if not matches:
                matches = name_to_id.get(kw.capitalize(), [])
            for mid in matches:
                if mid not in seen_ids:
                    seen_ids.add(mid)
                    if not dry_run:
                        try:
                            conn.execute(
                                "INSERT OR IGNORE INTO edges(project, source_id, target_id, type, properties) "
                                "VALUES(?,?,?,'DOCUMENTS_CODE','{}')",
                                (project, doc_id, mid))
                            conn.execute(
                                "INSERT OR IGNORE INTO edges(project, source_id, target_id, type, properties) "
                                "VALUES(?,?,?,'DOCUMENTED_BY','{}')",
                                (project, mid, doc_id))
                            created_edges += 2
                        except sqlite3.Error:
                            pass

        if created_edges % 500 == 0 and created_edges > 0:
            log.info(f"  ... {created_edges} edges so far")

    if dry_run:
        log.info(f"\n  [DRY-RUN] Would create {created_docs} Document nodes, {created_edges} edges")
        conn.execute("ROLLBACK")
    else:
        conn.execute("COMMIT")
        log.info(f"\n  Created {created_docs} Document nodes, {created_edges} edges (DOCUMENTS_CODE + DOCUMENTED_BY)")

    conn.close()
    return 0


def main():
    ap = argparse.ArgumentParser(description="后补文档↔代码双向边")
    ap.add_argument("project", nargs="?", help="项目名")
    ap.add_argument("--dry-run", action="store_true", help="不写入，只预览")
    ap.add_argument("--list", action="store_true", help="列出项目")
    args = ap.parse_args()

    if args.list:
        for p in sorted(os.listdir(CACHE_DIR)):
            if p.endswith(".db") and not p.startswith("_"):
                log.info(f"  {p[:-3]}")
        return 0

    if not args.project:
        ap.print_help()
        return 1

    return build(args.project, args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
