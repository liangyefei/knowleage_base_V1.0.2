"""nlq_store — SQLite 图谱存储：KBStore、项目列表/解析、文件版本映射。"""
import json
import os
import re
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from nlq_constants import (
    log, CACHE_DIR, _K_CALLERS, _K_CALLEES, _K_DATA, _K_ENC_IGNORE, _K_FILE,
    _K_FILE_PATH, _K_ID, _K_JOB_ID, _K_LABEL, _K_LINE, _K_NAME, _K_REF_NAME,
    _K_SOURCE, _K_STATUS, _K_TARGET, _K_TYPE, _ST_RUNNING, _MATCH_DOCUMENT,
)

# ── SQLite 图谱存储 ──────────────────────────────────────────────────


def _regexp(pattern: str, value) -> int:
    if value is None:
        return 0
    try:
        return 1 if re.search(pattern, str(value)) else 0
    except re.error:
        return 0


@dataclass
class DocCodeEdgeQuery:
    """doc_code_edges 的查询条件封装（扫描 G.FNM.03：参数过多）。"""
    doc_where: str | None = None
    doc_params: list | None = None
    code_where: str | None = None
    code_params: list | None = None
    symbols_only: bool = False
    limit: int = 200


def _bad_range(fpath, sline, eline) -> bool:
    """扫描 G.CTL.03：行范围合法性单点判断（4 条件收敛）。"""
    return not fpath or not sline or not eline or eline < sline


