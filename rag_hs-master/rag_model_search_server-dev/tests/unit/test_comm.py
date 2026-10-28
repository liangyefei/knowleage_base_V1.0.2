#!/usr/bin/env python3
"""
comm 域（Kafka 通信）单元测试。

覆盖:
- server.comm.task_factory.KafkaTaskFactory.create_task 工厂分发逻辑（纯逻辑，无需 Kafka）
- server.comm.sfs_path.resolve_sfs_path 路径解析与防穿越（纯逻辑，无需 Kafka）

Kafka 集成链路（KafkaCallbackProducer）已迁至
tests/it/test_kafka_integration.py，需真实 Kafka，连不上则 skip。
"""
# pylint: disable=no-use-sys-path-insert
import json
import logging
import sys
from pathlib import Path

logger = logging.getLogger(__name__)

# 将项目根目录加入 sys.path，保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest


def _try_connect_kafka():
    """尝试连接 Kafka，成功返回可用 bootstrap_servers 字符串，失败返回 None。

    候选顺序：configs.COMM_KAFKA_ADDRESS.bootstrap_servers -> 本地 haisi-kafka-dev:9092。
    不硬编码 IP，连接参数全部来自 configs.COMM_KAFKA_ADDRESS 或本地测试容器。
    """
    try:
        from kafka import KafkaProducer
    except Exception:
        return None
    from configs import COMM_KAFKA_ADDRESS
    candidates = []
    bs = COMM_KAFKA_ADDRESS.get("bootstrap_servers") if COMM_KAFKA_ADDRESS else None
    if bs:
        candidates.append(bs)
    candidates.append("haisi-kafka-dev:9092")  # 本地测试容器
    for c in candidates:
        try:
            p = KafkaProducer(
                bootstrap_servers=c,
                value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                api_version_auto_timeout_ms=3000,
                request_timeout_ms=5000,
            )
            # 构造成功即代表 broker 可达（内部会做 api version 探测）
            p.close()
            return c  # 返回可用地址
        except Exception as e:
            logger.debug("[test-comm] probe kafka %s failed: %s", c, e)
            continue
    return None


# ==================== task_factory 测试（纯逻辑，无需 Kafka）====================

from server.comm.task_factory import (
    ArchiveDocumentTask,
    CreateKnowledgeTask,
    DeleteDocumentTask,
    DeleteKnowledgeTask,
    KafkaTaskFactory,
    NoopTask,
    PublishDocumentTask,
    RetryProcessDocumentTask,
)


class TestKafkaTaskFactory:
    @pytest.mark.parametrize("event_type,expected_cls", [
        ("KB_CREATED", CreateKnowledgeTask),
        ("KB_UPDATED", NoopTask),
        ("KB_DELETED", DeleteKnowledgeTask),
        ("DOCUMENT_PUBLISHED", PublishDocumentTask),
        ("DOCUMENT_ARCHIVED", ArchiveDocumentTask),
        ("DOCUMENT_DELETED", DeleteDocumentTask),
        ("DOCUMENT_RETRY_PROCESS", RetryProcessDocumentTask),
    ])
    @staticmethod
    def test_create_task_dispatch(event_type, expected_cls):
        envelope = {"eventType": event_type, "kbId": "k1", "documentId": "d1", "payload": {}}
        task = KafkaTaskFactory.create_task(envelope)
        assert isinstance(task, expected_cls), (
            f"{event_type} -> {type(task).__name__} != {expected_cls.__name__}"
        )

    @staticmethod
    def test_unknown_event_type_raises_type_error():
        envelope = {"eventType": "UNKNOWN_EVENT", "kbId": "k1", "payload": {}}
        with pytest.raises(TypeError):
            KafkaTaskFactory.create_task(envelope)

    @staticmethod
    def test_missing_event_type_raises():
        # eventType 缺失时 get 返回 None，落入 else 分支抛 TypeError
        envelope = {"kbId": "k1", "payload": {}}
        with pytest.raises(TypeError):
            KafkaTaskFactory.create_task(envelope)


# ==================== 幂等性测试（rebalance 重投递场景）====================

from server.comm.task_factory import CreateKnowledgeTask, DeleteKnowledgeTask
from server.utils import BaseResponse


