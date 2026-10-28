# pylint: disable=no-use-sys-path-insert
import os
import sys
from pathlib import Path

import pytest
from langchain_core.documents import Document

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from configs import CHUNK_SIZE, OVERLAP_SIZE, logger
from server.knowledge_base.utils import make_text_splitter

SAMPLE_FILE = Path(__file__).resolve().parent.parent.parent / "knowledge_base" / "samples" / "content" / "test.txt"


def text(splitter_name):
    from langchain_community import document_loaders

    # 使用DocumentLoader读取文件
    filepath = str(SAMPLE_FILE)
    loader = document_loaders.UnstructuredFileLoader(filepath, autodetect_encoding=True)
    docs = loader.load()
    text_splitter = make_text_splitter(splitter_name, CHUNK_SIZE, OVERLAP_SIZE)
    if splitter_name == "MarkdownHeaderTextSplitter":
        docs = text_splitter.split_text(docs[0].page_content)
        for doc in docs:
            if doc.metadata:
                doc.metadata["source"] = os.path.basename(filepath)
    else:
        docs = text_splitter.split_documents(docs)
    for doc in docs:
        logger.info("{}", doc)
    return docs




@pytest.mark.parametrize("splitter_name",
                         [
                             "ChineseRecursiveTextSplitter",
                             "SpacyTextSplitter",
                             "RecursiveCharacterTextSplitter",
                             "MarkdownHeaderTextSplitter"
                         ])
def test_different_splitter(splitter_name):
    if not SAMPLE_FILE.exists():
        pytest.skip("测试数据 knowledge_base/samples/content/test.txt 不存在")
    try:
        docs = text(splitter_name)
        assert isinstance(docs, list)
        if len(docs)>0:
            assert isinstance(docs[0], Document)
    except Exception as e:
        pytest.fail(f"test_different_splitter failed with {splitter_name}, error: {str(e)}")