class KBStore:
    """直接读 CBM 的 SQLite 知识图谱（只读模式，毫秒级查询）。

    CBM 重索引会重建/替换 .db 文件；按文件 (mtime_ns, inode) 指纹检测变化
    并自动重开连接，避免重索引后继续读旧连接返回过期数据（前端"重索引后
    仍查不到"的根因）。
    """

    def __init__(self, project: str):
        self.project = project
        self.db_path = os.path.join(CACHE_DIR, f"{project}.db")
        self._conn = None
        self._fingerprint = None
        self._lock = threading.Lock()
        self._open()
        self.root_path = ""
        try:
            row = self._conn.execute(
                "SELECT root_path FROM projects LIMIT 1").fetchone()
            if row and row[0]:
                self.root_path = row[0]
        except sqlite3.Error:
            pass

    @staticmethod
    def _fts_match(keywords: list[str]) -> str:
        """关键词 → FTS5 MATCH 表达式（OR 连接，引号防注入）。

        FTS 内容列在建库时经 cbm_camel_split 切分（snake/驼峰/字母数字
        边界，如 Hi3516CV610→hi 3516 cv 610），查询词必须同样切分才能
        命中。
        """
        toks, seen = [], set()
        for k in keywords:
            for t in re.findall(r"[A-Za-z0-9_]+", k):
                parts = re.split(r"[\s_]+", re.sub(
                    r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"
                    r"|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])",
                    r" ", t))
                for p in parts:
                    p = p.lower()
                    if len(p) >= 2 and p not in seen:
                        seen.add(p)
                        toks.append('"' + p + '"')
        return " OR ".join(toks[:12])

    def close(self):
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    def search(self, keywords: list[str], limit: int = 8) -> list[dict]:
        if not keywords:
            return []
        pats = [re.escape(k) for k in keywords if k][:12]
        if not pats:
            return []

        def _hits(rows):
            return [
                {"id": r[0], "label": r[1], "name": r[2],
                 "qualified_name": r[3] or "", "file_path": r[4] or "",
                 "start_line": r[5] or 0,
                 "in_degree": r[6] or 0, "out_degree": r[7] or 0}
                for r in rows
            ]

        with self._lock:
            self._open()
            # FTS5 快路径：CBM 建库时已维护 nodes_fts（BM25），毫秒级。
            # REGEXP 是 Python 回调逐行全表扫描，EFS 上秒级——仅作 fallback。
            match = self._fts_match(keywords)
            if match:
                try:
                    rows = self._conn.execute(
                        "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                        "n.start_line, "
                        "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id AND e.type='CALLS') AS in_deg, "
                        "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id AND e.type='CALLS') AS out_deg "
                        "FROM nodes_fts JOIN nodes n ON n.id = nodes_fts.rowid "
                        "WHERE nodes_fts MATCH ? "
                        "AND n.label IN ('Function','Method','Class','Variable') "
                        "ORDER BY bm25(nodes_fts) LIMIT ?",
                        (match, limit)).fetchall()
                    if rows:
                        return _hits(rows)
                except sqlite3.Error:
                    pass
            regex = "|".join(pats)
            sql = (
                "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                "n.start_line, "
                "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id AND e.type='CALLS') AS in_deg, "
                "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id AND e.type='CALLS') AS out_deg "
                "FROM nodes n "
                "WHERE n.label IN ('Function','Method','Class','Variable') "
                "AND n.name LIKE ? "
                f"AND n.name REGEXP '.*({regex}).*' "
                "ORDER BY (in_deg + out_deg) DESC LIMIT ?"
            )
            return _hits(self._conn.execute(
                sql, (f"%{keywords[0]}%", limit)).fetchall())

    def snippet(self, name: str, max_chars: int = 2600) -> dict | None:
        """取符号完整源码（nodes.start_line..end_line 读源文件）。

        供 /query 的 include_source 用：top 命中直接拼源码，RAG 无需再调
        codegraph 取源码（省掉 codegraph daemon 的查询负载与内存压力）。
        任何异常返回 None（不中断查询主链路）。
        """
        with self._lock:
            self._open()
            row = self._conn.execute(
                "SELECT label, name, qualified_name, file_path, start_line, "
                "end_line FROM nodes "
                "WHERE name=? AND label IN ('Function','Method','Class') "
                "LIMIT 1", (name,)).fetchone()
        if not row:
            return None
        label, nm, qn, fpath, sline, eline = row
        if _bad_range(fpath, sline, eline):
            return None
        if not self.root_path:
            return None
        path = os.path.join(self.root_path, fpath)
        lines = _read_lines_cached(path)
        if lines is None:
            return None
        if sline < 1 or sline > len(lines):
            return None
        src = "".join(lines[sline - 1:eline])
        if len(src) > max_chars:
            src = src[:max_chars] + "\n... [truncated]"
        v = _lookup_files_version(_load_files_version(self.project), fpath)
        out = {"label": label, "name": nm, "qualified_name": qn,
               "file_path": fpath, "start_line": sline, "end_line": eline,
               "source": src}
        if v is not None:
            out["file_version"] = v
        return out

    def neighbors(self, name: str, limit: int = 10) -> dict:
        with self._lock:
            self._open()
            nid = self._conn.execute(
                "SELECT n.id FROM nodes n WHERE n.name=? "
                "ORDER BY (SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) DESC LIMIT 1",
                (name,),
            ).fetchone()
            if not nid:
                return {_K_CALLERS: [], _K_CALLEES: []}
            nid = nid[0]
            callers = self._nb_rows(
                "SELECT n.name,n.label,n.file_path FROM edges e JOIN nodes n ON n.id=e.source_id "
                "WHERE e.target_id=? AND e.type='CALLS' LIMIT ?", (nid, limit))
            callees = self._nb_rows(
                "SELECT n.name,n.label,n.file_path FROM edges e JOIN nodes n ON n.id=e.target_id "
                "WHERE e.source_id=? AND e.type='CALLS' LIMIT ?", (nid, limit))
            return {_K_CALLERS: callers, _K_CALLEES: callees}

    def counts(self):
        with self._lock:
            self._open()
            n = self._conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
            e = self._conn.execute("SELECT COUNT(*) FROM edges").fetchone()[0]
            return n, e


    def snippets_batch(self, names: list, max_chars: int = 2600) -> dict:
        """include_source 批量版：一条定位 SQL + 行缓存读文件，返回 {name: snippet}。

        单符号版 snippet() 每符号一条定位 SQL，NFS 上每条 ~0.1s 固定延迟，
        top-5 即 0.5s——批量定位合成 1 条。同名多节点取 file_path 非空优先。
        """
        out: dict = {}
        uniq = [n for n in dict.fromkeys(names) if n]
        if not uniq:
            return out
        ph = ",".join("?" * len(uniq))
        with self._lock:
            self._open()
            rows = self._conn.execute(
                "SELECT label, name, qualified_name, file_path, start_line, "
                "end_line FROM nodes "
                f"WHERE name IN ({ph}) AND label IN ('Function','Method','Class') "
                "ORDER BY (file_path != '') DESC", uniq).fetchall()
        by_name: dict = {}
        for r in rows:
            by_name.setdefault(r[1], r)
        fv_map = _load_files_version(self.project)
        for nm in uniq:
            row = by_name.get(nm)
            if not row:
                continue
            label, _, qn, fpath, sline, eline = row
            if _bad_range(fpath, sline, eline):
                continue
            if not self.root_path:
                continue
            lines = _read_lines_cached(os.path.join(self.root_path, fpath))
            if lines is None or sline < 1 or sline > len(lines):
                continue
            src = "".join(lines[sline - 1:eline])
            if len(src) > max_chars:
                src = src[:max_chars] + "\n... [truncated]"
            o = {"label": label, "name": nm, "qualified_name": qn,
                 "file_path": fpath, "start_line": sline, "end_line": eline,
                 "source": src}
            v = _lookup_files_version(fv_map, fpath)
            if v is not None:
                o["file_version"] = v
            out[nm] = o
        return out

    def neighbors_batch(self, names: list, per_side: int = 10) -> dict:
        """expand 批量版：定位/调用边/邻居信息共 3 条 SQL，返回 {name: {callers,callees}}。

        单符号版 neighbors() 每符号 3 条 SQL，NFS 上 8 符号 = 24 条 ~2.4s。
        定位语义保持"同名取入度最大的节点"（ORDER BY 入度降序后首见即取）。
        """
        out = {n: {_K_CALLERS: [], _K_CALLEES: []} for n in names}
        uniq = [n for n in dict.fromkeys(names) if n]
        if not uniq:
            return out
        ph = ",".join("?" * len(uniq))
        with self._lock:
            self._open()
            rows = self._conn.execute(
                "SELECT n.name AS nm, n.id AS nid FROM nodes n "
                f"WHERE n.name IN ({ph}) "
                "ORDER BY (SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) DESC",
                uniq).fetchall()
        if not rows:
            return out
        best: dict = {}
        for nm, nid in rows:
            best.setdefault(nm, nid)
        ids = list(best.values())
        ph2 = ",".join("?" * len(ids))
        with self._lock:
            self._open()
            edges = self._conn.execute(
                "SELECT src.name AS caller, src.id AS sid, "
                "tgt.name AS callee, tgt.id AS tid FROM edges e "
                "JOIN nodes src ON src.id=e.source_id "
                "JOIN nodes tgt ON tgt.id=e.target_id "
                f"WHERE e.type='CALLS' AND (e.target_id IN ({ph2}) OR e.source_id IN ({ph2})) "
                "LIMIT 1000", ids + ids).fetchall()
        id_best = {nid: nm for nm, nid in best.items()}
        nb_names = set()
        for caller, sid, callee, tid in edges:
            if tid in id_best:
                b = out[id_best[tid]][_K_CALLERS]
                if len(b) < per_side:
                    b.append({_K_NAME: caller, _K_LABEL: "", _K_FILE: ""})
                    nb_names.add(caller)
            if sid in id_best:
                b = out[id_best[sid]][_K_CALLEES]
                if len(b) < per_side:
                    b.append({_K_NAME: callee, _K_LABEL: "", _K_FILE: ""})
                    nb_names.add(callee)
        if nb_names:  # 邻居节点 label/file 补齐（输出与单符号版对齐）
            nb_list = list(nb_names)
            ph3 = ",".join("?" * len(nb_list))
            with self._lock:
                self._open()
                meta = self._conn.execute(
                    f"SELECT name, label, file_path FROM nodes WHERE name IN ({ph3})",
                    nb_list).fetchall()
            meta_map = {m[0]: (m[1] or "", m[2] or "") for m in meta}
            for entry in out.values():
                for c in entry[_K_CALLERS] + entry[_K_CALLEES]:
                    lb, fp = meta_map.get(c[_K_NAME], ("", ""))
                    c[_K_LABEL] = lb
                    c[_K_FILE] = fp
        return out

    def doc_code_edges(self, q: "DocCodeEdgeQuery") -> list[dict]:
        """查 Document→DOCUMENTS_CODE→代码 映射边（供 /doc_code）。

        单条参数化 SQL：边属性 properties 是原生 JSON 列，锚点
        ref_name/line 直接解析，不走 MCP 文本表。doc_where/code_where
        为可选的参数化条件片段（不加 AND 前缀）。
        """
        sql = ("SELECT d.name, d.file_path, f.name, f.label, "
               "f.file_path, f.start_line, f.end_line, e.properties "
               "FROM nodes d JOIN edges e ON e.source_id = d.id "
               "JOIN nodes f ON f.id = e.target_id "
               "WHERE d.label='Document' AND e.type='DOCUMENTS_CODE'")
        params: list = []
        if q.symbols_only:
            sql += " AND (f.label='Function' OR f.label='Method')"
        if q.doc_where:
            sql += f" AND ({q.doc_where})"
            params += q.doc_params
        if q.code_where:
            sql += f" AND ({q.code_where})"
            params += q.code_params
        sql += " LIMIT ?"
        params.append(q.limit)
        with self._lock:
            self._open()
            rows = self._conn.execute(sql, params).fetchall()
        out = []
        for dname, dpath, fname, flabel, fpath, sline, eline, eprops in rows:
            ref = {_K_REF_NAME: "", _K_LINE: None}
            try:
                p = json.loads(eprops) if eprops else {}
                if isinstance(p, dict):
                    ref = {_K_REF_NAME: str(p.get(_K_REF_NAME) or ""),
                           _K_LINE: p.get(_K_LINE) if isinstance(p.get(_K_LINE), int) else None}
            except (ValueError, TypeError):
                pass
            out.append({"doc": dpath or "", "doc_name": dname or "",
                        "symbol": fname or "", "label": flabel or "",
                        "file": fpath or "", "start_line": sline,
                        "end_line": eline, "doc_ref": ref})
        return out

    def mapped_files(self, side: str) -> list[dict]:
        """已建立 DOCUMENTS_CODE 映射的文件清单（供 /doc_code/files）。

        side=code：映射边的 target（File 级与符号级合并）按 file_path
        去重；side=doc：映射边的 source（Document 节点）按文档去重。
        每条含 refs=映射边数，按路径升序。
        """
        if side == "code":
            sql = ("SELECT f.file_path, COUNT(e.id) FROM edges e "
                   "JOIN nodes f ON f.id = e.target_id "
                   "WHERE e.type='DOCUMENTS_CODE' AND f.file_path != '' "
                   "AND f.file_path NOT LIKE '<%' "
                   "GROUP BY f.file_path ORDER BY f.file_path")
        else:
            sql = ("SELECT d.name, d.file_path, COUNT(e.id) FROM edges e "
                   "JOIN nodes d ON d.id = e.source_id "
                   "WHERE e.type='DOCUMENTS_CODE' "
                   "GROUP BY d.id ORDER BY d.file_path")
        with self._lock:
            self._open()
            rows = self._conn.execute(sql).fetchall()
        if side == "code":
            return [{"file": r[0], "name": os.path.basename(r[0]), "refs": r[1]}
                    for r in rows]
        return [{"file": r[1] or "", "name": r[0] or "", "refs": r[2]}
                for r in rows]

    def search_entities_boundary(self, term: str, limit: int = 10) -> list[dict]:
        """左边界词段匹配（联想首层）：term 须出现在词段开头（左侧为串首或
        非字母数字），大小写不敏感，name 与 file_path 双列——'LED' 命中
        led/led_init/hi_led/sample_led.c，不命中 enabled/knowledge（词内嵌
        子串）；前缀输入 'led_i' 同样可命中 led_init。"""
        if not term:
            return []
        rx = f"(?i)(^|[^0-9A-Za-z]){re.escape(term)}"
        with self._lock:
            self._open()
            # LIKE 预筛（C 实现不占 GIL）先裁行，REGEXP 仅对候选行跑——
            # 否则 8 万行逐行 Python 正则回调会把事件循环饿死
            rows = self._conn.execute(
                "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                "n.start_line, "
                "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) AS in_deg, "
                "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id) AS out_deg "
                "FROM nodes n WHERE (n.name LIKE ? OR n.file_path LIKE ?) "
                "AND (n.name REGEXP ? OR n.file_path REGEXP ?) "
                "ORDER BY (in_deg + out_deg) DESC LIMIT ?",
                (f"%{term}%", f"%{term}%", rx, rx, limit)).fetchall()
        return [{"id": r[0], "label": r[1] or "", "name": r[2] or "",
                 "qualified_name": r[3] or "", "file_path": r[4] or "",
                 "start_line": r[5] or 0,
                 "in_degree": r[6] or 0, "out_degree": r[7] or 0}
                for r in rows]

    def search_entities(self, keywords: list[str], limit: int = 8) -> list[dict]:
        """实体检索（/graph_search）：与 search() 同款 FTS+REGEXP 双路径，
        但不限制 label——Document/File/Module 等全类型节点均可命中。"""
        if not keywords:
            return []
        pats = [re.escape(k) for k in keywords if k][:12]
        if not pats:
            return []

        def _hits(rows):
            return [
                {"id": r[0], "label": r[1], "name": r[2],
                 "qualified_name": r[3] or "", "file_path": r[4] or "",
                 "start_line": r[5] or 0,
                 "in_degree": r[6] or 0, "out_degree": r[7] or 0}
                for r in rows
            ]

        with self._lock:
            self._open()
            fts_rows = []
            match = self._fts_match(keywords)
            if match:
                try:
                    fts_rows = self._conn.execute(
                        "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                        "n.start_line, "
                        "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) AS in_deg, "
                        "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id) AS out_deg "
                        "FROM nodes_fts JOIN nodes n ON n.id = nodes_fts.rowid "
                        "WHERE nodes_fts MATCH ? "
                        "ORDER BY bm25(nodes_fts) LIMIT ?",
                        (match, limit)).fetchall()
                except sqlite3.Error:
                    pass
            if len(fts_rows) >= limit:
                return _hits(fts_rows)
            # FTS 零命中/稀疏命中时补跑子串兜底并合并：FTS 按词段匹配，
            # 「610」这类内嵌在字母数字里的子串（Hi3516CV610、cv610_xxx）
            # 成不了独立 token，只有 LIKE 子串能命中——合并后按度数排序
            regex = "|".join(pats)
            sql = (
                "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                "n.start_line, "
                "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) AS in_deg, "
                "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id) AS out_deg "
                "FROM nodes n "
                "WHERE (n.name LIKE ? OR n.file_path LIKE ?) "
                f"AND (n.name REGEXP '.*({regex}).*' OR n.file_path REGEXP '.*({regex}).*') "
                "ORDER BY (in_deg + out_deg) DESC LIMIT ?"
            )
            sub_rows = self._conn.execute(
                sql, (f"%{keywords[0]}%", f"%{keywords[0]}%", limit)).fetchall()
            if not fts_rows:
                return _hits(sub_rows)
            merged = {r[0]: r for r in fts_rows}
            for r in sub_rows:
                merged.setdefault(r[0], r)
            rows = sorted(merged.values(),
                          key=lambda r: (r[6] or 0) + (r[7] or 0),
                          reverse=True)[:limit]
            return _hits(rows)

    def entity_graph(self, name: str, edge_limit: int = 80):
        """以 name 为中心的邻接子图（任意边类型，含边属性锚点）。
        中心节点取同名中度数最大者；节点用 id 关联（同名 README.md 等
        不会混）。返回 {center, nodes, edges}；未找到返回 None。"""
        with self._lock:
            self._open()
            row = self._conn.execute(
                "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                "n.start_line, n.end_line FROM nodes n WHERE n.name=? "
                "ORDER BY (SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) "
                "DESC LIMIT 1", (name,)).fetchone()
            if not row:
                return None
            nid = row[0]
            erows = self._conn.execute(
                "SELECT e.type, e.properties, "
                "s.id, s.label, s.name, s.qualified_name, s.file_path, "
                "s.start_line, s.end_line, "
                "t.id, t.label, t.name, t.qualified_name, t.file_path, "
                "t.start_line, t.end_line "
                "FROM edges e JOIN nodes s ON s.id=e.source_id "
                "JOIN nodes t ON t.id=e.target_id "
                "WHERE e.source_id=? OR e.target_id=? LIMIT ?",
                (nid, nid, edge_limit)).fetchall()

        def _node(r):
            return {_K_ID: r[0], _K_LABEL: r[1] or "", _K_NAME: r[2] or "",
                    "qualified_name": r[3] or "", "file_path": r[4] or "",
                    "start_line": r[5] or 0, "end_line": r[6] or 0}

        center = _node(row)
        center["matched"] = True
        nodes = {nid: center}
        edges = []
        deg_local: dict = {nid: 0}
        for (etype, eprops, *rest) in erows:
            s, t = rest[:7], rest[7:]
            for r in (s, t):
                if r[0] not in nodes:
                    nodes[r[0]] = _node(r)
                    deg_local[r[0]] = 0
            deg_local[s[0]] += 1
            deg_local[t[0]] += 1
            ref_name, line = "", None
            try:
                p = json.loads(eprops) if eprops else {}
                if isinstance(p, dict):
                    ref_name = str(p.get(_K_REF_NAME) or "")
                    line = p.get(_K_LINE) if isinstance(p.get(_K_LINE), int) else None
            except (ValueError, TypeError):
                pass
            e = {_K_SOURCE: s[0], _K_TARGET: t[0], _K_TYPE: etype or ""}
            if ref_name or line is not None:
                e[_K_REF_NAME] = ref_name
                if line is not None:
                    e[_K_LINE] = line
            edges.append(e)
        # 网络化：补邻居之间的互连边（一跳查询只含触达中心的边，纯星形）
        if len(nodes) > 2:
            id_list = ",".join(str(i) for i in nodes)
            try:
                erows2 = self._conn.execute(
                    "SELECT e.type, e.properties, "
                    "s.id, s.label, s.name, s.qualified_name, s.file_path, "
                    "s.start_line, s.end_line, "
                    "t.id, t.label, t.name, t.qualified_name, t.file_path, "
                    "t.start_line, t.end_line "
                    f"FROM edges e JOIN nodes s ON s.id=e.source_id "
                    f"JOIN nodes t ON t.id=e.target_id "
                    f"WHERE e.source_id IN ({id_list}) AND e.target_id IN ({id_list}) "
                    f"LIMIT 400").fetchall()
            except sqlite3.Error:
                erows2 = []
            seen_e = {(e[_K_SOURCE], e[_K_TARGET], e[_K_TYPE]) for e in edges}
            for (etype, _eprops, *rest) in erows2:
                k = (rest[0], rest[7], etype)
                if k in seen_e:
                    continue
                seen_e.add(k)
                sid, tid = rest[0], rest[7]
                if sid not in nodes:
                    nodes[sid] = _node(rest[:7])
                    deg_local[sid] = 0
                if tid not in nodes:
                    nodes[tid] = _node(rest[7:])
                    deg_local[tid] = 0
                deg_local[sid] += 1
                deg_local[tid] += 1
                edges.append({_K_SOURCE: sid, _K_TARGET: tid, _K_TYPE: etype or ""})
        # 剪枝：去低相关悬挂点——度数 1 的非中心节点只保留全局度数 top15
        pendants = [n for n in nodes.values()
                    if n[_K_ID] != nid and deg_local.get(n[_K_ID], 0) <= 1]
        if pendants and len(nodes) > 40:
            pid = ",".join(str(n[_K_ID]) for n in pendants)
            gdeg = dict(self._conn.execute(
                "SELECT n.id, (SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id)"
                " + (SELECT COUNT(*) FROM edges e2 WHERE e2.target_id=n.id) "
                f"FROM nodes n WHERE n.id IN ({pid})").fetchall())
            pendants.sort(key=lambda n: -gdeg.get(n[_K_ID], 0))
            drop = {n[_K_ID] for n in pendants[15:]}
            nodes = {i: n for i, n in nodes.items() if i not in drop}
            edges = [e for e in edges
                     if e[_K_SOURCE] in nodes and e[_K_TARGET] in nodes]
        for n in nodes.values():
            n["deg_local"] = deg_local.get(n[_K_ID], 0)
        return {"center": center, "nodes": list(nodes.values()), "edges": edges}

    def node_detail(self, name: str, node_id=None, file_hint: str = ""):
        """节点详情（/graph_node）：id 优先；否则按名（可带 file_path 提示）
        取度数最大者。返回节点属性 + 邻边类型统计 + 原始 properties。"""
        with self._lock:
            self._open()
            if node_id:
                row = self._conn.execute(
                    "SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                    "n.start_line, n.end_line, n.properties, "
                    "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) AS in_deg, "
                    "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id) AS out_deg "
                    "FROM nodes n WHERE n.id=? LIMIT 1", (node_id,)).fetchone()
            else:
                sql = ("SELECT n.id, n.label, n.name, n.qualified_name, n.file_path, "
                       "n.start_line, n.end_line, n.properties, "
                       "(SELECT COUNT(*) FROM edges e WHERE e.target_id=n.id) AS in_deg, "
                       "(SELECT COUNT(*) FROM edges e WHERE e.source_id=n.id) AS out_deg "
                       "FROM nodes n WHERE n.name=? ")
                params: list = [name]
                if file_hint:
                    sql += "AND n.file_path LIKE ? "
                    params.append("%" + file_hint)
                sql += "ORDER BY (in_deg + out_deg) DESC LIMIT 1"
                row = self._conn.execute(sql, params).fetchone()
            if not row:
                return None
            (cid, label, nm, qn, fp, sl, el, props, in_deg, out_deg) = row
            in_types = dict(self._conn.execute(
                "SELECT e.type, COUNT(*) FROM edges e WHERE e.target_id=? "
                "GROUP BY e.type", (cid,)).fetchall())
            out_types = dict(self._conn.execute(
                "SELECT e.type, COUNT(*) FROM edges e WHERE e.source_id=? "
                "GROUP BY e.type", (cid,)).fetchall())
        try:
            props_obj = json.loads(props) if props else {}
        except (ValueError, TypeError):
            props_obj = {}
        return {"id": cid, "label": label or "", "name": nm or "",
                "qualified_name": qn or "", "file_path": fp or "",
                "start_line": sl or 0, "end_line": el or 0,
                "in_degree": in_deg or 0, "out_degree": out_deg or 0,
                "in_types": in_types, "out_types": out_types,
                "props": props_obj if isinstance(props_obj, dict) else {}}

    def _nb_rows(self, sql: str, params: tuple) -> list[dict]:
        out = []
        for r in self._conn.execute(sql, params):
            out.append({"name": r[0], "label": r[1], "file": r[2]})
        return out

    def _open(self):
        if not os.path.exists(self.db_path):
            raise FileNotFoundError(
                f"项目 '{self.project}' 图谱不存在: {self.db_path}"
            )
        st = os.stat(self.db_path)
        fp = (st.st_mtime_ns, st.st_ino)
        if self._conn is not None and self._fingerprint == fp:
            return
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception as e:
                log.debug("close old conn: %s", e)
        # check_same_thread=False：asyncio.to_thread 的线程池会跨线程使用
        # 连接；所有访问都在 self._lock 内，串行安全。
        self._conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True,
                                     check_same_thread=False)
        self._conn.create_function("REGEXP", 2, _regexp)
        self._fingerprint = fp