class TestIdempotency:
    """KB_CREATED / KB_DELETED 在 rebalance 重投递下的幂等性。"""

    @staticmethod
    def test_create_kb_already_exists_treated_as_success(monkeypatch):
        # create_kb 返回 404 "已存在同名知识库" → CreateKnowledgeTask 应转为 200
        def fake_create_kb(knowledge_base_name):
            return BaseResponse(code=404, msg=f"已存在同名知识库 {knowledge_base_name}")
        monkeypatch.setattr("server.comm.task_factory.create_kb", fake_create_kb)
        # 避免触发 dual-mcp 索引（import 即副作用，patch 到模块属性即可）
        monkeypatch.setattr("server.comm.dual_mcp_client.trigger_index_async", lambda kb: None,
                            raising=False)
        task = CreateKnowledgeTask()
        res = task.request({"kbId": "kb1"})
        assert res.code == 200
        assert "已存在" in res.msg or "视为创建成功" in res.msg

    @staticmethod
    def test_create_kb_validation_404_not_idempotent(monkeypatch):
        # create_kb 返回 404 但非 "已存在"（如名称为空）→ 保持 404，不转 200
        def fake_create_kb(knowledge_base_name):
            return BaseResponse(code=404, msg="知识库名称不能为空，请重新填写知识库名称")
        monkeypatch.setattr("server.comm.task_factory.create_kb", fake_create_kb)
        task = CreateKnowledgeTask()
        res = task.request({"kbId": "kb1"})
        assert res.code == 404  # 验证类 404 不幂等

    @staticmethod
    def test_delete_kb_not_found_treated_as_success(monkeypatch):
        # delete_kb 返回 404 "未找到知识库" → DeleteKnowledgeTask 应转为 200
        def fake_delete_kb(knowledge_base_name):
            return BaseResponse(code=404, msg=f"未找到知识库 {knowledge_base_name}")
        monkeypatch.setattr("server.comm.task_factory.delete_kb", fake_delete_kb)
        task = DeleteKnowledgeTask()
        res = task.request({"kbId": "kb1"})
        assert res.code == 200
        assert "视为已删除" in res.msg

    @staticmethod
    def test_delete_kb_success_passthrough(monkeypatch):
        # delete_kb 返回 200 → 直接透传
        def fake_delete_kb(knowledge_base_name):
            return BaseResponse(code=200, msg=f"成功删除知识库 {knowledge_base_name}")
        monkeypatch.setattr("server.comm.task_factory.delete_kb", fake_delete_kb)
        task = DeleteKnowledgeTask()
        res = task.request({"kbId": "kb1"})
        assert res.code == 200


# ==================== resolve_sfs_path 测试（纯逻辑，无需 Kafka）====================

from server.comm import sfs_path
from server.comm.sfs_path import resolve_sfs_path


class TestResolveSfsPath:
    @staticmethod
    def test_normal_relative_path(monkeypatch):
        monkeypatch.setattr(sfs_path, "SFS_MOUNT_PATH", "/mnt/d")
        assert resolve_sfs_path("kb/abc/file.pdf") == "/mnt/d/kb/abc/file.pdf"

    @staticmethod
    def test_subpath_with_special_chars(monkeypatch):
        monkeypatch.setattr(sfs_path, "SFS_MOUNT_PATH", "/mnt/sfs")
        assert resolve_sfs_path("kb/ef6eeb8e/cat-001/需求文档v3.xlsx") == (
            "/mnt/sfs/kb/ef6eeb8e/cat-001/需求文档v3.xlsx"
        )

    @pytest.mark.parametrize("bad_path", [
        "../etc/passwd",       # .. 穿越
        "/etc/passwd",         # POSIX 绝对路径
        "C:/x",                # Windows 盘符
        "d:/x",                # 小写盘符
        "D:\\x",               # Windows 反斜杠盘符
        "kb/../x",             # 中间含 ..
        "",                    # 空
    ])
    @staticmethod
    def test_rejects_traversal_and_absolute(monkeypatch, bad_path):
        monkeypatch.setattr(sfs_path, "SFS_MOUNT_PATH", "/mnt/d")
        with pytest.raises(ValueError):
            resolve_sfs_path(bad_path)

    @staticmethod
    def test_empty_raises(monkeypatch):
        monkeypatch.setattr(sfs_path, "SFS_MOUNT_PATH", "/mnt/d")
        with pytest.raises(ValueError):
            resolve_sfs_path("")
        with pytest.raises(ValueError):
            resolve_sfs_path(None)
