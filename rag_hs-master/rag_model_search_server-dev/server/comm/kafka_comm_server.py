#!/usr/bin/env python3
# @Time : 2026/8/12 10:00:00
# @Author : Hetao
# @File : [Code] --> kafka_comm_server.py
# @Software : PyCharm
# @Function: Kafka 通信服务。解耦 poll 与 exec：
#   - 1 个 KafkaPollerThread：poll(max_records=N) → engine.dispatch(msg)（非阻塞）
#   - 1 个 ProcessingEngine：控制车道串行（KB 生命周期）+ 文档车道并行（N 单线程 executor + VLM Semaphore）
#     + KBGate（KB 屏障）+ OffsetTracker（按分区连续前缀提交）
#   - 1 个共享 KafkaCallbackProducer：投递处理结果回调
#
#   并行来自 engine 的 worker pool，不依赖多 poller。consumer group 跨 Pod 分区分配，
#   key=kbId 保证同 KB 消息落同一分区→同一 Pod，无需跨 Pod 协调。
import threading

from configs import (
    COMM_KAFKA_ADDRESS,
    COMM_KAFKA_SHUTDOWN_TIMEOUT_MS,
    COMM_KAFKA_WORKERS,
    logger,
)
from server.comm.kafka_poller import KafkaPollerThread
from server.comm.kafka_producer import KafkaCallbackProducer
from server.comm.processing_engine import ProcessingEngine


class KafkaCommServer(threading.Thread):
    """Kafka 通信服务：编排 1 个 poller + 1 个 engine + 1 个共享 producer 的生命周期。"""

    def __init__(self, config: dict = None):
        super().__init__(name="KafkaCommServer", daemon=True)
        self.config = config or COMM_KAFKA_ADDRESS or {}
        self._shutdown_timeout_ms = (
            int(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS)
            if COMM_KAFKA_SHUTDOWN_TIMEOUT_MS
            else 7200000
        )
        # consumer_workers 仅用于兼容旧配置；新架构下并行来自 engine worker pool，1 poller 即可
        workers = (
            int(COMM_KAFKA_WORKERS)
            if COMM_KAFKA_WORKERS
            else 1
        )
        if workers > 1:
            logger.warning(
                "KafkaCommServer: 新架构下并行来自 engine worker pool，COMM_KAFKA_WORKERS="
                f"{workers} 被忽略，使用 1 poller + 16 doc workers"
            )
        self._consumer_workers = 1

        # 先建生产者（共享实例），再建 engine，最后建 poller
        self._producer = KafkaCallbackProducer(self.config)
        self._engine = ProcessingEngine(
            producer=self._producer,
            commit_fn=None,  # 事务化：offset 提交由 producer 事务完成，不再回填 _commit_fn
            group_id=self.config.get("group_id", "model-service"),  # 事务化：注入 group_id（与 poller.group_id 同源）
        )
        self._poller = KafkaPollerThread(
            engine=self._engine, config=self.config, worker_id=0
        )
        # 事务化：注入 poller 到 engine，wait_shutdown 兜底提交 offset + _execute_transaction 死信路径用
        self._engine._poller = self._poller
        self._running = False

    def run(self) -> None:
        if not self.config or not self.config.get("bootstrap_servers"):
            logger.warning("KafkaCommServer: COMM_KAFKA_ADDRESS 未配置，跳过启动")
            return
        logger.info(
            f"KafkaCommServer 启动，poller=1，engine doc_workers=16，"
            f"shutdown_timeout_ms={self._shutdown_timeout_ms}"
        )
        self._running = True
        # 启动 poller 线程（内部 _init_consumer 后进入 poll 循环）
        self._poller.start()
        # 等待 poller 退出（正常会一直阻塞，直到 stop() 触发）
        self._poller.join()

    def stop(self) -> None:
        logger.info("KafkaCommServer 停止中...")
        self._running = False
        # 1. 停止 poller 拉新消息
        self._poller.stop()
        # 2. 等待 poller 退出（close consumer）
        self._poller.join(timeout=self._shutdown_timeout_ms / 1000.0)
        if self._poller.is_alive():
            logger.warning(
                f"poller join 超时（>{self._shutdown_timeout_ms}ms），"
                f"可能正在 poll 长任务，依赖 Kafka rebalance 兜底"
            )
        # 3. engine 优雅停止：等控制车道 + 在途文档完成，提交所有已推进 offset，关闭 executor
        self._engine.wait_shutdown(timeout_ms=self._shutdown_timeout_ms)
        # 4. 关闭生产者
        self._producer.close()
        logger.info("KafkaCommServer 停止完成")