# 进程级连接缓存
_STORES: dict[str, KBStore] = {}


def get_store(project: str) -> KBStore:
    if project not in _STORES:
        _STORES[project] = KBStore(project)
    return _STORES[project]


# kbId → 知识库显示名（如 kbf1dc0… → CV610ALL）。名字存平台 PG
# （knowledge.knowledge_bases），dual-mcp 无 PG 客户端，改读共享 PVC 上的
# 映射文件（RAG qa pod 挂 /kb-files = dual-mcp 挂 /projects）：
#   刷新：在 qa pod 内用 psycopg2 查平台库后写 /kb-files/.kb-names.json
#   （幂等，mtime 变化自动重载；缺失时 kb_name 为空、前端回退显示 kbId）。
KB_NAMES_PATH = os.environ.get("KB_NAMES_PATH", "/projects/.kb-names.json")
_kb_names_cache: dict = {"map": {}, "mtime": -1.0}


def _kb_display_names() -> dict:
    try:
        m = os.path.getmtime(KB_NAMES_PATH)
        if m != _kb_names_cache["mtime"]:
            with open(KB_NAMES_PATH, encoding="utf-8") as f:
                _kb_names_cache["map"] = {
                    str(k): str(v) for k, v in json.load(f).items()}
            _kb_names_cache["mtime"] = m
    except (OSError, ValueError):
        pass
    return _kb_names_cache["map"]


