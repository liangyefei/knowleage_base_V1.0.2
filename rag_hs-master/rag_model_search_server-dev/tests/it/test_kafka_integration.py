#!/usr/bin/env python3
"""
comm 域 Kafka 集成测试（IT）。

覆盖:
- server.comm.kafka_producer.KafkaCallbackProducer 回调投递链路（需真实 Kafka）

依赖真实 Kafka；broker 不可达时通过 kafka_addr fixture 跳过。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import json
import logging
import sys
import uuid
from pathlib import Path

# 将项目根目录加入 sys.path，保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

logger = logging.getLogger(__name__)


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
            logger.debug("[kafka-integration] probe %s failed, will try next: %s", c, e)
            continue
    return None


# ==================== Kafka 集成测试（需 Kafka，连不上则 skip）====================


@pytest.fixture
def kafka_addr():
    addr = _try_connect_kafka()
    if not addr:
        pytest.skip("Kafka 不可用，跳过集成测试")
    return addr


class TestKafkaIntegration:
    @staticmethod
    def test_producer_send_callback(kafka_addr):
        """验证 KafkaCallbackProducer 能投递回调到目标 topic，并读回确认送达。"""
        from kafka import KafkaConsumer

        from server.comm.kafka_producer import KafkaCallbackProducer

        topic = f"test-processed-{uuid.uuid4().hex[:8]}"
        config = {
            "bootstrap_servers": kafka_addr,
            "topic_processed": topic,
        }
        p = KafkaCallbackProducer(config)
        assert p._producer is None  # 懒加载：未发送前不初始化
        try:
            p.send_callback(
                "PROCESSING_COMPLETED", "test-kb", "test-doc",
                {"processedAt": "2026-08-12T00:00:00Z", "chunkCount": 3},
            )
            assert p._producer is not None  # send_callback 触发懒加载
            p.flush()
        finally:
            p.close()

        # 读回验证送达
        verify_group = f"verify-{uuid.uuid4().hex[:8]}"
        c = KafkaConsumer(
            topic,
            bootstrap_servers=kafka_addr,
            group_id=verify_group,
            auto_offset_reset="earliest",
            value_deserializer=lambda v: json.loads(v.decode("utf-8")) if v else None,
            consumer_timeout_ms=10000,
            api_version_auto_timeout_ms=10000,
            request_timeout_ms=30000,
        )
        found = False
        try:
            for msg in c:
                env = msg.value
                if not isinstance(env, dict):
                    continue
                if (env.get("kbId") == "test-kb"
                        and env.get("documentId") == "test-doc"
                        and env.get("eventType") == "PROCESSING_COMPLETED"):
                    found = True
                    break
        finally:
            c.close()
        assert found, f"未在 {topic} 读回 PROCESSING_COMPLETED 回调"
