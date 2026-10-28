#!/usr/bin/env python3
"""
server.comm.kafka_producer 单元测试。

覆盖 KafkaCallbackProducer:
- send_callback: 投递回调信封,断言 envelope 形状 / key 选择 / 异常降级
- _init_producer: 无 bootstrap_servers / sasl_ssl 注入 / 构造异常兜底
- close: _producer 为 None / flush+close 异常降级 / 状态重置

所有用例均使用 mock,不连真实 Kafka。logger 用 patch 拦截(loguru 不走标准 logging)。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

# 将项目根目录加入 sys.path,保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest
from kafka.structs import OffsetAndMetadata

from server.comm.kafka_producer import KafkaCallbackProducer

# ==================== send_callback 测试 ====================

class TestSendCallback:
    @staticmethod
    def test_send_callback_returns_false_when_not_initialized():
        # _ensure_producer 失败（KafkaProducer 构造抛异常）→ 返回 False
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger") as mock_logger:
            KP.side_effect = RuntimeError("broker down")
            ok = p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        assert ok is False
        # 未初始化 → WARNING 日志
        assert mock_logger.warning.called

    @staticmethod
    def _make_ready_producer(config=None):
        """实例化后直接绕过 _ensure_producer,设置 _producer / _initialized。"""
        p = KafkaCallbackProducer(config=config or {"bootstrap_servers": "mock:9092"})
        p._producer = MagicMock()
        p._initialized = True
        return p

    def test_key_falls_back_to_kb_id_when_document_id_missing(self):
        p = self._make_ready_producer()
        # document_id 为空字符串 → 回退到 kb_id
        p.send_callback("PROCESSING_COMPLETED", "kb-1", "", {})
        _, kwargs = p._producer.send.call_args
        assert kwargs["key"] == "kb-1"

    def test_key_is_document_id_when_present(self):
        p = self._make_ready_producer()
        p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        _, kwargs = p._producer.send.call_args
        assert kwargs["key"] == "doc-1"

    def test_key_none_when_both_missing(self):
        p = self._make_ready_producer()
        # 两者都为空 → document_id or kb_id = "" or "" = ""
        p.send_callback("PROCESSING_FAILED", "", "", {})
        _, kwargs = p._producer.send.call_args
        assert kwargs["key"] == ""

    def test_payload_none_normalized_to_empty_dict(self):
        p = self._make_ready_producer()
        p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", None)
        _, kwargs = p._producer.send.call_args
        assert kwargs["value"]["payload"] == {}

    def test_send_callback_returns_false_on_send_exception(self):
        p = self._make_ready_producer()
        p._producer.send.side_effect = RuntimeError("kafka broker down")
        with patch("server.comm.kafka_producer.logger"):
            ok = p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        # send 抛异常 → 不 re-raise，返回 False
        assert ok is False

    def test_send_callback_returns_true_on_success(self):
        p = self._make_ready_producer()
        ok = p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        assert ok is True
        assert p._producer.send.called

    def test_send_envelope_shape(self):
        p = self._make_ready_producer()
        p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {"chunkCount": 3})
        assert p._producer.send.called
        args, kwargs = p._producer.send.call_args
        # send(topic, key=key, value=envelope) 位置参数: topic; key/value 走 kwargs
        topic = args[0]
        envelope = kwargs["value"]
        assert topic == p.topic
        assert envelope["eventType"] == "PROCESSING_COMPLETED"
        assert envelope["kbId"] == "kb-1"
        assert envelope["documentId"] == "doc-1"
        assert envelope["payload"] == {"chunkCount": 3}
        assert "messageId" in envelope
        assert "timestamp" in envelope

    def test_send_raises_logs_error_and_swallows(self):
        p = self._make_ready_producer()
        p._producer.send.side_effect = RuntimeError("kafka broker down")
        with patch("server.comm.kafka_producer.logger") as mock_logger:
            # 不抛异常
            p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        # send 异常 → log error,不抛
        assert mock_logger.error.called
        err_call_args = mock_logger.error.call_args.args[0]
        assert "Kafka 回调投递失败" in err_call_args

    def test_send_success_logs_info(self):
        p = self._make_ready_producer()
        with patch("server.comm.kafka_producer.logger") as mock_logger:
            p.send_callback("PROCESSING_COMPLETED", "kb-1", "doc-1", {})
        # send 成功 → log info
        assert mock_logger.info.called
        info_call_args = mock_logger.info.call_args.args[0]
        assert "Kafka 回调已投递" in info_call_args


# ==================== _init_producer 测试 ====================

class TestInitProducer:
    @staticmethod
    def test_constructor_raises_sets_producer_none():
        cfg = {"bootstrap_servers": "kafka:9092"}
        p = KafkaCallbackProducer(config=cfg)
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger") as mock_logger:
            KP.side_effect = RuntimeError("connect failed")
            p._init_producer()
        # 构造异常 → _producer=None,log error
        assert p._producer is None
        assert mock_logger.error.called

    @staticmethod
    def test_default_serializer_present():
        cfg = {"bootstrap_servers": "kafka:9092"}
        p = KafkaCallbackProducer(config=cfg)
        with patch("server.comm.kafka_producer.KafkaProducer") as KP:
            p._init_producer()
        kwargs = KP.call_args.kwargs
        assert "value_serializer" in kwargs
        assert "key_serializer" in kwargs
        assert kwargs["retries"] == 3

    @staticmethod
    def test_empty_bootstrap_servers():
        p = KafkaCallbackProducer(config={"bootstrap_servers": ""})
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger") as mock_logger:
            p._init_producer()
        KP.assert_not_called()
        assert mock_logger.warning.called
        assert p._producer is None

    @staticmethod
    def test_no_bootstrap_servers_empty_config():
        # config={} 为 falsy → self.config 走 COMM_KAFKA_ADDRESS;
        # 需在构造前 patch COMM_KAFKA_ADDRESS={},使 self.config 最终为 {}
        with patch("server.comm.kafka_producer.COMM_KAFKA_ADDRESS", {}), \
             patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger") as mock_logger:
            p = KafkaCallbackProducer(config={})
            p._init_producer()
        # 无 bootstrap_servers → 仅 warning,不创建 KafkaProducer
        KP.assert_not_called()
        assert mock_logger.warning.called
        assert p._producer is None

    @staticmethod
    def test_sasl_ssl_defaults():
        cfg = {
            "bootstrap_servers": "kafka:9092",
            # 非空 dict 才进入 sasl 分支;不提供具体键以验证默认值
            "sasl_ssl": {"enabled": True},
        }
        p = KafkaCallbackProducer(config=cfg)
        with patch("server.comm.kafka_producer.KafkaProducer") as KP:
            p._init_producer()
        kwargs = KP.call_args.kwargs
        assert kwargs["security_protocol"] == "SASL_SSL"
        assert kwargs["sasl_mechanism"] == "PLAIN"
        assert kwargs["sasl_plain_username"] == ""
        assert kwargs["sasl_plain_password"] == ""

    @staticmethod
    def test_sasl_ssl_injected():
        cfg = {
            "bootstrap_servers": "kafka:9092",
            "sasl_ssl": {
                "security_protocol": "SASL_SSL",
                "mechanism": "SCRAM-SHA-512",
                "username": "u",
                "password": "p",
            },
        }
        p = KafkaCallbackProducer(config=cfg)
        # spec=object 让 mock 不带 init_transactions 属性(模拟不支持事务 API 的客户端),
        # 避免 _init_producer 进入事务化分支再调一次 KafkaProducer 导致 assert_called_once 失败;
        # 用 object 而非 [] 是因为空 spec 会让 mock 变 NonCallable 导致 KafkaProducer() 调用失败
        with patch("server.comm.kafka_producer.KafkaProducer", spec=object) as KP:
            p._init_producer()
        KP.assert_called_once()
        kwargs = KP.call_args.kwargs
        assert kwargs["bootstrap_servers"] == "kafka:9092"
        assert kwargs["security_protocol"] == "SASL_SSL"
        assert kwargs["sasl_mechanism"] == "SCRAM-SHA-512"
        assert kwargs["sasl_plain_username"] == "u"
        assert kwargs["sasl_plain_password"] == "p"
        # 成功路径下 _producer 为 mock
        assert p._producer is KP.return_value


# ==================== close 测试 ====================

class TestClose:
    @staticmethod
    def test_close_close_only_exception_degrades():
        # flush 正常,close 抛异常
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        p._producer = MagicMock()
        p._initialized = True
        p._producer.close.side_effect = RuntimeError("close boom")
        with patch("server.comm.kafka_producer.logger") as mock_logger:
            p.close()
        assert mock_logger.error.called
        err_args = mock_logger.error.call_args.args[0]
        assert "KafkaCallbackProducer 关闭异常" in err_args
        assert p._producer is None
        assert p._initialized is False

    @staticmethod
    def test_close_flush_close_exception_degrades():
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        p._producer = MagicMock()
        p._initialized = True
        p._producer.flush.side_effect = RuntimeError("flush boom")
        # flush 抛异常,close 仍调用;均在同一 try 内
        with patch("server.comm.kafka_producer.logger") as mock_logger:
            p.close()
        # 至少有一处 error 日志
        assert mock_logger.error.called
        err_args = mock_logger.error.call_args.args[0]
        assert "KafkaCallbackProducer 关闭异常" in err_args
        # 状态重置
        assert p._producer is None
        assert p._initialized is False

    @staticmethod
    def test_close_normal_resets_state():
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_prod = MagicMock()
        p._producer = mock_prod
        p._initialized = True
        p.close()
        # close() 会重置 _producer=None,需用提前捕获的 mock_prod 断言
        mock_prod.flush.assert_called_once()
        mock_prod.close.assert_called_once()
        assert p._producer is None
        assert p._initialized is False

    @staticmethod
    def test_producer_none_returns_directly():
        p = KafkaCallbackProducer(config={})
        # _producer 默认 None
        assert p._producer is None
        # 直接 close 不抛
        p.close()
        assert p._producer is None
        assert p._initialized is False


# ==================== _ensure_producer 自愈测试 ====================

class TestSelfHeal:
    """P1: KafkaCallbackProducer 自愈——初始化失败后 _initialized 保持 False，
    后续 send_callback 可重新初始化（Kafka 短暂不可用恢复后自愈），避免永久不可用。
    """

    @staticmethod
    def test_close_resets_initialized_allowing_reinit():
        """close() 后 _initialized=False，下次 _ensure_producer 可重新初始化。

        契约：close() 始终重置 _initialized=False（即使 _producer 为 None），
        允许下次 send_callback 重新初始化，避免 close 后永久不可用。
        """
        cfg = {"bootstrap_servers": "kafka:9092"}
        p = KafkaCallbackProducer(config=cfg)
        p._producer = MagicMock()
        p._initialized = True
        p.close()
        assert p._producer is None
        assert p._initialized is False  # close 重置，允许重新初始化

        # 重新初始化应成功
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger"):
            KP.return_value = MagicMock()
            ok = p._ensure_producer()
        assert ok is True
        assert p._producer is not None
        assert p._initialized is True

    @staticmethod
    def test_double_check_returns_true_when_already_initialized():
        """已初始化时 _ensure_producer 快速返回 True（double-check 路径，不重复初始化）。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        p._producer = MagicMock()
        p._initialized = True
        with patch("server.comm.kafka_producer.KafkaProducer") as KP:
            ok = p._ensure_producer()
        assert ok is True
        KP.assert_not_called()  # 已初始化，不重复构造 KafkaProducer

    @staticmethod
    def test_producer_reinit_after_failure():
        """首次 _ensure_producer 失败（_initialized=False），第二次调用成功（_initialized=True）。

        场景：Kafka 短暂不可用 → 首次初始化抛异常 → _initialized 保持 False；
        Kafka 恢复后第二次 _ensure_producer 重新初始化成功 → _initialized=True。
        """
        cfg = {"bootstrap_servers": "kafka:9092"}
        p = KafkaCallbackProducer(config=cfg)
        # 第一次：KafkaProducer 构造抛异常 → _producer=None, _initialized=False
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger"):
            KP.side_effect = RuntimeError("broker down")
            ok1 = p._ensure_producer()
        assert ok1 is False
        assert p._producer is None
        assert p._initialized is False  # 失败保持 False，允许重试自愈

        # 第二次：KafkaProducer 构造成功 → _producer=mock, _initialized=True
        with patch("server.comm.kafka_producer.KafkaProducer") as KP2, \
             patch("server.comm.kafka_producer.logger"):
            KP2.return_value = MagicMock()
            ok2 = p._ensure_producer()
        assert ok2 is True
        assert p._producer is not None
        assert p._initialized is True  # 自愈成功