def list_projects() -> list[dict]:
    names = _kb_display_names()
    out = []
    for p in sorted(Path(CACHE_DIR).glob("*.db")):
        if p.stem.startswith("_"):
            continue
        try:
            s = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
            cnt = s.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
            edges = s.execute("SELECT COUNT(*) FROM edges").fetchone()[0]
            root = s.execute("SELECT root_path FROM projects LIMIT 1").fetchone()
            s.close()
            _m = re.search(r"kb[0-9a-f]{20,}", p.stem)
            kb_id = _m.group(0) if _m else p.stem
            out.append({"name": p.stem, "nodes": cnt, "edges": edges,
                        "root_path": root[0] if root else "",
                        "kb_name": names.get(kb_id) or names.get(p.stem)})
        except sqlite3.Error:
            continue
    return out


# list_projects 原始实现每次打开全部 .db（EFS 上秒级），而 /query 的项目名
# 解析和 /projects 都高频调用它——30s TTL 缓存。
_projects_cache: dict = {_K_DATA: None, "ts": 0.0}
_PROJECTS_CACHE_TTL = float(os.environ.get('CBM_PROJECTS_CACHE_TTL', '600'))


def list_projects_cached() -> list[dict]:
    now = time.time()
    if _projects_cache[_K_DATA] is not None and now - _projects_cache["ts"] < _PROJECTS_CACHE_TTL:
        return _projects_cache[_K_DATA]
    data = list_projects()
    _projects_cache[_K_DATA] = data
    _projects_cache["ts"] = now
    return data


