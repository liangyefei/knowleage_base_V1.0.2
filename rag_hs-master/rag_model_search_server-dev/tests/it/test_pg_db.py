"""PGKBService 集成测试。

需真实 PG 服务且 kbs_config.pg 中提供 connection_uri。
pg_kb_service 在类定义期执行 create_engine(connection_uri),
connection_uri 为 None 时会抛 ArgumentError 导致 collection ERROR,
故此处先做配置/可达性检查,不满足则模块级 skip。
PG 连接串缺失见 tests/api/question.md Q6。
"""
import socket

import pytest

from configs import kbs_config

_pg_cfg = kbs_config.get("pg") or {}
_uri = _pg_cfg.get("connection_uri")
if not _uri:
    pytest.skip(
        "pg 未配置 connection_uri(见 tests/api/question.md Q6)",
        allow_module_level=True,
    )

_host = _pg_cfg.get("host")
try:
    _port = int(_pg_cfg.get("port") or 0)
except (TypeError, ValueError):
    _port = 0
if not (_host and _port):
    pytest.skip("pg 未配置 host/port", allow_module_level=True)
try:
    with socket.create_connection((_host, _port), timeout=2):
        pass
except OSError as e:
    pytest.skip(f"PG 服务不可达 {_host}:{_port} ({e})", allow_module_level=True)

from server.knowledge_base.kb_service.pg_kb_service import PGKBService
from server.knowledge_base.migrate import create_tables
from server.knowledge_base.utils import KnowledgeFile

kbService = PGKBService("test")

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
