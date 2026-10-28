"""检索过滤参数封装：version/tags/cur_labels 的共享 dataclass。

供 kb_doc_api._search_milvus / pipeline 检索路（milvus/dual_mcp/mempalace）/
multi_knowledge_base_chat._retrieve_one_kb_one_query 复用，减少函数参数数量（H3403）。

定义在 knowledge_base 层（中立位置），供 chat.pipeline 与 knowledge_base 双向复用，
避免跨层依赖（与 milvus_filter.py 同模式）。
"""
from dataclasses import dataclass


@dataclass
class SearchFilter:
    """检索过滤参数（多版本 + 标签）。

    version: 指定版本 label 精确匹配；None 时用 cur_labels 做默认当前版本过滤。
    tags: 标签 OR 语义过滤（json_contains / 客户端过滤）；None/空不加过滤。
    cur_labels: 编排层注入的当前版本 label 集合，version is None 时用；
        None 则自查 build_files_version_map（向后兼容其他调用方）。
    """
    version: str | None = None
    tags: list[str] | None = None
    cur_labels: set | None = None
