#!/usr/bin/env python3
"""server.comm.kafka_poller 单元测试。"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from server.comm.kafka_poller import KafkaPollerThread


class TestGroupId:
    @staticmethod
    def test_group_id_from_config():
        """group_id 从 config 读取并可通过 property 暴露。"""
        config = {"bootstrap_servers": "k:9092", "group_id": "my-group"}
        poller = KafkaPollerThread(engine=MagicMock(), config=config, worker_id=0)
        assert poller.group_id == "my-group"

    @staticmethod
    def test_group_id_default():
        """无 group_id 配置时默认 model-service。"""
        config = {"bootstrap_servers": "k:9092"}
        poller = KafkaPollerThread(engine=MagicMock(), config=config, worker_id=0)
        assert poller.group_id == "model-service"