def resolve_project_name(project: str) -> str:
    """把调用方传入的项目名解析为已索引项目名。

    kbId == 项目名是命名约定，但 entrypoint 自动发现 /projects/* 时派生名带
    parent 前缀（如 projects-kbc212...）。精确不命中时做规范化包含匹配，
    唯一命中才采用；零个/多个命中保持原样（由 FileNotFoundError 报清晰错误）。
    """
    projects = list_projects_cached()
    if any(p[_K_NAME] == project for p in projects):
        return project

    def _norm(s: str) -> str:
        return re.sub(r"[^a-z0-9]", "", (s or "").lower())

    np_ = _norm(project)
    hits = [p for p in projects if np_ and np_ in _norm(p[_K_NAME])]
    if len(hits) > 1:
        # 双项目并存（建库空壳与 zip 变体 projects-kb<kbId>-code）：
        # 按图谱节点数择优，空壳让位给有数据的项目
        hits.sort(key=lambda p: p.get("nodes") or 0, reverse=True)
        best = hits[0]
        log.info(f"[project-alias] '{project}' 模糊命中多个 "
                 f"{[p['name'] for p in hits]}，按节点数取 {best['name']}"
                 f"({best.get('nodes')})")
        return best[_K_NAME]
    if len(hits) == 1:
        log.info(f"[project-alias] '{project}' → '{hits[0]['name']}'")
        return hits[0][_K_NAME]
    return project




