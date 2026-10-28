"""MilvusKBService 集成测试。

需真实 Milvus 服务。原文件 import 了未使用的 PGKBService,
而 PGKBService 在类定义期会触发 create_engine(None) 崩溃(见 Q6),
故移除该未使用导入,并对 Milvus 做可达性检查,不可达则模块级 skip。
"""
import socket

import pytest

from configs import kbs_config

_milvus_cfg = kbs_config.get("milvus") or {}
_host = _milvus_cfg.get("host")
try:
    _port = int(_milvus_cfg.get("port") or 0)
except (TypeError, ValueError):
    _port = 0
if not (_host and _port):
    pytest.skip("milvus 未配置 host/port", allow_module_level=True)
try:
    with socket.create_connection((_host, _port), timeout=2):
        pass
except OSError as e:
    pytest.skip(f"Milvus 服务不可达 {_host}:{_port} ({e})", allow_module_level=True)

from server.knowledge_base.kb_service.milvus_kb_service import MilvusKBService
from server.knowledge_base.migrate import create_tables
from server.knowledge_base.utils import KnowledgeFile

# 注意:已移除未使用的 PGKBService 导入,因其在类定义期触发 create_engine(None) 崩溃(见 Q6)
kbService = MilvusKBService("test")

test_kb_name = "test"
test_file_name = "README.md"
testKnowledgeFile = KnowledgeFile(test_file_name, test_kb_name)
search_content = "如何启动api服务"

def test_init():
    create_tables()


def test_create_db():
    assert kbService.create_kb()


def test_add_doc():
    assert kbService.add_doc(testKnowledgeFile)


def test_search_db():
    result = kbService.search_docs(search_content)
    assert len(result) > 0
def test_delete_doc():
    assert kbService.delete_doc(testKnowledgeFile)
