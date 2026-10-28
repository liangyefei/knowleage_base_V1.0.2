"""验证 _build_source_documents 对联网搜索结果透出 name（网页标题）字段。

为避免 import 整个 chat 模块触发模块级 Milvus 连接，这里用 ast 从源文件
直接提取被测函数执行，纯本地无网络依赖。

容器内运行：
  cd /mnt/d/Code/haisi/haisi_rag_models_server
  python3 release/test/test_source_docs_name_field.py
"""
import ast
import json
import os
import sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "server", "chat", "multi_knowledge_base_chat.py")


def _load_func(name: str):
    """从源文件提取指定函数定义并编译，返回可调用对象"""
    with open(SRC, encoding="utf-8") as f:
        tree = ast.parse(f.read())
    node = next(n for n in ast.walk(tree)
                if isinstance(n, ast.FunctionDef) and n.name == name)
    module = ast.parse("")
    module.body = [node]
    ns = {"os": os, "OrderedDict": OrderedDict}
    exec(compile(module, SRC, "exec"), ns)
    return ns[name]


def main() -> None:
    build = _load_func("_build_source_documents")
    from langchain.docstore.document import Document

    docs = [
        Document(page_content="网页标题A\n摘要内容A", metadata={
            "source": "https://example.com/a",
            "doc_type": "internet_search",
            "title": "网页标题A",
        }),
        Document(page_content="网页标题B\n摘要内容B", metadata={
            "source": "https://example.com/b",
            "doc_type": "internet_search",
            "title": "网页标题B",
        }),
        Document(page_content="知识库内容", metadata={
            "source": "/kb-files/kb/CV610/docs/manual.pdf",
            "doc_type": "milvus",
        }),
    ]
    used = {id(d) for d in docs}
    out = build(docs, used)
    print(json.dumps(out, ensure_ascii=False, indent=2))

    by_file = {d["file"]: d for d in out}
    assert by_file["https://example.com/a"].get("name") == "网页标题A", "internet_search 条目缺少 name"
    assert by_file["https://example.com/b"].get("name") == "网页标题B", "internet_search 条目缺少 name"
    assert "name" not in by_file["/kb-files/kb/CV610/docs/manual.pdf"], "milvus 条目不应被注入 name"
    print("\n[OK] internet_search 条目已带 name(网页标题)，知识库条目不受影响", file=sys.stderr)


if __name__ == "__main__":
    main()
