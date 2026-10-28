#!/usr/bin/env python3
"""
server.comm.kafka_comm_server 单元测试（新架构：1 poller + 1 engine + 1 producer）。

覆盖 KafkaCommServer:
- TestRunSkip: 无 bootstrap_servers 时 run() 立即 return,不启动线程
- TestStop: poller.stop / engine.wait_shutdown / producer.close 被调用顺序
- TestConfig: shutdown_timeout 从配置注入；COMM_KAFKA_WORKERS>1 触发 warning 但仍用 1 poller

所有用例均使用 mock,不连真实 Kafka,子组件 KafkaCallbackProducer /
KafkaPollerThread / ProcessingEngine 全部 patch 为 MagicMock 以隔离副作用。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from server.comm.kafka_comm_server import KafkaCommServer


def _make_server(**config_overrides):
    """构造 KafkaCommServer，子组件全部 mock。

    config_overrides 支持 COMM_KAFKA_WORKERS / COMM_KAFKA_SHUTDOWN_TIMEOUT_MS /
    COMM_KAFKA_ADDRESS 三个 patch 值。
    """
    patches = []
    patches.append(patch("server.comm.kafka_comm_server.COMM_KAFKA_WORKERS",
                         config_overrides.get("COMM_KAFKA_WORKERS", 1)))
    patches.append(patch("server.comm.kafka_comm_server.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS",
                         config_overrides.get("COMM_KAFKA_SHUTDOWN_TIMEOUT_MS", 7200000)))
    patches.append(patch("server.comm.kafka_comm_server.COMM_KAFKA_ADDRESS",
                         config_overrides.get("COMM_KAFKA_ADDRESS", {"bootstrap_servers": "k:9092"})))
    patches.append(patch("server.comm.kafka_comm_server.KafkaCallbackProducer"))
    patches.append(patch("server.comm.kafka_comm_server.KafkaPollerThread"))
    patches.append(patch("server.comm.kafka_comm_server.ProcessingEngine"))
    for p in patches:
        p.start()
    try:
        server = KafkaCommServer()
    finally:
        for p in patches:
            p.stop()
    return server


class TestRunSkip:
    """无 bootstrap_servers 时 run() 立即 return,不启动 poller 线程。"""

    @staticmethod
    def test_run_skip_empty_address():
        with patch("server.comm.kafka_comm_server.COMM_KAFKA_WORKERS", 1), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS", 7200000), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_ADDRESS", {}), \
             patch("server.comm.kafka_comm_server.KafkaCallbackProducer"), \
             patch("server.comm.kafka_comm_server.KafkaPollerThread"), \
             patch("server.comm.kafka_comm_server.ProcessingEngine"), \
             patch("server.comm.kafka_comm_server.logger") as mock_logger:
            server = KafkaCommServer()
            assert server.config == {}
            server.run()
            # warning: COMM_KAFKA_ADDRESS 未配置
            assert mock_logger.warning.called
            warn_msgs = [c.args[0] for c in mock_logger.warning.call_args_list if c.args]
            assert any("COMM_KAFKA_ADDRESS 未配置" in m for m in warn_msgs)
            # poller 未 start
            server._poller.start.assert_not_called()
            assert server._running is False

    @staticmethod
    def test_run_skip_empty_bootstrap_servers():
        with patch("server.comm.kafka_comm_server.COMM_KAFKA_WORKERS", 1), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS", 7200000), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_ADDRESS", {"bootstrap_servers": ""}), \
             patch("server.comm.kafka_comm_server.KafkaCallbackProducer"), \
             patch("server.comm.kafka_comm_server.KafkaPollerThread"), \
             patch("server.comm.kafka_comm_server.ProcessingEngine"), \
             patch("server.comm.kafka_comm_server.logger") as mock_logger:
            server = KafkaCommServer()
            assert server.config.get("bootstrap_servers") == ""
            server.run()
            assert mock_logger.warning.called
            server._poller.start.assert_not_called()


class TestStop:
    """stop() 调用 poller.stop / engine.wait_shutdown / producer.close 顺序。"""

    @staticmethod
    def test_stop_calls_poller_stop_engine_wait_shutdown_producer_close():
        server = _make_server(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS=1000)
        server._poller.is_alive.return_value = False
        server.stop()
        # poller.stop() 被调用
        server._poller.stop.assert_called_once()
        # engine.wait_shutdown 被调用，timeout=shutdown_timeout_ms
        server._engine.wait_shutdown.assert_called_once()
        _, kwargs = server._engine.wait_shutdown.call_args
        assert kwargs.get("timeout_ms") == 1000
        # producer.close() 被调用
        server._producer.close.assert_called_once()

    @staticmethod
    def test_stop_warns_on_poller_still_alive():
        server = _make_server(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS=1000)
        # poller join 后仍存活 → 触发 warning
        server._poller.is_alive.return_value = True
        with patch("server.comm.kafka_comm_server.logger") as mock_logger:
            server.stop()
        warn_msgs = [c.args[0] for c in mock_logger.warning.call_args_list if c.args]
        assert any("join 超时" in m for m in warn_msgs)
        # 仍然调用 engine.wait_shutdown + producer.close
        server._engine.wait_shutdown.assert_called_once()
        server._producer.close.assert_called_once()


class TestConfig:
    """配置注入：shutdown_timeout、COMM_KAFKA_WORKERS 兼容。"""

    @staticmethod
    def test_shutdown_timeout_from_config():
        server = _make_server(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS=99000)
        assert server._shutdown_timeout_ms == 99000

    @staticmethod
    def test_shutdown_timeout_none_uses_default():
        server = _make_server(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS=None)
        assert server._shutdown_timeout_ms == 7200000

    @staticmethod
    def test_workers_gt_1_warns_and_uses_1_poller():
        # 新架构下 COMM_KAFKA_WORKERS>1 被忽略，使用 1 poller + 16 doc workers
        with patch("server.comm.kafka_comm_server.logger") as mock_logger:
            server = _make_server(COMM_KAFKA_WORKERS=8)
        assert server._consumer_workers == 1
        warn_msgs = [c.args[0] for c in mock_logger.warning.call_args_list if c.args]
        assert any("被忽略" in m for m in warn_msgs)

    @staticmethod
    def test_workers_1_no_warning():
        with patch("server.comm.kafka_comm_server.logger") as mock_logger:
            _make_server(COMM_KAFKA_WORKERS=1)
        # workers=1 不触发 "被忽略" warning
        warn_msgs = [c.args[0] for c in mock_logger.warning.call_args_list if c.args]
        assert not any("被忽略" in m for m in warn_msgs)

    @staticmethod
    def test_group_id_wired_to_engine():
        # 事务化：ProcessingEngine 构造时注入 group_id（来自 config），commit_fn=None
        with patch("server.comm.kafka_comm_server.COMM_KAFKA_WORKERS", 1), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_SHUTDOWN_TIMEOUT_MS", 7200000), \
             patch("server.comm.kafka_comm_server.COMM_KAFKA_ADDRESS",
                   {"bootstrap_servers": "k:9092", "group_id": "model-service-test"}), \
             patch("server.comm.kafka_comm_server.KafkaCallbackProducer"), \
             patch("server.comm.kafka_comm_server.KafkaPollerThread"), \
             patch("server.comm.kafka_comm_server.ProcessingEngine") as mock_engine_cls:
            KafkaCommServer()
        # ProcessingEngine 构造调用应含 group_id="model-service-test"，commit_fn=None
        _, kwargs = mock_engine_cls.call_args
        assert kwargs.get("group_id") == "model-service-test"
        assert kwargs.get("commit_fn") is None
