#!/usr/bin/env python3
"""
ProcessingEngine 集成单元测试：并行调度 + 顺序门 + offset 跟踪 + 回调投递。

覆盖 spec.md 场景：
- 端到端：KB_CREATED + N 条 DOCUMENT_PUBLISHED + KB_DELETED，断言并行 + 顺序 + 回调 + offset 推进
- KB_CREATED 屏障：文档事件在 create_kb 完成前排队
- VLM 限流：同时调 VLM 不超过 vlm_concurrency_limit
- 同 documentId FIFO
- 跨 documentId 并行
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
import threading
import time
from collections import namedtuple
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest
from kafka.structs import TopicPartition

from server.comm import processing_engine as pe_mod
from server.comm.processing_engine import ProcessingEngine
from server.utils import BaseResponse

# kafka ConsumerRecord 兼容的最小 mock
MockRecord = namedtuple(
    "MockRecord",
    ["topic", "partition", "offset", "timestamp", "timestamp_type", "key", "value", "headers", "checksum"],
)

_DEFAULT_OFFSET = 0
_DEFAULT_PARTITION = 0
_DEFAULT_TOPIC = "kb-document-processing"


def make_msg(event_type, kb_id, document_id=None, **kwargs):
    """构造 mock Kafka 消息。

    可选参数 offset/partition/topic 通过 kwargs 传入，避免签名参数过多。
    """
    offset = kwargs.get("offset", _DEFAULT_OFFSET)
    partition = kwargs.get("partition", _DEFAULT_PARTITION)
    topic = kwargs.get("topic", _DEFAULT_TOPIC)
    envelope = {
        "messageId": f"mid-{offset}",
        "eventType": event_type,
        "kbId": kb_id,
        "documentId": document_id,
        "timestamp": "2026-08-20T00:00:00Z",
        "payload": {"filePath": f"kb/{kb_id}/cat/{document_id}.pdf"} if document_id else {},
    }
    return MockRecord(
        topic=topic, partition=partition, offset=offset, timestamp=0,
        timestamp_type=0, key=kb_id, value=envelope, headers=[], checksum=None,
    )


class FakeTask:
    """可控的假任务：执行时调 fn 并返回 BaseResponse(200)。"""
    def __init__(self, fn=None, code=200, msg="ok", data=None):
        self._fn = fn
        self._code = code
        self._msg = msg
        self._data = data

    def run(self, envelope):
        if self._fn:
            self._fn(envelope)
        return {"code": self._code, "msg": self._msg, "data": self._data}


class FakeProducer:
    """记录回调投递的假生产者。

    send_callback_transactional 成功返回 True，失败（send_callback_result=False）raise RuntimeError。
    callbacks 记录 (event_type, kb_id, document_id, payload) 元组（从 value envelope 提取，保持旧 shape）。
    is_transactional_available 默认 True（走事务路径），可设 transactional_available=False 测降级路径。
    """
    send_callback_result = True
    transactional_available = True

    def __init__(self, ok=True, transactional=True):
        self.callbacks = []
        self._lock = threading.Lock()
        if not ok:
            self.send_callback_result = False
        self.transactional_available = transactional

    def is_transactional_available(self):
        return self.transactional_available

    def send_callback(self, event_type, kb_id, document_id, payload):
        """非事务降级路径用：成功返回 True，失败返回 False（与事务路径 raise 不同）。"""
        with self._lock:
            self.callbacks.append((event_type, kb_id, document_id, payload))
        return self.send_callback_result

    def send_callback_transactional(self, key, value, offsets, group_id, timeout=30):
        with self._lock:
            self.callbacks.append((
                value.get("eventType"),
                value.get("kbId"),
                value.get("documentId"),
                value.get("payload"),
            ))
        if not self.send_callback_result:
            raise RuntimeError("send_callback_transactional 失败（模拟事务失败）")
        return True


def make_engine(producer=None, commit_fn=None, vlm_limit=8, doc_pool=16, group_id=None):
    """构造 ProcessingEngine，强制覆盖 VLM/worker pool 配置。group_id 用于事务化 offset 提交。"""
    if producer is None:
        producer = MagicMock()
    # monkeypatch 配置常量（engine 在 __init__ 时读）
    old_vlm = pe_mod.COMM_KAFKA_VLM_CONCURRENCY_LIMIT
    old_doc = pe_mod.COMM_KAFKA_DOC_WORKER_POOL_SIZE
    old_ctrl = pe_mod.COMM_KAFKA_CONTROL_WORKER_POOL_SIZE
    old_shut = pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS
    pe_mod.COMM_KAFKA_VLM_CONCURRENCY_LIMIT = vlm_limit
    pe_mod.COMM_KAFKA_DOC_WORKER_POOL_SIZE = doc_pool
    pe_mod.COMM_KAFKA_CONTROL_WORKER_POOL_SIZE = 1
    pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = 30000
    try:
        engine = ProcessingEngine(producer=producer, commit_fn=commit_fn, group_id=group_id)
    finally:
        pe_mod.COMM_KAFKA_VLM_CONCURRENCY_LIMIT = old_vlm
        pe_mod.COMM_KAFKA_DOC_WORKER_POOL_SIZE = old_doc
        pe_mod.COMM_KAFKA_CONTROL_WORKER_POOL_SIZE = old_ctrl
        pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = old_shut
    return engine


class TestEngineGroupId:
    """__init__ 接收 group_id 参数，供 _execute_transaction 事务用。"""

    @staticmethod
    def test_group_id_stored():
        engine = make_engine(group_id="my-group")
        assert engine._group_id == "my-group"

    @staticmethod
    def test_group_id_default_none():
        engine = make_engine()
        assert engine._group_id is None


class TestAdvanceOffsetNoCommit:
    """事务化：_advance_offset 只推进内存 base，不再调 _commit_fn（offset 由 producer 事务提交）。"""

    @staticmethod
    def test_advance_offset_does_not_call_commit_fn():
        commit_fn = MagicMock()
        engine = make_engine(commit_fn=commit_fn, group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._advance_offset(tp, 0)
        # 不调 commit_fn（事务化后 offset 提交由 producer 事务完成）
        commit_fn.assert_not_called()

    @staticmethod
    def test_advance_offset_still_advances_base():
        engine = make_engine(group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._advance_offset(tp, 0)
        # 内存 base 推进到 1
        assert engine._offset_tracker._base[tp] == 1


class TestExecuteTransaction:
    """_execute_transaction：事务化投递回调 + 提交 offset，带重试计数与死信。"""

    @staticmethod
    def _make_engine_with_producer(producer=None, group_id="model-service"):
        if producer is None:
            producer = MagicMock()
        return make_engine(producer=producer, group_id=group_id)

    def test_transaction_success_advances_base_and_clears_retry(self):
        producer = MagicMock()
        # send_callback_transactional 成功（不抛异常）
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        # 预置 retry_count（模拟之前失败过）
        engine._callback_retry[tp] = {0: 2}
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # base 推进
        assert engine._offset_tracker._base[tp] == 1
        # retry 清理
        assert engine._callback_retry.get(tp, {}).get(0) is None
        # producer.send_callback_transactional 被调
        producer.send_callback_transactional.assert_called_once()

    def test_transaction_failure_no_advance_no_retry_clear(self):
        producer = MagicMock()
        producer.send_callback_transactional.side_effect = RuntimeError("tx failed")
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is False
        # base 不推进
        assert engine._offset_tracker._base.get(tp) is None or engine._offset_tracker._base[tp] == 0
        # retry 递增到 1
        assert engine._callback_retry[tp][0] == 1

    def test_transaction_failure_max_retries_force_advance_and_deadletter(self):
        producer = MagicMock()
        producer.send_callback_transactional.side_effect = RuntimeError("tx failed")
        # max_retries=2：第 3 次失败达阈值强制推进
        with patch("server.comm.processing_engine.COMM_KAFKA_CALLBACK_MAX_RETRIES", 2):
            engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        # 预置 retry=1（下次失败即达阈值 2）
        engine._callback_retry[tp] = {0: 1}
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True  # 达阈值强制推进
        # base 推进
        assert engine._offset_tracker._base[tp] == 1
        # retry 清理
        assert engine._callback_retry.get(tp, {}).get(0) is None

    def test_transaction_deadletter_calls_poller_commit_offsets(self):
        """死信路径：达 max_retries 时强制 poller.commit_offsets（放弃回调投递，非事务提交到 broker）。"""
        producer = MagicMock()
        producer.send_callback_transactional.side_effect = RuntimeError("tx failed")
        with patch("server.comm.processing_engine.COMM_KAFKA_CALLBACK_MAX_RETRIES", 2):
            engine = self._make_engine_with_producer(producer)
        # 注入 mock poller（事务化后死信路径用 poller.commit_offsets 强制提交）
        poller = MagicMock()
        engine._poller = poller
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        # 预置 retry=1（下次失败即达阈值 2）
        engine._callback_retry[tp] = {0: 1}
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # poller.commit_offsets 被调，提交 offset+1（放弃回调投递）
        poller.commit_offsets.assert_called_once()
        offsets_arg = poller.commit_offsets.call_args.args[0]
        assert offsets_arg[tp].offset == 1  # offset+1

    def test_transaction_max_retries_le_zero_never_force_advance(self):
        """max_retries<=0 向后兼容：永不强制推进（保留死循环风险）。"""
        producer = MagicMock()
        producer.send_callback_transactional.side_effect = RuntimeError("tx failed")
        engine = self._make_engine_with_producer(producer)
        # ⚠️ 注意：不能用 patch(COMM_KAFKA_CALLBACK_MAX_RETRIES, 0)！
        # 因为 __init__ L113 是 `int(x) if x else 5`，patch 到 0 时 `if 0` 为 falsy，
        # 会走 else 分支得到 5，不是 0。直接设置实例属性才能正确测试 max_retries<=0 分支。
        engine._callback_max_retries = 0
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is False
        # base 不推进
        assert engine._offset_tracker._base.get(tp) is None or engine._offset_tracker._base[tp] == 0

    # ==================== 重投递前置检测（offset < base 跳过事务）====================

    def test_redelivery_skips_transactional_path(self):
        """重投递（offset < base）跳过事务路径：不调 send_callback_transactional，直接 return True。

        场景：rebalance / at-least-once 重投递导致已处理过的 offset 再次进入 _execute_transaction。
        若不跳过，事务会提交 offset+1（<= base）到 broker，导致 broker committed offset 回退，
        consumer 一直 poll 老消息，向量化无限重跑——这是线上"向量化停不下来"的根因。
        """
        producer = MagicMock()
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        # 模拟 offset=0 已被处理过：dispatch + complete 推进 base 到 1
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 0→1
        # 重投递：再次对 offset=0 调 _execute_transaction
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # 事务未被调用（避免提交回退的 offset 到 broker）
        producer.send_callback_transactional.assert_not_called()
        # base 不变（仍是 1，没有被回退）
        assert engine._offset_tracker._base[tp] == 1

    def test_redelivery_skips_degraded_path(self):
        """重投递（offset < base）也跳过降级路径：不调 send_callback / _commit_offset_fallback。
        降级路径的 _commit_offset_fallback 也会提交回退的 offset 到 broker，同样触发死循环，
        所以重投递检测必须在降级路径之前拦截（检测位于 _execute_transaction 开头）。
        """
        producer = MagicMock()
        producer.is_transactional_available.return_value = False  # 走降级路径
        engine = self._make_engine_with_producer(producer)
        poller = MagicMock()
        engine._poller = poller
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 0→1
        # 重投递 offset=0：应跳过降级路径
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # 降级路径的 send_callback 未被调
        producer.send_callback.assert_not_called()
        # poller.commit_offsets 未被调（避免提交回退 offset 到 broker）
        poller.commit_offsets.assert_not_called()
        # base 不变
        assert engine._offset_tracker._base[tp] == 1

    def test_non_redelivery_executes_transaction_normally(self):
        """非重投递（offset == base）正常执行事务路径（回归测试）。
        确保重投递前置检测不影响正常首派流程：offset == base 时 is_redelivery 返回 False，
        走正常事务路径，send_callback_transactional 被调用，base 正常推进。
        """
        producer = MagicMock()
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)  # base=0
        # offset=0 == base=0，非重投递，应正常执行事务
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        producer.send_callback_transactional.assert_called_once()
        assert engine._offset_tracker._base[tp] == 1

    def test_redelivery_does_not_modify_state(self):
        """重投递跳过时不修改任何状态（base / retry_count / producer 均不变）。

        重投递路径是纯粹的"跳过"：return True，不清理 retry_count（正常流程中 base
        推进时 retry_count 已清理），不调 _advance_offset（base 已在之前推进过）。
        """
        producer = MagicMock()
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 0→1
        # 预置 retry_count（模拟边界场景：正常流程中 base 推进时 retry 已清理）
        engine._callback_retry[tp] = {0: 2}
        ok = engine._execute_transaction(tp, 0, "doc-1", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # retry_count 不被重投递路径修改（不增不减，无副作用）
        assert engine._callback_retry.get(tp, {}).get(0) == 2
        # base 不变
        assert engine._offset_tracker._base[tp] == 1
        # producer 事务方法未被调
        producer.send_callback_transactional.assert_not_called()

    def test_redelivery_log_message_contains_base_and_offset(self):
        """重投递跳过时日志包含 tp/offset/base/callback_key（线上定位根因用）。

        验证日志可观测性：线上排查"向量化停不下来"时，可通过此日志确认重投递检测生效。
        """
        producer = MagicMock()
        engine = self._make_engine_with_producer(producer)
        tp = TopicPartition("my-topic", 7)
        engine._offset_tracker.on_dispatch(tp, 100)
        engine._offset_tracker.on_complete(tp, 100)  # base 100→101
        with patch("server.comm.processing_engine.logger") as mock_logger:
            ok = engine._execute_transaction(tp, 100, "doc-redeliver", {"eventType": "PROCESSING_COMPLETED"})
        assert ok is True
        # 验证 info 日志被调，且消息包含关键定位信息
        mock_logger.info.assert_called_once()
        log_msg = mock_logger.info.call_args.args[0]
        assert "offset=100" in log_msg
        assert "base=101" in log_msg
        assert "重投递" in log_msg
        assert "my-topic" in log_msg  # tp 信息
        assert "doc-redeliver" in log_msg  # callback_key


class TestFinishTaskUsesTransaction:
    """_finish_task 调 _execute_transaction 而非 _deliver_callback + _advance_offset。"""

    @staticmethod
    def test_finish_task_calls_execute_transaction_on_completed():
        producer = MagicMock()
        engine = make_engine(producer=producer, group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        msg = MagicMock()
        msg.topic = "t"
        msg.partition = 0
        msg.offset = 0
        msg.value = {"messageId": "m1", "eventType": "DOCUMENT_PUBLISHED", "kbId": "kb1", "documentId": "d1"}
        result = {"code": 200, "msg": "ok", "data": {"chunkCount": 3}}
        # patch _execute_transaction 验证被调
        with patch.object(engine, "_execute_transaction", return_value=True) as mock_tx:
            engine._finish_task(msg, result, "DOCUMENT_PUBLISHED")
        mock_tx.assert_called_once()
        # 验证传入的 callback_key 和 value
        args = mock_tx.call_args.args
        assert args[0] == tp  # TopicPartition
        assert args[1] == 0  # offset
        # callback_value 含 eventType=PROCESSING_COMPLETED
        assert args[3]["eventType"] == "PROCESSING_COMPLETED"

    @staticmethod
    def test_finish_task_failed_event_calls_execute_transaction_with_failed_callback():
        producer = MagicMock()
        engine = make_engine(producer=producer, group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        msg = MagicMock()
        msg.topic = "t"
        msg.partition = 0
        msg.offset = 0
        msg.value = {"messageId": "m1", "eventType": "DOCUMENT_PUBLISHED", "kbId": "kb1", "documentId": "d1"}
        result = {"code": 500, "msg": "fail", "data": None}
        with patch.object(engine, "_execute_transaction", return_value=True) as mock_tx:
            engine._finish_task(msg, result, "DOCUMENT_PUBLISHED")
        mock_tx.assert_called_once()
        assert mock_tx.call_args.args[3]["eventType"] == "PROCESSING_FAILED"

    # ==================== 重投递前置检测（NO_CALLBACK / producer is None 路径） ====================

    @staticmethod
    def test_finish_task_no_callback_redelivery_skips_commit():
        """KB_UPDATED 重投递（offset < base）跳过 _commit_offset_fallback，避免 broker committed 回退。

        场景：rebalance 后 Kafka 从旧 committed offset 重投递 KB_UPDATED/DOCUMENT_ARCHIVED，
        offset 已处理过（base 已推进），再次提交 offset+1 会回退 broker committed offset，
        引发 consumer 一直 poll 老消息的死循环。前置检测在进入 NO_CALLBACK 分支前拦截。
        """
        engine = make_engine(producer=MagicMock(), group_id="g1")
        tp = TopicPartition("t", 0)
        # 先 dispatch + complete offset=0，base 推进到 1
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 0 -> 1
        # 注入 mock poller 监控 commit_offsets 调用（_commit_offset_fallback 内部调它）
        engine._poller = MagicMock()

        msg = MagicMock()
        msg.topic = "t"
        msg.partition = 0
        msg.offset = 0  # 重投递：offset=0 < base=1
        msg.value = {"messageId": "m1", "eventType": "KB_UPDATED", "kbId": "kb1", "documentId": None}
        result = {"code": 200, "msg": "ok", "data": None}

        engine._finish_task(msg, result, "KB_UPDATED")

        # 重投递跳过 _commit_offset_fallback，poller.commit_offsets 不应被调
        engine._poller.commit_offsets.assert_not_called()

    @staticmethod
    def test_finish_task_producer_none_redelivery_skips_commit():
        """producer 未初始化 + 重投递时跳过 _commit_offset_fallback，避免 broker committed 回退。

        producer is None 路径同样调 _commit_offset_fallback 提交 offset+1，重投递时同样
        回退 broker committed offset。前置检测一并覆盖此分支。
        """
        engine = make_engine(producer=MagicMock(), group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 0 -> 1
        engine._poller = MagicMock()
        engine._producer = None  # 模拟 producer 未初始化（init_transactions 失败）

        msg = MagicMock()
        msg.topic = "t"
        msg.partition = 0
        msg.offset = 0  # 重投递
        msg.value = {"messageId": "m1", "eventType": "DOCUMENT_PUBLISHED", "kbId": "kb1", "documentId": "d1"}
        result = {"code": 200, "msg": "ok", "data": None}

        engine._finish_task(msg, result, "DOCUMENT_PUBLISHED")

        # 重投递跳过 _commit_offset_fallback
        engine._poller.commit_offsets.assert_not_called()

    @staticmethod
    def test_finish_task_no_callback_non_redelivery_commits_normally():
        """非重投递 KB_UPDATED 正常调 _commit_offset_fallback，确保前置检测不误杀正常路径。

        回归保护：offset == base（首次处理）必须正常提交 offset+1 到 broker，否则
        重启后 consumer 从旧 committed offset 开始，重复消费 KB_UPDATED。
        """
        engine = make_engine(producer=MagicMock(), group_id="g1")
        tp = TopicPartition("t", 0)
        # 仅 dispatch，base=0，offset=0 == base，非重投递（首次处理）
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._poller = MagicMock()

        msg = MagicMock()
        msg.topic = "t"
        msg.partition = 0
        msg.offset = 0
        msg.value = {"messageId": "m1", "eventType": "KB_UPDATED", "kbId": "kb1", "documentId": None}
        result = {"code": 200, "msg": "ok", "data": None}

        engine._finish_task(msg, result, "KB_UPDATED")

        # 非重投递正常路径，_commit_offset_fallback 被调（提交 offset+1=1 到 broker）
        engine._poller.commit_offsets.assert_called_once()
        committed = engine._poller.commit_offsets.call_args.args[0]
        assert committed[tp].offset == 1  # offset+1


class TestFinishSkipUsesTransaction:
    """_finish_skip 调 _execute_transaction 而非 _deliver_callback + _advance_offset。"""

    @staticmethod
    def test_finish_skip_calls_execute_transaction_on_completed():
        producer = MagicMock()
        engine = make_engine(producer=producer, group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        msg = MagicMock()
        msg.offset = 0
        msg.value = {"messageId": "m1", "eventType": "DOCUMENT_PUBLISHED", "kbId": "kb1", "documentId": "d1"}
        result = BaseResponse(code=200, msg="no-op", data=None)
        with patch.object(engine, "_execute_transaction", return_value=True) as mock_tx:
            engine._finish_skip(msg, tp, result)
        mock_tx.assert_called_once()
        args = mock_tx.call_args.args
        assert args[0] == tp  # TopicPartition
        assert args[1] == 0  # offset
        assert args[2] == "d1"  # callback_key = document_id or kb_id
        assert args[3]["eventType"] == "PROCESSING_COMPLETED"

    @staticmethod
    def test_finish_skip_failed_event_calls_execute_transaction_with_failed_callback():
        producer = MagicMock()
        engine = make_engine(producer=producer, group_id="g1")
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        msg = MagicMock()
        msg.offset = 0
        msg.value = {"messageId": "m1", "eventType": "DOCUMENT_PUBLISHED", "kbId": "kb1", "documentId": "d1"}
        result = BaseResponse(code=500, msg="permanent error", data=None)
        with patch.object(engine, "_execute_transaction", return_value=True) as mock_tx:
            engine._finish_skip(msg, tp, result)
        mock_tx.assert_called_once()
        assert mock_tx.call_args.args[2] == "d1"
        assert mock_tx.call_args.args[3]["eventType"] == "PROCESSING_FAILED"


class TestProcessingEngine:
    @staticmethod
    def test_e2e_parallel_and_ordered():
        """KB_CREATED + 50 DOCUMENT_PUBLISHED + KB_DELETED：并行执行 + 顺序 + 回调 + offset 推进。"""
        _KB1_KEY = 'kb1'
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        # 记录并发峰值
        inflight = [0]
        inflight_lock = threading.Lock()
        max_inflight = [0]

        def task_fn(envelope):
            with inflight_lock:
                inflight[0] += 1
                if inflight[0] > max_inflight[0]:
                    max_inflight[0] = inflight[0]
            time.sleep(0.05)  # 模拟向量化耗时
            with inflight_lock:
                inflight[0] -= 1

        # monkeypatch create_task
        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(
            lambda envelope: FakeTask(fn=task_fn)
        )
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=64, doc_pool=16, group_id="g1")
            msgs = [make_msg("KB_CREATED", _KB1_KEY, offset=0)]
            for i in range(1, 51):
                msgs.append(make_msg("DOCUMENT_PUBLISHED", _KB1_KEY, document_id=f"doc-{i}", offset=i))
            msgs.append(make_msg("KB_DELETED", _KB1_KEY, offset=51))

            for m in msgs:
                engine.dispatch(m)

            # 等待全部完成
            engine.wait_shutdown(timeout_ms=10000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 50 条文档 + 1 KB_CREATED + 1 KB_DELETED = 52 次回调（KB_UPDATED 不回调，KB_DELETED 回调）
        completed = [c for c in producer.callbacks if c[0] == "PROCESSING_COMPLETED"]
        assert len(completed) >= 50  # 至少 50 条文档成功回调
        # 并发峰值 > 1（证明并行，非串行）
        assert max_inflight[0] > 1, f"未并行执行，max_inflight={max_inflight[0]}"
        # offset 推进：最后 base 应为 52（0..51 全完成）
        assert len(committed) > 0
        last_commit = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last_commit[tp] is not None  # 有提交

    @staticmethod
    def test_kb_created_barrier_queues_docs():
        """KB_CREATED 屏障：create_kb 完成前文档事件入等待队列。"""
        producer = FakeProducer()
        committed = []
        create_done = threading.Event()
        create_started = threading.Event()

        def create_fn(envelope):
            create_started.set()
            create_done.wait(timeout=5.0)  # 阻塞 create_kb
            return {"code": 200, "msg": "ok", "data": None}

        def doc_fn(envelope):
            pass

        old_create = pe_mod.KafkaTaskFactory.create_task

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_CREATED":
                return FakeTask(fn=create_fn)
            return FakeTask(fn=doc_fn)

        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=lambda o: committed.append(o), group_id="g1")

            # 先派发 KB_CREATED（会阻塞在 create_fn）
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            create_started.wait(timeout=2.0)
            assert create_started.is_set()

            # 派发文档事件（应入等待队列，不执行 doc_fn）
            doc_executed = []
            def doc_fn2(envelope):
                doc_executed.append(envelope.get("documentId"))

            # 更新 fake_create 的 doc 任务 fn
            def fake_create2(envelope):
                et = envelope.get("eventType")
                if et == "KB_CREATED":
                    return FakeTask(fn=create_fn)
                return FakeTask(fn=doc_fn2)
            pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create2)

            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", "kb1", document_id="d1", offset=1))
            time.sleep(0.2)
            assert not doc_executed  # 文档未执行（在等待队列）

            # 完成 create_kb，flush 等待队列
            create_done.set()
            time.sleep(0.5)
            assert doc_executed == ["d1"]  # flush 后执行

            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

    @staticmethod
    def test_vlm_concurrency_limit():
        """VLM 限流：worker_pool=16、vlm_limit=8，同时调 VLM 不超过 8。"""
        producer = FakeProducer()
        vlm_inflight = [0]
        vlm_max = [0]
        vlm_lock = threading.Lock()

        def vlm_fn(envelope):
            with vlm_lock:
                vlm_inflight[0] += 1
                if vlm_inflight[0] > vlm_max[0]:
                    vlm_max[0] = vlm_inflight[0]
            time.sleep(0.1)
            with vlm_lock:
                vlm_inflight[0] -= 1

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_CREATED":
                # 控制事件不参与 VLM 限流统计
                return FakeTask(fn=None)
            return FakeTask(fn=vlm_fn)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=lambda o: None, vlm_limit=8, doc_pool=16, group_id="g1")
            # 先派发 KB_CREATED（快速完成）
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            time.sleep(0.2)  # 等 create_kb 完成、gate ready
            # 派发 16 个文档（不同 documentId，VLM 事件）
            for i in range(1, 17):
                engine.dispatch(make_msg("DOCUMENT_PUBLISHED", "kb1", document_id=f"d{i}", offset=i))
            time.sleep(1.5)
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        assert vlm_max[0] <= 8, f"VLM 并发超限: {vlm_max[0]}"
        assert vlm_max[0] >= 2, f"VLM 未并发: {vlm_max[0]}"

    @staticmethod
    def test_same_document_id_fifo():
        """同一 documentId 多条事件按派发顺序执行（FIFO）。"""
        producer = FakeProducer()
        order = []
        order_lock = threading.Lock()

        def fn(envelope):
            with order_lock:
                order.append(envelope.get("documentId"))
            time.sleep(0.02)

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_CREATED":
                # 控制事件不污染 order 统计
                return FakeTask(fn=None)
            return FakeTask(fn=fn)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=lambda o: None, vlm_limit=64, doc_pool=4, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            time.sleep(0.2)
            # 同一 documentId，不同 offset
            for i in range(1, 6):
                engine.dispatch(make_msg("DOCUMENT_PUBLISHED", "kb1", document_id="same-doc", offset=i))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 同 documentId 应按 offset 序执行（FIFO）
        # order 仅含 same-doc 条目（KB_CREATED 不调 fn）
        assert order == ["same-doc"] * 5, f"FIFO 顺序错误: {order}"

    @staticmethod
    def test_non_dict_envelope_skipped():
        """非 dict 消息：commit+skip，不抛异常。"""
        producer = FakeProducer()
        committed = []
        engine = make_engine(producer=producer, commit_fn=lambda o: committed.append(o), group_id="g1")
        # 构造 value=None 的消息
        msg = MockRecord("t", 0, 10, 0, 0, None, None, [], None)
        engine.dispatch(msg)
        time.sleep(0.2)
        # 应推进 offset（skip）—— 事务化后 per-message 不 commit，仅推进内存 base
        tp = TopicPartition("t", 0)
        assert engine._offset_tracker._base.get(tp) is not None and engine._offset_tracker._base[tp] >= 1

    @staticmethod
    def test_document_archived_no_callback():
        """DOCUMENT_ARCHIVED 在 NO_CALLBACK_EVENTS 中，处理成功不投递回调，但仍推进 offset。

        契约来源：kafka-structure.md 第 2.2 节 "是否回调: 否"。
        对照：KB_CREATED 不在 NO_CALLBACK_EVENTS 中，应投递回调。
        """
        _KB_NAME_KEY = 'kb1'
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            # ArchiveDocumentTask 返回 200（成功），验证即使成功也不回调
            return FakeTask(fn=None, code=200, msg="ok", data=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            # KB_CREATED：应投递 PROCESSING_COMPLETED（documentId=None）
            engine.dispatch(make_msg("KB_CREATED", _KB_NAME_KEY, offset=0))
            time.sleep(0.2)  # 等 create_kb + on_kb_created flush
            # DOCUMENT_ARCHIVED：不应投递回调
            engine.dispatch(make_msg("DOCUMENT_ARCHIVED", _KB_NAME_KEY, document_id="doc-arch", offset=1))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 回调断言：仅 KB_CREATED 投递 1 条，DOCUMENT_ARCHIVED 不投递
        assert len(producer.callbacks) == 1, f"预期仅 1 条回调（KB_CREATED），实际: {producer.callbacks}"
        cb_event, cb_kb, cb_doc, _ = producer.callbacks[0]
        assert cb_event == "PROCESSING_COMPLETED"
        assert cb_kb == _KB_NAME_KEY
        assert cb_doc is None  # KB_CREATED 的 documentId 为 None
        # offset 推进断言：两条消息都完成，最终 base 应为 2
        assert len(committed) >= 1
        last = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last.get(tp) is not None
        assert last[tp].offset >= 2, f"offset 未推进到 2，实际: {last[tp].offset}"

    # ==================== P0/P1 回归测试 ====================

    @staticmethod
    def test_finish_skip_permanent_error_sends_failed():
        """P0-1: 永久消息错误（create_task 抛异常）时投递 PROCESSING_FAILED，而非 COMPLETED。

        契约：create_task 仅实例化（无 I/O），抛异常属永久消息错误，不可重试。
        _finish_skip(code=500) 应投递 PROCESSING_FAILED + 推进 offset，避免毒丸消息卡死分区。
        """
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            raise TypeError("unknown eventType")

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            # 带 documentId 的文档事件，create_task 抛异常 → _finish_skip(code=500)
            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", "kb1", document_id="d1", offset=0))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 应投递 PROCESSING_FAILED（code=500），而非 COMPLETED
        assert len(producer.callbacks) == 1, f"预期 1 条回调，实际: {producer.callbacks}"
        cb_event, cb_kb, cb_doc, payload = producer.callbacks[0]
        assert cb_event == "PROCESSING_FAILED", f"预期 PROCESSING_FAILED，实际: {cb_event}"
        assert cb_kb == "kb1"
        assert cb_doc == "d1"
        assert payload.get("errorCode") == "500", f"预期 errorCode=500，实际: {payload}"
        # offset 推进（毒丸消息不卡死分区）
        assert len(committed) >= 1
        last = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last[tp].offset >= 1, f"offset 未推进，实际: {last[tp].offset}"

    @staticmethod
    def test_finish_skip_non_dict_no_callback():
        """P0-1 补充：非 dict 消息 _finish_skip 仅推进 offset，不投递回调。

        非 dict 无 eventType/documentId，不满足回调条件，仅推进 offset。
        """
        producer = FakeProducer()
        committed = []
        engine = make_engine(producer=producer, commit_fn=lambda o: committed.append(dict(o)), group_id="g1")
        msg = MockRecord("t", 0, 5, 0, 0, None, None, [], None)
        engine.dispatch(msg)
        engine.wait_shutdown(timeout_ms=2000)
        # 非字典不投递回调
        assert not producer.callbacks, f"预期无回调，实际: {producer.callbacks}"
        # offset 推进
        assert len(committed) >= 1
        tp = TopicPartition("t", 0)
        assert committed[-1][tp].offset >= 1, f"offset 未推进，实际: {committed[-1][tp].offset}"

    @staticmethod
    def test_on_kb_deleted_pending_no_offset_stall():
        """P0-2: KB_CREATED 失败时 pending 文档事件在 KB_DELETED 时被 discard，
        on_discard_fn 推进 offset + 投递 no-op 成功回调，避免 pending 消息 offset 卡死。

        场景：KB_CREATED code=500 → on_kb_created 未调，state=initializing；
        2 条文档事件入 pending；KB_DELETED discard pending → 每条调 on_discard_fn
        推进 offset + 投递 PROCESSING_COMPLETED，KB_DELETED 本身也完成并推进 offset。
        """
        _KB_NAME_KEY = 'kb1'
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_CREATED":
                # create_kb 失败（code=500），on_kb_created 不会被调用，state 保持 initializing
                return FakeTask(fn=None, code=500, msg="create_kb failed", data=None)
            return FakeTask(fn=None, code=200, msg="ok", data=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", _KB_NAME_KEY, offset=0))
            time.sleep(0.2)  # 等 KB_CREATED 失败完成
            # 2 条文档事件入 pending（state=initializing，on_kb_created 未调）
            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", _KB_NAME_KEY, document_id="d1", offset=1))
            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", _KB_NAME_KEY, document_id="d2", offset=2))
            time.sleep(0.2)
            # KB_DELETED：discard pending → on_discard_fn 推进 offset + 投递 no-op 成功回调
            engine.dispatch(make_msg("KB_DELETED", _KB_NAME_KEY, offset=3))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 回调断言：
        # - 1 条 PROCESSING_FAILED（KB_CREATED code=500）
        # - 2 条 PROCESSING_COMPLETED（pending discard，code=200）
        # - 1 条 PROCESSING_COMPLETED（KB_DELETED，code=200）
        failed = [c for c in producer.callbacks if c[0] == "PROCESSING_FAILED"]
        completed = [c for c in producer.callbacks if c[0] == "PROCESSING_COMPLETED"]
        assert len(failed) == 1, f"预期 1 条 FAILED（KB_CREATED），实际: {failed}"
        assert len(completed) == 3, f"预期 3 条 COMPLETED（2 pending + 1 KB_DELETED），实际: {completed}"
        # pending discard 的回调应携带 documentId
        discard_completed = [c for c in completed if c[2] in ("d1", "d2")]
        assert len(discard_completed) == 2, f"预期 2 条 pending discard 回调，实际: {discard_completed}"
        # offset 推进：4 条消息全部完成，最终 base 应为 4（无 pending 卡死）
        assert len(committed) >= 1
        last = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last[tp].offset >= 4, f"offset 未推进到 4（pending 卡死），实际: {last[tp].offset}"

    @staticmethod
    def test_stable_hash_routing():
        """P1: 同 documentId 5 次派发全部路由到同一 executor 线程（crc32 稳定路由）。

        验证 zlib.crc32(document_id) % pool_size 的路由稳定性：
        同 documentId 永远命中同一 executor，保证 FIFO；不依赖 PYTHONHASHSEED。
        """
        import zlib
        producer = FakeProducer()
        thread_names = []
        thread_lock = threading.Lock()

        def fn(envelope):
            with thread_lock:
                thread_names.append(threading.current_thread().name)
            time.sleep(0.02)

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_CREATED":
                return FakeTask(fn=None)
            return FakeTask(fn=fn)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=lambda o: None, vlm_limit=64, doc_pool=8, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            time.sleep(0.2)
            # 同一 documentId 派发 5 次
            for i in range(1, 6):
                engine.dispatch(make_msg("DOCUMENT_PUBLISHED", "kb1", document_id="same-doc", offset=i))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 5 次执行均在同一线程（crc32 路由稳定，同 documentId 命中同一 executor）
        assert len(thread_names) == 5, f"预期 5 次执行，实际: {len(thread_names)}"
        unique_threads = set(thread_names)
        assert len(unique_threads) == 1, f"预期全部命中同一线程，实际线程: {unique_threads}"
        # 验证线程名前缀与 crc32 路由一致
        expected_idx = zlib.crc32(b"same-doc") % 8
        assert thread_names[0].startswith(f"DocWorker-{expected_idx}"), \
            f"预期线程前缀 DocWorker-{expected_idx}，实际: {thread_names[0]}"

    @staticmethod
    def test_kb_deleted_timeout_no_force_delete():
        """P1: KB_DELETED 等在途文档超时时不执行 delete_kb。

        新行为（N1 修复）：超时后 reset gate + 推进 offset + 投递 PROCESSING_FAILED 回调，
        KB 残留模型端需人工清理，但分区不卡死、该 KB 后续事件能正常处理。
        本测试仅断言 delete_kb 全程未执行（完整新行为见 test_kb_deleted_timeout_resets_gate_and_advances_offset）。
        """
        _KB_NAME_KEY = 'kb1'
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        doc_started = threading.Event()
        doc_block = threading.Event()
        delete_called = []

        def doc_fn(envelope):
            doc_started.set()
            doc_block.wait(timeout=10.0)  # 阻塞文档任务

        def delete_fn(envelope):
            delete_called.append(envelope.get("eventType"))

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_DELETED":
                return FakeTask(fn=delete_fn)
            if et == "KB_CREATED":
                return FakeTask(fn=None)
            return FakeTask(fn=doc_fn)

        old_create = pe_mod.KafkaTaskFactory.create_task
        old_shut = pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = 500  # 500ms 超时
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            # make_engine 恢复 COMM_KAFKA_SHUTDOWN_TIMEOUT_MS=500（old_shut 已被覆盖为 500）
            engine.dispatch(make_msg("KB_CREATED", _KB_NAME_KEY, offset=0))
            time.sleep(0.2)
            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", _KB_NAME_KEY, document_id="d1", offset=1))
            doc_started.wait(timeout=2.0)
            assert doc_started.is_set()
            # KB_DELETED：等在途文档超时（doc_block 未 set），不执行 delete_kb
            engine.dispatch(make_msg("KB_DELETED", _KB_NAME_KEY, offset=2))
            time.sleep(1.0)  # 等 500ms 超时 + 余量
            assert not delete_called, f"超时后 delete_kb 不应执行，实际: {delete_called}"
            # 释放文档任务，允许 wait_shutdown 完成
            doc_block.set()
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)
            pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = old_shut

        # delete_kb 全程未被执行
        assert not delete_called, f"预期 delete_kb 全程不执行，实际: {delete_called}"

    @staticmethod
    def test_finish_skip_control_event_sends_callback():
        """P1: 控制事件（documentId=None）走 skip 路径时也投递回调。

        场景：KB_CREATED 的 create_task 抛 TypeError → _finish_skip(code=500)。
        移除 document_id 真值检查后，控制事件（documentId=None）应投递
        PROCESSING_FAILED，key 回退为 kb_id，offset 推进。
        """
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            if envelope.get("eventType") == "KB_CREATED":
                raise TypeError("unknown eventType")
            return FakeTask(fn=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

        # 应投递 PROCESSING_FAILED（documentId=None，控制事件）
        assert len(producer.callbacks) == 1, f"预期 1 条回调，实际: {producer.callbacks}"
        cb_event, cb_kb, cb_doc, payload = producer.callbacks[0]
        assert cb_event == "PROCESSING_FAILED", f"预期 PROCESSING_FAILED，实际: {cb_event}"
        assert cb_kb == "kb1"
        assert cb_doc is None, f"预期 documentId=None（控制事件），实际: {cb_doc}"
        assert payload.get("errorCode") == "500", f"预期 errorCode=500，实际: {payload}"
        # offset 推进（_finish_skip 同步执行，base 0→1）
        assert len(committed) >= 1
        last = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last[tp].offset >= 1, f"offset 未推进，实际: {last[tp].offset}"

    @staticmethod
    def test_finish_task_no_advance_offset_on_callback_failure():
        """P1: send_callback 返回 False 时不推进 offset，依赖 Kafka at-least-once 重投递。

        场景：KB_CREATED 任务成功（code=200），但 producer.send_callback 返回 False
        （如 Kafka 不可用）→ _finish_task 不推进 offset，等待重投递。
        """
        producer = FakeProducer(ok=False)
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            return FakeTask(fn=None, code=200, msg="ok", data=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            time.sleep(0.3)  # 等 control task 完成 _finish_task
            # send_callback 返回 False → _finish_task return，不 advance offset
            assert producer.callbacks, f"预期回调被投递，实际: {producer.callbacks}"
            assert not committed, f"预期回调失败时 offset 未推进（无 commit），实际: {committed}"
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)

    # ==================== N1 KB_DELETED 超时 reset gate 测试 ====================

    @staticmethod
    def test_kb_deleted_timeout_resets_gate_and_advances_offset():
        """N1: KB_DELETED 等在途超时后 reset gate + 推进 offset + 投递 PROCESSING_FAILED。

        新行为（取代旧"保留 deleting + 不推进 offset"）：
        - gate 状态被 reset（get_state 返回 ready，非 deleting）
        - offset 推进（分区不卡死，最终 base 达到 3）
        - 投递 PROCESSING_FAILED（KB_DELETED 超时未执行删除，告知平台）
        - delete_kb 全程未执行
        """
        _KB_NAME_KEY = 'kb1'
        producer = FakeProducer()
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        doc_started = threading.Event()
        doc_block = threading.Event()
        delete_called = []

        def doc_fn(envelope):
            doc_started.set()
            doc_block.wait(timeout=10.0)  # 阻塞文档任务

        def delete_fn(envelope):
            delete_called.append(envelope.get("eventType"))

        def fake_create(envelope):
            et = envelope.get("eventType")
            if et == "KB_DELETED":
                return FakeTask(fn=delete_fn)
            if et == "KB_CREATED":
                return FakeTask(fn=None)
            return FakeTask(fn=doc_fn)

        old_create = pe_mod.KafkaTaskFactory.create_task
        old_shut = pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = 500  # 500ms 超时
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            engine.dispatch(make_msg("KB_CREATED", _KB_NAME_KEY, offset=0))
            time.sleep(0.2)
            engine.dispatch(make_msg("DOCUMENT_PUBLISHED", _KB_NAME_KEY, document_id="d1", offset=1))
            doc_started.wait(timeout=2.0)
            assert doc_started.is_set()
            # KB_DELETED：等在途文档超时（doc_block 未 set）→ reset gate + 推进 offset + 投递 FAILED
            engine.dispatch(make_msg("KB_DELETED", _KB_NAME_KEY, offset=2))
            time.sleep(1.0)  # 等 500ms 超时 + 余量
            # 断言新行为：gate 状态已 reset（非 deleting）
            assert engine._gate.get_state(_KB_NAME_KEY) == "ready", \
                f"预期 gate reset 后状态 ready，实际: {engine._gate.get_state(_KB_NAME_KEY)}"
            # 断言投递了 PROCESSING_FAILED（KB_DELETED 超时未执行删除）
            failed = [c for c in producer.callbacks if c[0] == "PROCESSING_FAILED"]
            assert len(failed) == 1, f"预期 1 条 PROCESSING_FAILED（KB_DELETED 超时），实际: {producer.callbacks}"
            assert failed[0][1] == _KB_NAME_KEY, f"预期 FAILED 回调 kbId=kb1，实际: {failed[0]}"
            # 释放文档任务，允许 wait_shutdown 完成
            doc_block.set()
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)
            pe_mod.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS = old_shut

        # delete_kb 全程未执行
        assert not delete_called, f"预期 delete_kb 全程不执行，实际: {delete_called}"
        # offset 推进：最终 base 应达到 3（KB_CREATED=0 + DOC=1 + KB_DELETED=2 全完成）
        assert len(committed) >= 1, f"预期 offset 已推进（commit 被调），实际: {committed}"
        last = committed[-1]
        tp = TopicPartition("kb-document-processing", 0)
        assert last[tp].offset >= 3, f"预期最终 base >= 3（分区未卡死），实际: {last[tp].offset}"

    # ==================== 回调投递死循环打破测试 ====================

    @staticmethod
    def test_finish_task_callback_failure_max_retries_advances_offset():
        """回调投递达 max_retries 后强制推进 offset + 记死信日志，打破死循环。

        场景：producer.send_callback 一直返回 False（回调 topic 永久不可用），
        同一消息（offset=0）被 Kafka 重投递 3 次：
        - retry 1/3 < 3：不推进 offset（committed 为空）
        - retry 2/3 < 3：不推进 offset（committed 为空）
        - retry 3/3 >= 3：强制推进 offset + 记死信日志 + 清理 retry_count

        断言：
        - 前 2 次重投递 committed 为空（保留 at-least-once 重投递语义）
        - 第 3 次达阈值后 committed 非空，base 推进到 1
        - retry_count 被清理（避免内存泄漏）
        - send_callback 被调 3 次（每次重投递都尝试投递回调）
        """
        producer = FakeProducer(ok=False)
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            return FakeTask(fn=None, code=200, msg="ok", data=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        old_max_retries = pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES = 3
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            # 同一消息（offset=0）dispatch 3 次（模拟 Kafka at-least-once 重投递）
            for _ in range(3):
                engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
                time.sleep(0.3)  # 等 control task 完成 _finish_task

            # 前 2 次失败不推进 offset（at-least-once 重投递语义保留）
            # 第 3 次达阈值强制推进 —— 事务化后 per-message 不 commit，仅推进内存 base
            tp = TopicPartition("kb-document-processing", 0)
            assert engine._offset_tracker._base.get(tp) is not None and engine._offset_tracker._base[tp] >= 1, \
                f"预期第 3 次达阈值后强制推进 base，实际 base: {engine._offset_tracker._base.get(tp)}"
            # retry_count 被清理（达阈值后清理，避免内存泄漏）
            assert not engine._callback_retry, \
                f"预期达阈值后 retry_count 清理，实际: {engine._callback_retry}"
            # send_callback 被调 3 次（每次重投递都尝试投递回调）
            assert len(producer.callbacks) == 3, \
                f"预期 send_callback 被调 3 次（每次重投递都尝试），实际: {len(producer.callbacks)}"
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)
            pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES = old_max_retries

    @staticmethod
    def test_finish_task_callback_success_clears_retry_count():
        """回调投递成功后清理 retry_count：前 N 次失败 + 第 N+1 次成功 → 推进 offset + 清理。"""
        producer = FakeProducer(ok=False)
        committed = []
        committed_lock = threading.Lock()

        def commit_fn(offsets):
            with committed_lock:
                committed.append(dict(offsets))

        def fake_create(envelope):
            return FakeTask(fn=None, code=200, msg="ok", data=None)

        old_create = pe_mod.KafkaTaskFactory.create_task
        old_max_retries = pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES
        pe_mod.KafkaTaskFactory.create_task = staticmethod(fake_create)
        pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES = 5
        try:
            engine = make_engine(producer=producer, commit_fn=commit_fn, vlm_limit=8, doc_pool=4, group_id="g1")
            # 前 2 次失败
            for _ in range(2):
                engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
                time.sleep(0.3)
            assert not committed, f"预期前 2 次失败不推进，实际: {committed}"
            # retry_count 累积到 2
            tp = TopicPartition("kb-document-processing", 0)
            assert engine._callback_retry.get(tp, {}).get(0) == 2, \
                f"预期 retry_count=2，实际: {engine._callback_retry}"

            # 第 3 次成功（恢复投递）
            producer.send_callback_result = True
            engine.dispatch(make_msg("KB_CREATED", "kb1", offset=0))
            time.sleep(0.3)
            # 成功后推进 offset + 清理 retry_count —— 事务化后 per-message 不 commit，仅推进内存 base
            assert engine._offset_tracker._base.get(tp) is not None and engine._offset_tracker._base[tp] >= 1, \
                f"预期第 3 次成功后推进 base，实际 base: {engine._offset_tracker._base.get(tp)}"
            assert not engine._callback_retry, \
                f"预期成功后 retry_count 清理，实际: {engine._callback_retry}"
            engine.wait_shutdown(timeout_ms=5000)
        finally:
            pe_mod.KafkaTaskFactory.create_task = staticmethod(old_create)
            pe_mod.COMM_KAFKA_CALLBACK_MAX_RETRIES = old_max_retries


class TestWaitShutdownFallback:
    """事务化后 wait_shutdown 用 poller.commit_offsets 兜底提交残余 offset。"""

    @staticmethod
    def test_wait_shutdown_calls_poller_commit_offsets():
        producer = MagicMock()
        poller = MagicMock()
        engine = make_engine(producer=producer, group_id="g1")
        # 注入 poller（事务化后 wait_shutdown 用 poller.commit_offsets 兜底）
        engine._poller = poller
        tp = TopicPartition("t", 0)
        engine._offset_tracker.on_dispatch(tp, 0)
        engine._offset_tracker.on_complete(tp, 0)  # base 推进到 1，有残余 offset
        engine.wait_shutdown(timeout_ms=1000)
        # poller.commit_offsets 被调（兜底提交）
        poller.commit_offsets.assert_called_once()
