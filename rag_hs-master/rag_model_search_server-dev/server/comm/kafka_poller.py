#!/usr/bin/env python3
# @Time : 2026/8/20
# @Author : Hetao
# @File : [Code] --> kafka_poller.py
# @Software : PyCharm
# @Function: Kafka 消息拉取线程。只负责 poll + 派发到 ProcessingEngine，不执行任务、不 commit。
#   解耦 poll 与 exec：poller 不被长任务阻塞，执行交给 engine 的 worker pool 并行处理。
import json
import threading
import time

from kafka import KafkaConsumer

from configs import COMM_KAFKA_ADDRESS, COMM_KAFKA_POLL_MAX_RECORDS, logger
from server.comm.callback_utils import safe_repr, safe_str

# _init_consumer 失败后的重连间隔（秒）。broker 抖动/重启时 poller 不会静默退出，
# 而是按此间隔重试，直到连上或被 stop()。
_RECONNECT_BACKOFF_S = 30


def _safe_json_loads(value):
    """容错 JSON 反序列化：坏 JSON 返回 None 而非抛异常。

    坏消息（格式错误/非 UTF-8/空 bytes）返回 None，由 engine.dispatch 的 _finish_skip 跳过并推进 offset，
    避免坏消息卡死分区（poll 层捕获异常后 offset 未提交，导致同一条坏消息被无限重复消费）。
    """
    if not value:
        return None
    try:
        return json.loads(value.decode("utf-8"))
    except Exception as e:
        logger.error(
            f"[poller] 消息反序列化失败（返回 None 跳过，将推进 offset）: {safe_str(e)} | "
            f"raw={safe_repr(value[:200])}"
        )
        return None