# ==================== send_callback 返回 bool 测试 ====================

class TestSendCallbackReturnBool:
    """P1: send_callback 返回 bool——投递失败时返回 False，engine 据此不推进 offset。"""

    @staticmethod
    def test_send_callback_returns_false_on_send_exception():
        """send 抛异常时返回 False（不 re-raise）。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_producer = MagicMock()
        mock_producer.send.side_effect = RuntimeError("send failed")
        p._producer = mock_producer
        p._initialized = True
        with patch("server.comm.kafka_producer.logger"):
            result = p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {"chunkCount": 1})
        assert result is False

    @staticmethod
    def test_send_callback_returns_false_when_not_initialized():
        """producer 未初始化时返回 False。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger"):
            KP.side_effect = RuntimeError("broker down")
            ok = p._ensure_producer()
        assert ok is False
        # send_callback 在 producer 未初始化时返回 False
        with patch("server.comm.kafka_producer.KafkaProducer") as KP2, \
             patch("server.comm.kafka_producer.logger"):
            KP2.side_effect = RuntimeError("broker down")
            result = p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {"chunkCount": 1})
        assert result is False

    @staticmethod
    def test_send_callback_returns_true_on_success():
        """send 成功时返回 True。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_producer = MagicMock()
        p._producer = mock_producer
        p._initialized = True
        with patch("server.comm.kafka_producer.logger"):
            result = p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {"chunkCount": 1})
        assert result is True
        mock_producer.send.assert_called_once()


# ==================== N2 send_callback 同步等 ACK 测试 ====================

class TestSendCallbackFutureGet:
    """N2: send_callback 调 future.get(timeout) 同步等 broker ACK。

    修复前：send() 返回 Future 不等 ACK，Kafka 不可用时仍返回 True → engine 误推进 offset。
    修复后：future.get() 超时或抛异常返回 False，engine 不推进 offset，依赖重投递。
    """

    @staticmethod
    def test_send_callback_future_get_success_logs_ack():
        """future.get 成功后日志包含 ACK 标识。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_producer = MagicMock()
        mock_future = MagicMock()
        mock_future.get.return_value = MagicMock()
        mock_producer.send.return_value = mock_future
        p._producer = mock_producer
        p._initialized = True
        with patch("server.comm.kafka_producer.logger") as mock_logger:
            p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {})
        assert mock_logger.info.called
        info_args = mock_logger.info.call_args.args[0]
        assert "ACK" in info_args, f"预期日志含 ACK 标识，实际: {info_args}"

    @staticmethod
    def test_send_callback_returns_false_on_future_get_timeout():
        """future.get 超时（broker 不可用）→ 返回 False。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_producer = MagicMock()
        mock_future = MagicMock()
        mock_future.get.side_effect = Exception("future get timeout")
        mock_producer.send.return_value = mock_future
        p._producer = mock_producer
        p._initialized = True
        with patch("server.comm.kafka_producer.logger"):
            result = p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {"chunkCount": 1})
        assert result is False
        # 验证 future.get 被调（确认同步等 ACK）
        mock_future.get.assert_called_once()

    @staticmethod
    def test_send_callback_returns_true_on_future_get_success():
        """future.get 成功（broker ACK）→ 返回 True。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        mock_producer = MagicMock()
        mock_future = MagicMock()
        # future.get 不抛异常，正常返回
        mock_future.get.return_value = MagicMock()  # RecordMetadata mock
        mock_producer.send.return_value = mock_future
        p._producer = mock_producer
        p._initialized = True
        with patch("server.comm.kafka_producer.logger"):
            result = p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {"chunkCount": 1})
        assert result is True
        # 验证 future.get 被调（确认同步等 ACK）
        mock_future.get.assert_called_once()