# ── 文件级多版本透出：per-file 版本映射（RAG 上传链路写入）──────────
# 映射由 RAG 在建索引/重索引时随请求带上，存 <CACHE_DIR>/file_versions/
# <project>.json；/query 的 matches 与 include_source 据此附 file_version。
# 键为相对索引根的路径，查询时对 match.file_path 做精确或后缀匹配
# （不同 KB 的索引根可能是 code/ 子目录或整个 kbId 目录）。

_FILES_VERSION_DIR = os.path.join(CACHE_DIR, "file_versions")


def _files_version_path(project: str) -> str:
    return os.path.join(_FILES_VERSION_DIR, f"{project}.json")


def _save_files_version(project: str, mapping: dict) -> None:
    try:
        os.makedirs(_FILES_VERSION_DIR, exist_ok=True)
        tmp = _files_version_path(project) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(mapping or {}, f, ensure_ascii=False)
        os.replace(tmp, _files_version_path(project))
    except Exception as e:
        log.info(f"[files-version] 保存 {project} 版本映射失败: {e}")


_FILE_LINES_CACHE: dict = {}      # (path, mtime_ns, size) -> 行列表；mtime 变即失效
_FILES_VERSION_CACHE: dict = {}    # project -> (ts, map)，TTL 300s


def _read_lines_cached(path: str):
    """按 (mtime,size) 缓存源文件行——NFS 上重复整读是大头（include_source
    每查询读 top-N 个源文件，且多符号常共享同一文件）。"""
    try:
        st = os.stat(path)
    except OSError:
        return None
    key = (path, st.st_mtime_ns, st.st_size)
    hit = _FILE_LINES_CACHE.get(key)
    if hit is not None:
        return hit
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return None
    if len(_FILE_LINES_CACHE) > 512:
        _FILE_LINES_CACHE.clear()
    _FILE_LINES_CACHE[key] = lines
    return lines