class KafkaPollerThread(threading.Thread):
    """Kafka 消息拉取线程：poll 消息并派发到 ProcessingEngine，不阻塞执行。

    与 ProcessingEngine 配合：
    - poller: poll(max_records=N) → engine.dispatch(msg)（非阻塞）
    - engine: 按 eventType 分流到控制车道（串行）或文档车道（并行），完成后回调 + 推进 offset + commit
    """

    def __init__(self, engine, config: dict = None, worker_id: int = 0):
        super().__init__(name=f"KafkaPoller-{worker_id}", daemon=True)
        self._engine = engine
        self.config = config or COMM_KAFKA_ADDRESS or {}
        self.topic = self.config.get("topic_processing", "kb-document-processing")
        self.group_id = self.config.get("group_id", "model-service")
        self.bootstrap_servers = self.config.get("bootstrap_servers")
        self._worker_id = worker_id
        self._poll_max_records = int(COMM_KAFKA_POLL_MAX_RECORDS) if COMM_KAFKA_POLL_MAX_RECORDS else 32
        self._consumer = None
        self._running = False

    def run(self) -> None:
        """拉取循环：poll 消息并派发到 engine，支持通过 stop() 优雅关闭。

        _init_consumer 失败后进入退避重连循环（_RECONNECT_BACKOFF_S 间隔），
        避免 broker 抖动/重启时 poller 线程静默退出导致消费链路中断
        （主进程不感知，无 watchdog 兜底，只有重启进程才能恢复）。
        """
        self._running = True
        while self._running:
            if not self._init_consumer():
                logger.error(
                    f"[poller-{self._worker_id}] 初始化失败，{_RECONNECT_BACKOFF_S}s 后重试"
                )
                # 分段 sleep，保证 stop() 时能及时退出
                for _ in range(_RECONNECT_BACKOFF_S):
                    if not self._running:
                        break
                    time.sleep(1)
                continue
            logger.info(f"[poller-{self._worker_id}] 拉取循环开始")
            try:
                while self._running:
                    try:
                        records = self._consumer.poll(
                            timeout_ms=1000, max_records=self._poll_max_records
                        )
                        if not records:
                            continue
                        total = sum(len(msgs) for msgs in records.values())
                        logger.info(
                            f"[poller-{self._worker_id}] poll 到 {total} 条消息，{len(records)} 个分区"
                        )
                        # 按分区顺序遍历，同一分区内消息按 offset 序（保证 KB_CREATED 先于 DOCUMENT_PUBLISHED）
                        for _tp, msgs in records.items():
                            for msg in msgs:
                                if not self._running:
                                    break
                                self._engine.dispatch(msg)
                    except Exception as e:
                        err_str = str(e).replace("{", "{{").replace("}", "}}")
                        logger.error(
                            f"[poller-{self._worker_id}] poll 异常: {err_str}", exc_info=True
                        )
                        time.sleep(1)  # 退避，避免 poll→except→poll 紧密循环
            except Exception as e:
                # 兜底：未预期异常导致内层循环退出，close 后重连
                err_str = str(e).replace("{", "{{").replace("}", "}}")
                logger.error(
                    f"[poller-{self._worker_id}] 拉取循环异常退出，将重连: {err_str}",
                    exc_info=True,
                )
            finally:
                self.close()
        logger.info(f"[poller-{self._worker_id}] 已停止")

    def stop(self) -> None:
        """优雅停止拉取循环。"""
        self._running = False

    def commit_offsets(self, offsets) -> None:
        """提交 offset（由 engine 的 OffsetTracker 推进后调用）。

        consumer 未初始化时 no-op（at-least-once：未提交的 offset 重启后重投递，幂等兜底）。
        """
        if self._consumer is None:
            return
        try:
            self._consumer.commit(offsets)
            logger.info(
                f"[poller-{self._worker_id}] commit 成功: {list(offsets.items())}"
            )
        except Exception as e:
            logger.error(
                f"[poller-{self._worker_id}] commit 失败: {safe_str(e)} | offsets={list(offsets.items())}",
                exc_info=True,
            )

    def close(self) -> None:
        """关闭消费者。offset 提交由 engine.wait_shutdown 负责，这里仅 close。"""
        if self._consumer is None:
            return
        try:
            self._consumer.close()
        except Exception as e:
            err_str = str(e).replace("{", "{{").replace("}", "}}")
            logger.error(f"[poller-{self._worker_id}] 关闭异常: {err_str}")
        self._consumer = None

    def _init_consumer(self) -> bool:
        """懒加载 KafkaConsumer 实例。返回是否初始化成功。"""
        if self._consumer is not None:
            return True
        if not self.bootstrap_servers:
            logger.warning(
                f"[poller-{self._worker_id}] bootstrap_servers 未配置，poller 不启动"
            )
            return False
        kwargs = {
            "bootstrap_servers": self.bootstrap_servers,
            "group_id": self.group_id,
            "enable_auto_commit": False,  # 手动 commit（由 engine 按分区推进）
            "auto_offset_reset": "earliest",
            # 反序列化容错：坏 JSON 返回 None 而非抛异常，避免 poll() 层捕获后 offset 未提交
            # 导致同一条坏消息被无限重复消费（分区永久卡死，后续消息全部阻塞）。
            # engine.dispatch 对 None/非 dict 走 _finish_skip 推进 offset，坏消息被跳过。
            "value_deserializer": lambda v: _safe_json_loads(v),
            "key_deserializer": lambda k: k.decode("utf-8") if k else None,
            "consumer_timeout_ms": 1000,  # poll 空轮询不阻塞太久
            # 注意：kafka-python 3.x 移除了 api_version_auto_timeout_ms 配置项，不要传
            "request_timeout_ms": 305000,  # 需严格大于 session_timeout_ms
            "session_timeout_ms": 30000,
            "max_poll_interval_ms": 10800000,  # 180 分钟，支持单条最长处理约 3 小时（含余量）
        }
        sasl = self.config.get("sasl_ssl")
        if sasl:
            kwargs["security_protocol"] = sasl.get("security_protocol", "SASL_SSL")
            kwargs["sasl_mechanism"] = sasl.get("mechanism", "PLAIN")
            kwargs["sasl_plain_username"] = sasl.get("username", "")
            kwargs["sasl_plain_password"] = sasl.get("password", "")
        try:
            self._consumer = KafkaConsumer(self.topic, **kwargs)
            logger.info(
                f"[poller-{self._worker_id}] 已连接，topic={self.topic}, group={self.group_id}, "
                f"poll_max_records={self._poll_max_records}"
            )
            return True
        except Exception as e:
            # 异常消息可能含 {…}（如 KafkaConfigurationError: Unrecognized configs: {...}），
            # loguru 默认对消息调 .format() 会把 {…} 当占位符导致 KeyError，故转义花括号
            err_str = str(e).replace("{", "{{").replace("}", "}}")
            logger.error(
                f"[poller-{self._worker_id}] 连接失败: {err_str} | "
                f"bootstrap_servers={self.bootstrap_servers}, topic={self.topic}, "
                f"group_id={self.group_id}",
                exc_info=True,
            )
            self._consumer = None
            return False