# ==================== send_callback_transactional 事务测试 ====================

class TestSendCallbackTransactional:
    """事务化投递回调 + offset 提交。"""

    @staticmethod
    def test_transaction_not_initialized_raises():
        """producer 未初始化 → 抛 RuntimeError（不静默吞，由上层重试）。"""
        p = KafkaCallbackProducer(config={"bootstrap_servers": "kafka:9092"})
        with patch("server.comm.kafka_producer.KafkaProducer") as KP, \
             patch("server.comm.kafka_producer.logger"):
            KP.side_effect = RuntimeError("broker down")
            with pytest.raises(RuntimeError):
                p.send_callback_transactional(
                    key="doc-1", value={}, offsets={}, group_id="model-service",
                )

    @staticmethod
    def _make_ready_producer(config=None):
        p = KafkaCallbackProducer(config=config or {"bootstrap_servers": "mock:9092"})
        p._producer = MagicMock()
        p._initialized = True
        return p

    def test_transaction_commit_failure_aborts_resets_and_raises(self):
        """commit_transaction 失败 → abort_transaction + 重置 producer（自愈）+ 抛出。"""
        p = self._make_ready_producer()
        mock_prod = p._producer  # 重置后 p._producer=None，提前捕获引用
        mock_future = MagicMock()
        mock_prod.send.return_value = mock_future
        mock_prod.commit_transaction.side_effect = RuntimeError("coordinator down")
        offsets = {("kb-document-processing", 0): OffsetAndMetadata(5, b"")}
        with pytest.raises(RuntimeError):
            p.send_callback_transactional(
                key="doc-1", value={}, offsets=offsets, group_id="model-service",
            )
        mock_prod.abort_transaction.assert_called_once()
        # 自愈：失败后重置 producer 状态
        mock_prod.close.assert_called_once()
        assert p._producer is None
        assert p._initialized is False
        assert p._transactional_ok is False

    def test_transaction_send_failure_aborts_resets_and_raises(self):
        """send 失败 → abort_transaction + 重置 producer（自愈）+ 抛出。"""
        p = self._make_ready_producer()
        mock_prod = p._producer  # 重置后 p._producer=None，提前捕获引用
        mock_prod.send.side_effect = RuntimeError("callback topic unavailable")
        offsets = {("kb-document-processing", 0): OffsetAndMetadata(5, b"")}
        with pytest.raises(RuntimeError):
            p.send_callback_transactional(
                key="doc-1", value={}, offsets=offsets, group_id="model-service",
            )
        mock_prod.begin_transaction.assert_called_once()
        mock_prod.abort_transaction.assert_called_once()
        mock_prod.commit_transaction.assert_not_called()
        # 自愈：失败后重置 producer 状态，下次 _ensure_producer 重新初始化（新 PID）
        mock_prod.close.assert_called_once()
        assert p._producer is None
        assert p._initialized is False
        assert p._transactional_ok is False

    def test_transaction_success_returns_true(self):
        """begin_transaction → send → send_offsets_to_transaction → commit_transaction 全成功 → True。"""
        p = self._make_ready_producer()
        mock_future = MagicMock()
        p._producer.send.return_value = mock_future
        offsets = {("kb-document-processing", 0): OffsetAndMetadata(5, b"")}
        result = p.send_callback_transactional(
            key="doc-1", value={"eventType": "PROCESSING_COMPLETED"},
            offsets=offsets, group_id="model-service",
        )
        assert result is True
        p._producer.begin_transaction.assert_called_once()
        p._producer.send.assert_called_once()
        p._producer.send_offsets_to_transaction.assert_called_once_with(offsets, "model-service")
        p._producer.commit_transaction.assert_called_once()
        p._producer.abort_transaction.assert_not_called()


