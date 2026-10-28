"""文档工具：归一化、跨路去重、按整块预算拼接、版本/标签过滤。"""
import re

from langchain.docstore.document import Document


def _matches_version_tags(doc, version: str | None,
                          tags: list[str] | None,
                          cur_labels=None) -> bool:
    """检查 doc 是否匹配 version/tags 过滤条件（与 Milvus filter 语义对齐）。

    用于 dual_mcp/mempalace 客户端过滤（这两路不支持服务端 filter），
    与 milvus_retrieval._search_milvus_impl 的 filter_expr 保持一致。

    语义：
    - version 传值：metadata["file_version_label"] 必须精确等于 version
      （无 label 的 doc 不匹配——与 Milvus `== "v1.2.3"` 一致）
    - version=None + cur_labels 非空：有 label 但不在 cur_labels 的过滤掉；
      无 label 的 doc 保留（P1-1：Milvus 服务端 filter 加 `or is null` 已对齐，
      三路一致保留无 label 文档，保护历史遗留无版本号文档可检索 + code 路代码 chunk）
    - version=None + cur_labels None/空：不过滤（fail-open，空库或未查映射）
    - tags（OR 语义）：metadata["tags"] 含至少一个指定 tag；None/空不加过滤
    - 两者叠加：同时满足
    """
    # version 过滤
    if version is not None:
        if doc.metadata.get("file_version_label") != version:
            return False
    elif cur_labels:
        doc_label = doc.metadata.get("file_version_label")
        # 有 label 但不在当前版本集合 → 旧版本 chunk，过滤掉
        # 无 label → 保留（code/mempalace 无版本号的 doc 不受版本过滤影响）
        if doc_label is not None and doc_label not in cur_labels:
            return False
    # tags 过滤（OR 语义）
    if tags:
        doc_tags = doc.metadata.get("tags") or []
        if not isinstance(doc_tags, list):
            doc_tags = []
        if not any(t in doc_tags for t in tags):
            return False
    return True


def _norm_text(s: str) -> str:
    """归一化文本：去掉所有空白，用于跨块去重比较（HTML 噪声块空白差异大）。"""
    return re.sub(r"\s+", "", s or "")


def _dedupe_docs(docs: list[Document]) -> list[Document]:
    """跨路去重：Milvus/mempalace(水合)/code 三路常返回相同或互相包含的文本块。

    归一化（去空白）后完全相同、或一方完整包含另一方时，只保留内容更全的一份，
    避免重复块挤占 reranker top_n 名额与上下文预算。空文本块直接丢弃。

    性能优化：预计算 norm_text + 长度分区，避免 O(n²) 内层重复 regex 替换与
    无效子串匹配。RRF 融合已用内容哈希合并"完全相同"的块（见编排层 L547-563），
    此处主要处理"一方完整包含另一方"的包含关系。
    """
    out: list[Document] = []
    out_norm: list[str] = []   # 与 out 同步：已保留块的 norm_text（预计算，避免内层重算）
    out_lens: list[int] = []   # 与 out 同步：norm_text 长度（用于长度分区过滤）

    for d in docs:
        t = _norm_text(d.page_content)
        if not t:
            continue  # 空内容块跳过
        t_len = len(t)
        dup = False
        for i, kt in enumerate(out_norm):
            kt_len = out_lens[i]
            # 长度分区：包含关系的必要条件，长度不满足直接跳过（省子串匹配）
            # t==kt 需 len 相等；t in kt（d 是 kept 子串）需 len(t)<len(kt)；
            # kt in t（kept 是 d 子串）需 len(kt)<len(t)。三区间互斥，每对最多 1 次子串匹配
            if t_len == kt_len:
                if t == kt:           # 完全相同（RRF 已处理，防御性）
                    dup = True
                    break
            elif t_len < kt_len:
                if t in kt:           # d 被 kept 包含 → 丢弃 d
                    dup = True
                    break
            else:  # t_len > kt_len
                if kt in t:           # d 包含 kept → 用 d 替换 kept
                    out[i] = d
                    out_norm[i] = t
                    out_lens[i] = t_len
                    dup = True
                    break
        if not dup:
            out.append(d)
            out_norm.append(t)
            out_lens.append(t_len)
    return out


def _join_chunks_with_budget(docs: list[Document], budget: int) -> tuple[str, list[Document]]:
    """按整块预算拼接：只纳入能完整放下的块，不拦腰截断。

    返回 (拼接文本, 实际纳入的块列表)——用于在 docs 响应中标记哪些块真正进了 LLM 上下文。
    """
    parts = []
    used_docs: list[Document] = []
    used = 0
    for d in docs:
        text = (d.page_content or "").replace("===", "-").replace("^^^", "")
        if not text.strip():
            continue
        add = len(text) + (1 if parts else 0)
        if used + add > budget:
            break
        parts.append(text)
        used_docs.append(d)
        used += add
    return "\n".join(parts), used_docs
