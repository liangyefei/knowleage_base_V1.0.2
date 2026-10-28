#!/usr/bin/env python3
"""kg_stats 瘦身 patch（QA 报告 P2：输出 92.7K 字符客户端无法消费）。

对 site-packages 里的 knowledge_graph.py 做幂等字符串级 patch：
stats() 的 relationship_types 从全量 DISTINCT 谓词（LLM 自由命名数千条）
改为按频次 Top-30，并新增 top_predicates 计数列表。
版本变化时若模式不匹配则跳过（不打坏包）。
"""

import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import sys

TARGET = "/app/.venv/lib/python3.12/site-packages/mempalace/knowledge_graph.py"
MARKER = "top_predicates"

OLD = '''            predicates = [
                r["predicate"]
                for r in conn.execute(
                    "SELECT DISTINCT predicate FROM triples ORDER BY predicate"
                ).fetchall()
            ]
        return {
            "entities": entities,
            "triples": triples,
            "current_facts": current,
            "expired_facts": expired,
            "relationship_types": predicates,
        }'''

NEW = '''            predicates = [
                r["predicate"]
                for r in conn.execute(
                    "SELECT predicate FROM triples GROUP BY predicate ORDER BY COUNT(*) DESC LIMIT 30"
                ).fetchall()
            ]
            predicate_counts = [
                {"predicate": r["predicate"], "count": r["cnt"]}
                for r in conn.execute(
                    "SELECT predicate, COUNT(*) AS cnt FROM triples GROUP BY predicate "
                    "ORDER BY cnt DESC LIMIT 30"
                ).fetchall()
            ]
        return {
            "entities": entities,
            "triples": triples,
            "current_facts": current,
            "expired_facts": expired,
            "relationship_types": predicates,
            "top_predicates": predicate_counts,
        }'''


def main():
    _S_KEY = '%s'
    try:
        with open(TARGET, encoding="utf-8") as f:
            src = f.read()
    except OSError as e:
        logger.info("[kg-stats-patch] 打开失败: %s", e)
        return 1
    if MARKER in src:
        logger.info(_S_KEY, '[kg-stats-patch] 已打过，跳过')
        return 0
    if OLD not in src:
        logger.info(_S_KEY, '[kg-stats-patch] 目标代码块未找到（包版本变化？）——跳过')
        return 0
    with open(TARGET, "w", encoding="utf-8") as f:
        f.write(src.replace(OLD, NEW, 1))
    logger.info(_S_KEY, '[kg-stats-patch] 已应用：relationship_types → Top-30 + top_predicates 计数')
    return 0


if __name__ == "__main__":
    sys.exit(main())