# ==================== _reinit_if_idle 空闲 PID 失效检测测试 ====================

class TestReinitIfIdle:
    """空闲超阈值时重置 producer 拿新 PID,避免长时间空闲后 PID mapping 失效。

    场景:2000 页 PDF 处理 6 小时,producer 空闲,broker 端 PID mapping 失效。
    投递回调前检查空闲时长,超 30 分钟就 reset+reinit 拿新 PID。
    """

    @staticmethod
    def _make_ready_producer(config=None):
        p = KafkaCallbackProducer(config=config or {"bootstrap_servers": "kafka:9092"})
        p._producer = MagicMock()
        p._initialized = True
        return p

    def test_first_call_skips_reinit(self):
        """_last_txn_time is None(启动后首次投递)→ 不 reinit,直接返回 True。"""
        p = self._make_ready_producer()
        p._last_txn_time = None
        with patch.object(p, "_reset_producer_state") as mock_reset:
            ok = p._reinit_if_idle()
        assert ok is True
        mock_reset.assert_not_called()

    def test_idle_above_threshold_triggers_reinit(self):
        """空闲 >= 30 分钟 → 触发 _reset_producer_state 拿新 PID。"""
        p = self._make_ready_producer()
        p._last_txn_time = time.monotonic() - 1860  # 31 分钟前,超 30 分钟阈值
        with patch.object(p, "_reset_producer_state") as mock_reset:
            ok = p._reinit_if_idle()
        assert ok is True
        mock_reset.assert_called_once()

    def test_idle_below_threshold_skips_reinit(self):
        """空闲 < 30 分钟 → PID 仍有效,不 reinit。"""
        p = self._make_ready_producer()
        p._last_txn_time = time.monotonic() - 60  # 1 分钟前
        with patch.object(p, "_reset_producer_state") as mock_reset:
            ok = p._reinit_if_idle()
        assert ok is True
        mock_reset.assert_not_called()

    def test_send_callback_transactional_updates_last_txn_time_on_success(self):
        """send_callback_transactional 更新 _last_txn_time。"""
        p = self._make_ready_producer()
        mock_future = MagicMock()
        p._producer.send.return_value = mock_future
        assert p._last_txn_time is None
        offsets = {("kb-document-processing", 0): OffsetAndMetadata(5, b"")}
        p.send_callback_transactional(
            key="doc-1", value={"eventType": "PROCESSING_COMPLETED"},
            offsets=offsets, group_id="model-service",
        )
        assert p._last_txn_time is not None

    def test_send_callback_updates_last_txn_time_on_success(self):
        """send_callback 成功后更新 _last_txn_time,供下次空闲检测。"""
        p = self._make_ready_producer()
        mock_future = MagicMock()
        p._producer.send.return_value = mock_future
        assert p._last_txn_time is None
        with patch("server.comm.kafka_producer.logger"):
            p.send_callback("PROCESSING_COMPLETED", "kb1", "d1", {})
        assert p._last_txn_time is not None