def _read_files_version_uncached(project: str) -> dict:
    try:
        with open(_files_version_path(project), encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except Exception as e:
        log.debug("读 files_version 失败: %s", e)
    # 兜底：建库时传入的项目名与 CBM 实际注册名可能不一致（如 derive 出的
    # projects-xxx-code），按规范化包含匹配唯一命中时采用
    def _norm(s: str) -> str:
        return re.sub(r"[^a-z0-9]", "", (s or "").lower())
    np_ = _norm(project)
    if not np_:
        return {}
    try:
        hits = []
        for f in os.listdir(_FILES_VERSION_DIR):
            if not f.endswith(".json"):
                continue
            fn = _norm(f[:-5])
            if np_ in fn or fn in np_:
                hits.append(f)
    except OSError:
        return {}
    if len(hits) != 1:
        return {}
    try:
        with open(os.path.join(_FILES_VERSION_DIR, hits[0]), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _lookup_files_version(mapping: dict, file_path: str):
    """按精确或后缀匹配查 file_path 的版本；未命中返回 None（视为 v1）。"""
    if not mapping or not file_path:
        return None
    if file_path in mapping:
        return mapping[file_path]
    best_key, best_len = None, -1
    for k in mapping:
        if file_path.endswith(k) and len(k) > best_len:
            best_key, best_len = k, len(k)
    if best_key is not None:
        return mapping[best_key]
    # 反向后缀：映射键带 code/ 前缀而图谱路径不带（或反之）时对齐
    for k in mapping:
        if k.endswith(file_path) and len(file_path) > best_len:
            return mapping[k]
    return None




def _load_files_version(project: str) -> dict:
    now = time.time()
    hit = _FILES_VERSION_CACHE.get(project)
    if hit is not None and now - hit[0] < 300:
        return hit[1]
    data = _read_files_version_uncached(project)
    if len(_FILES_VERSION_CACHE) > 64:
        _FILES_VERSION_CACHE.clear()
    _FILES_VERSION_CACHE[project] = (now, data)
    return data