# ==================== 事务锁串行测试 ====================

class TestTxnLock:
    """事务操作(begin/send/commit)加 _txn_lock 串行,避免多文档车道 worker 并发
    调用导致事务状态混乱(IN_TRANSACTION→IN_TRANSACTION 或 Cannot call send
    in state READY)。
    """

    @staticmethod
    def _make_ready_producer(config=None):
        p = KafkaCallbackProducer(config=config or {"bootstrap_servers": "kafka:9092"})
        p._producer = MagicMock()
        p._initialized = True
        p._transactional_ok = True
        p._last_txn_time = None
        return p

    def test_send_callback_serialized(self):
        """两线程并发调 send_callback(事务模式),begin/commit 不交叉。"""
        p = self._make_ready_producer()
        p._producer.send.return_value = MagicMock()

        call_log = []
        log_lock = threading.Lock()

        def log_call(name):
            with log_lock:
                call_log.append(name)

        barrier = threading.Barrier(2)

        def _begin_transaction():
            return (log_call("begin"), time.sleep(0.05))

        def _commit_transaction():
            return log_call("commit")

        p._producer.begin_transaction.side_effect = _begin_transaction
        p._producer.commit_transaction.side_effect = _commit_transaction

        def worker(doc_id):
            barrier.wait()
            p.send_callback("PROCESSING_COMPLETED", "kb1", doc_id, {})

        t1 = threading.Thread(target=worker, args=("doc-1",), name="T1")
        t2 = threading.Thread(target=worker, args=("doc-2",), name="T2")
        t1.start()
        t2.start()
        t1.join(timeout=5)
        t2.join(timeout=5)

        assert call_log == ["begin", "commit", "begin", "commit"], \
            f"事务操作未串行,sequence={call_log}"

    def test_send_callback_transactional_serialized(self):
        """两线程并发调 send_callback_transactional,begin/commit 不交叉(串行)。"""
        p = self._make_ready_producer()
        p._producer.send.return_value = MagicMock()

        call_log = []
        log_lock = threading.Lock()

        def log_call(name):
            with log_lock:
                call_log.append(name)

        barrier = threading.Barrier(2)

        # begin 后 sleep,放大并发窗口:若没锁,第二个线程会在此期间也 begin
        def _begin_transaction():
            return (log_call("begin"), time.sleep(0.05))

        def _commit_transaction():
            return log_call("commit")

        p._producer.begin_transaction.side_effect = _begin_transaction
        p._producer.commit_transaction.side_effect = _commit_transaction
        p._producer.send_offsets_to_transaction = MagicMock()

        offsets = {("kb-document-processing", 0): OffsetAndMetadata(5, b"")}

        def worker(doc_id):
            barrier.wait()  # 两线程同时放行,最大化并发
            p.send_callback_transactional(
                key=doc_id, value={"eventType": "PROCESSING_COMPLETED"},
                offsets=offsets, group_id="model-service",
            )

        t1 = threading.Thread(target=worker, args=("doc-1",), name="T1")
        t2 = threading.Thread(target=worker, args=("doc-2",), name="T2")
        t1.start()
        t2.start()
        t1.join(timeout=5)
        t2.join(timeout=5)

        # 串行:begin→commit→begin→commit。若并发交叉:begin→begin→commit→commit
        assert call_log == ["begin", "commit", "begin", "commit"], \
            f"事务操作未串行,sequence={call_log}"