#!/usr/bin/env python3
# @Time : 2026/8/12 10:00:00
# @Author : Hetao
# @File : [Code] --> kafka_producer.py
# @Software: PyCharm
# @Function: Kafka 回调生产者，向 kb-document-processed topic 投递回调信封（PROCESSING_COMPLETED / PROCESSING_FAILED）
import json
import os
import socket
import threading
import time
import uuid
from datetime import datetime, timezone

from kafka import KafkaProducer

from configs import COMM_KAFKA_ADDRESS, COMM_KAFKA_CALLBACK_SEND_TIMEOUT_MS, logger
from server.comm.callback_utils import safe_json_dumps, safe_str

# producer 空闲超此阈值(秒)则重置拿新 PID,避免长时间空闲后 PID mapping 失效
# (InvalidProducerIdMappingError,常见于大文档 6 小时处理后回调投递)
_PRODUCER_IDLE_REINIT_THRESHOLD_SECONDS = 1800  # 30 分钟


class KafkaCallbackProducer:
    """向 kb-document-processed topic 投递回调信封（PROCESSING_COMPLETED / PROCESSING_FAILED）"""

    def __init__(self, config: dict = None):
        self.config = config or COMM_KAFKA_ADDRESS
        self.topic = self.config.get("topic_processed", "kb-document-processed") if self.config else "kb-document-processed"  # pylint: disable=line-too-long
        self._producer = None
        self._initialized = False
        self._transactional_ok = False  # 事务是否可用（init_transactions 成功后置 True）
        self._lock = threading.Lock()
        self._txn_lock = threading.Lock()  # 事务操作串行锁,避免并发 begin/commit 导致状态混乱
        self._last_txn_time = None  # 上次成功事务/投递完成时间戳(monotonic),用于空闲 PID 失效检测

    def send_callback(self, event_type, kb_id, document_id, payload) -> bool:
        """投递回调信封。event_type: PROCESSING_COMPLETED|PROCESSING_FAILED。key=document_id 或 kb_id。

        同步等 ACK：调 `future.get(timeout=COMM_KAFKA_CALLBACK_SEND_TIMEOUT_MS)` 确保
        消息被 broker ACK，避免异步投递失败时仍返回 True 导致 engine 误推进 offset。

        事务 producer 兼容（修 IllegalStateError bug）：_init_producer 无条件尝试事务化
        升级，升级成功后 self._producer 是事务 producer（状态 READY），直接 send 会抛
        IllegalStateError: Cannot call send in state READY。此处检查 _transactional_ok，
        事务 producer 用 begin_transaction→send→commit_transaction 包裹（不调
        send_offsets_to_transaction，offset 提交仍由降级路径 poller.commit_offsets 分离完成）。

        Returns:
            True: 投递成功（消息已被 broker ACK）；
            False: producer 未初始化、future.get 超时或 send/get 抛异常
                  （不 re-raise，由调用方决定是否推进 offset）。
        """
        if not self._ensure_producer():
            logger.warning("KafkaCallbackProducer 未初始化，跳过回调投递")
            return False
        envelope = {
            "messageId": str(uuid.uuid4()),
            "eventType": event_type,
            "kbId": kb_id,
            "documentId": document_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "payload": payload or {},
        }
        key = document_id or kb_id
        timeout = (int(COMM_KAFKA_CALLBACK_SEND_TIMEOUT_MS) if COMM_KAFKA_CALLBACK_SEND_TIMEOUT_MS else 10000) / 1000.0
        # 事务操作加锁串行:多文档车道 worker 并发调 send_callback 时,begin_transaction/
        # send/commit_transaction 是分离调用,中间状态 IN_TRANSACTION 会被并发线程破坏 →
        # Invalid transition IN_TRANSACTION→IN_TRANSACTION 或 IllegalStateError: Cannot
        # call send in state READY。锁覆盖 reinit + 事务全过程,确保同一时刻只有一个线程在事务中。
        with self._txn_lock:
            # 空闲超阈值先 reinit 拿新 PID,避免长时间空闲后 PID mapping 失效
            if not self._reinit_if_idle():
                logger.warning("KafkaCallbackProducer reinit 后仍不可用，跳过回调投递")
                return False
            try:
                if self._transactional_ok:
                    # 事务 producer：READY 状态直接 send 抛 IllegalStateError，必须 begin→send→commit
                    self._producer.begin_transaction()
                    future = self._producer.send(self.topic, key=key, value=envelope)
                    future.get(timeout=timeout)
                    self._producer.commit_transaction()
                else:
                    # 非事务 producer：直接 send
                    future = self._producer.send(self.topic, key=key, value=envelope)
                    future.get(timeout=timeout)
                logger.info(f"Kafka 回调已投递（ACK）: eventType={event_type}, kbId={kb_id}, documentId={document_id}")
                self._last_txn_time = time.monotonic()  # 投递成功更新时间戳,供下次空闲检测
            except Exception as e:
                err_str = str(e).replace("{", "{{").replace("}", "}}")
                logger.error(
                    f"Kafka 回调投递失败/超时: eventType={event_type}, kbId={kb_id}, error={err_str} | "
                    f"envelope={safe_json_dumps(envelope)}",
                    exc_info=True,
                )
                # 事务 producer 失败：abort 事务 + 重置状态（与 send_callback_transactional 一致，
                # 避免状态损坏导致后续重试全失败 → 死信路径 → 回调丢失）
                if self._transactional_ok:
                    try:
                        self._producer.abort_transaction()
                    except Exception as abort_err:
                        logger.error(f"[producer] abort_transaction 失败: {safe_str(abort_err)}")
                    self._reset_producer_state()
                return False
        return True

    def send_callback_transactional(self, key, value, offsets, group_id, timeout=30):
        """事务化投递回调 + 提交 offset（exactly-once 消费-生产模式）。

        一个事务内完成：回调消息投递 + consumer offset 提交，
        broker 端保证原子性（commit_transaction 成功 ⇔ 回调已入 topic 且 offset 已提交）。

        Args:
            key: 回调消息 key（document_id 或 kb_id）
            value: 回调消息 value（envelope dict）
            offsets: dict[TopicPartition, OffsetAndMetadata]，待提交的 offset
            group_id: consumer group_id，用于 send_offsets_to_transaction
            timeout: send 的 future.get 超时秒

        Returns:
            True: 事务成功（回调已投递 + offset 已提交）

        Raises:
            RuntimeError: producer 未初始化
            Exception: 事务任一步失败（已 abort，由上层 _execute_transaction 捕获重试计数）
        """
        if not self._ensure_producer():
            raise RuntimeError("KafkaCallbackProducer 未初始化，事务无法执行")
        # 事务操作加锁串行:多文档车道 worker 并发调 send_callback_transactional 时,
        # begin_transaction/send/commit_transaction 是分离调用,中间状态 IN_TRANSACTION
        # 会被并发线程破坏 → Invalid transition IN_TRANSACTION→IN_TRANSACTION 或
        # IllegalStateError: Cannot call send in state READY。锁覆盖 reinit + 事务全过程,
        # 确保同一时刻只有一个线程在事务中(broker 端单 transactional_id 本就串行,加锁不影响吞吐)。
        with self._txn_lock:
            # 空闲超阈值先 reinit 拿新 PID,避免长时间空闲后 begin_transaction 抛 InvalidProducerIdMappingError
            if not self._reinit_if_idle():
                raise RuntimeError("KafkaCallbackProducer reinit 后仍不可用，事务无法执行")
            # kafka-python 官方 API 为蛇形 begin_transaction（非驼峰 beginTransaction）
            self._producer.begin_transaction()
            try:
                future = self._producer.send(self.topic, key=key, value=value)
                future.get(timeout=timeout)  # 等 send ACK
                self._producer.send_offsets_to_transaction(offsets, group_id)
                self._producer.commit_transaction()
                self._last_txn_time = time.monotonic()  # 事务成功更新时间戳,供下次空闲检测
                logger.info(
                    f"Kafka 事务回调已提交: key={key}, offsets={list(offsets.items())}, "
                    f"group_id={group_id}"
                )
                return True
            except Exception as e:
                try:
                    self._producer.abort_transaction()
                except Exception as abort_err:
                    logger.error(f"[producer] abort_transaction 失败: {safe_str(abort_err)}")
                # 诊断增强：按异常类型快速定位失败环节（grep "[{err_type}]" 即可分类统计）
                # - ProducerFencedException: 多实例共用 transactional_id 相互 fence（多 pod 副本）
                # - IllegalGeneration/RebalanceInProgress: send_offsets_to_transaction 时 consumer rebalance 中
                # - TransactionTimeoutException/TransactionAbortedException: 事务超时或被 abort
                # - CoordinatorNotAvailableException/NotCoordinatorException: 事务 coordinator 不可用
                # - KafkaTransactionError: 事务状态机错误（init_transactions 未完成等）
                # - RuntimeError("KafkaCallbackProducer 未初始化"): producer 初始化失败（broker 不支持事务?）
                err_type = type(e).__name__
                err_str = str(e).replace("{", "{{").replace("}", "}}")
                logger.error(
                    f"Kafka 事务失败 [{err_type}]（已 abort，offset 未提交，将重投递）: "
                    f"key={key}, group_id={group_id}, "
                    f"offsets={list(offsets.items())}, error={err_str}",
                    exc_info=True,
                )
                # 自愈：事务失败可能是 PID 映射失效/状态损坏（InvalidProducerIdMappingError/
                # ProducerFenced/TransactionTimeout 等），重置 producer 状态，下次 _ensure_producer
                # 会重新 _init_producer（新 PID + init_transactions），避免 producer 一直用失效
                # PID 导致 retry 全失败 → 死信路径 → 回调丢失。
                self._reset_producer_state()
                raise

    def is_transactional_available(self) -> bool:
        """事务是否可用（init_transactions 成功且 producer 已初始化）。

        engine._execute_transaction 据此决定走事务路径还是降级到非事务 send_callback。
        """
        return self._transactional_ok and self._producer is not None and self._initialized

    def flush(self):
        if self._ensure_producer():
            self._producer.flush()

    def close(self):
        if self._producer:
            try:
                # 事务化：关闭前 abort 未完成事务（若有），避免 close 时悬挂事务
                try:
                    self._producer.abort_transaction()
                except Exception:
                    pass  # 无进行中事务会抛异常，忽略
                self._producer.flush()
                self._producer.close()
            except Exception as e:
                err_str = str(e).replace("{", "{{").replace("}", "}}")
                logger.error(f"KafkaCallbackProducer 关闭异常: {err_str}")
            self._producer = None
        # 始终重置 _initialized，允许下次 send_callback 重新初始化（自愈）
        self._initialized = False

    def _ensure_producer(self) -> bool:
        """懒加载 KafkaProducer，首次发送时初始化，避免主线程启动时阻塞。

        多线程安全：使用 double-check + Lock，确保只有一个线程执行 _init_producer()，
        其他线程等待并复用结果，避免回调丢失与 KafkaProducer 实例泄露。

        自愈：初始化失败后 _initialized 保持 False，允许后续 send_callback 重试
        （Kafka 短暂不可用恢复后，下次调用会重新初始化），避免永久不可用。
        """
        if self._initialized and self._producer is not None:
            return True
        with self._lock:
            if self._initialized and self._producer is not None:
                return True
            self._init_producer()
            # 仅在 _producer 成功创建后置 True，失败保持 False 允许重试自愈
            self._initialized = self._producer is not None
        return self._producer is not None

    def _reinit_if_idle(self) -> bool:
        """空闲超阈值时重置 producer 拿新 PID,避免长时间空闲后 PID mapping 失效。

        场景:2000 页 PDF 处理 6 小时,期间 producer 空闲,broker 端 PID mapping
        可能因 transactional.id.expiration.ms 超时或连接空闲断开而失效。处理
        完成投递回调时 begin_transaction 会抛 InvalidProducerIdMappingError,
        触发 5 次重试(每次重投递重新处理 6 小时)→ 死信 → 回调丢失。

        本方法在投递回调前检查空闲时长,超过 _PRODUCER_IDLE_REINIT_THRESHOLD_SECONDS
        (默认 30 分钟)就 reset+reinit 拿新 PID,代价是最多 15s 初始化延迟
        (远小于 6 小时重处理的代价)。

        Returns:
            True: producer 可用(刚 reinit 或仍新鲜)
            False: producer 不可用(_ensure_producer / reinit 失败)
        """
        if not self._ensure_producer():
            return False
        if self._last_txn_time is None:
            return True  # 启动后首次投递,producer 刚 init,PID 新鲜
        idle_seconds = time.monotonic() - self._last_txn_time
        if idle_seconds < _PRODUCER_IDLE_REINIT_THRESHOLD_SECONDS:
            return True  # 未超阈值,PID 仍有效
        logger.info(
            f"[producer] producer 空闲 {idle_seconds:.0f}s 超阈值 "
            f"{_PRODUCER_IDLE_REINIT_THRESHOLD_SECONDS}s,重置拿新 PID "
            f"避免 PID mapping 失效(InvalidProducerIdMappingError)"
        )
        self._reset_producer_state()
        return self._producer is not None and self._initialized

    def _init_producer(self):
        """初始化 producer：先建非事务 producer 保证可用，再带超时尝试事务化。

        ⚠️ 核心防阻塞设计：
        - kafka-python 的 init_transactions() 在 transaction coordinator 不可用时会
          **无限阻塞**（不抛异常、不尊重 request_timeout_ms），导致 send_callback
          在 _ensure_producer() 中死等 → 回调线程卡死 → 后端永远收不到消息。
        - 因此先创建一个不带 transactional_id 的 producer（构造不阻塞），保证
          send_callback 立即可用；然后在独立线程中带 15s 超时尝试事务化，
          超时即放弃事务、保持非事务 producer（at-least-once 语义）。
        """
        _BOOTSTRAP_SERVERS_KEY = 'bootstrap_servers'
        if not self.config or not self.config.get(_BOOTSTRAP_SERVERS_KEY):
            logger.warning("KafkaCallbackProducer: bootstrap_servers 未配置，生产者不启用")
            return
        kwargs = {
            _BOOTSTRAP_SERVERS_KEY: self.config[_BOOTSTRAP_SERVERS_KEY],
            "value_serializer": lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
            "key_serializer": lambda k: k.encode("utf-8") if k else None,
            # 注意：kafka-python 3.x 移除了 api_version_auto_timeout_ms 配置项，不要传
            "request_timeout_ms": 30000,
            "retries": 3,
        }
        sasl = self.config.get("sasl_ssl")
        if sasl:
            kwargs["security_protocol"] = sasl.get("security_protocol", "SASL_SSL")
            kwargs["sasl_mechanism"] = sasl.get("mechanism", "PLAIN")
            kwargs["sasl_plain_username"] = sasl.get("username", "")
            kwargs["sasl_plain_password"] = sasl.get("password", "")

        # 1. 先建非事务 producer（不带 transactional_id，构造不阻塞），保证 send_callback 立即可用
        try:
            self._producer = KafkaProducer(**kwargs)
            self._transactional_ok = False
            logger.info(
                f"KafkaCallbackProducer 已连接（非事务），topic={self.topic}，"
                f"将尝试事务化升级（带 15s 超时）"
            )
        except Exception as e:
            # 异常消息可能含 {…}（如 KafkaConfigurationError: Unrecognized configs: {...}），
            # loguru 默认对消息调 .format() 会把 {…} 当占位符导致 KeyError，故转义花括号
            err_str = str(e).replace("{", "{{").replace("}", "}}")
            logger.error(
                f"KafkaCallbackProducer 构造失败（网络/认证/配置）: {err_str} | "
                f"bootstrap_servers={self.config.get(_BOOTSTRAP_SERVERS_KEY)}, topic={self.topic}",
                exc_info=True,
            )
            self._producer = None
            return

        # 2. 带超时尝试事务化：独立线程跑 init_transactions，避免主线程阻塞
        # 先检测客户端库是否支持事务 API（部分裁剪版 kafka-python 移除了事务方法），
        # 不支持则直接跳过，避免每次初始化都抛 AssertionError 产生日志噪音。
        if not hasattr(KafkaProducer, "init_transactions") or not hasattr(KafkaProducer, "begin_transaction"):
            logger.info(
                f"KafkaCallbackProducer kafka-python 客户端不支持事务 API"
                f"（init_transactions/begin_transaction 方法缺失），使用非事务 producer"
                f"（at-least-once 语义）。topic={self.topic}"
            )
            return

        # transactional_id 必须集群内唯一（否则多 Pod 同 id 会互相 fence，Epoch 递增循环），
        # 且跨重启稳定（才能恢复未完成的事务）。优先用 StatefulSet 的 POD_NAME（稳定），
        # 其次 Deployment 的 HOSTNAME（重启后变，会留 60s 事务恢复窗口），最后 hostname 兜底。
        _instance_id = os.environ.get("POD_NAME") or os.environ.get("HOSTNAME") or socket.gethostname()
        tx_kwargs = dict(kwargs, transactional_id=f"model-service-callback-tx-{_instance_id}")
        logger.info(f"KafkaCallbackProducer 使用 transactional_id=model-service-callback-tx-{_instance_id}")
        tx_producer = None
        try:
            tx_producer = KafkaProducer(**tx_kwargs)
        except Exception as e:
            # 事务 producer 构造失败：保持非事务 producer 即可
            logger.warning(
                f"KafkaCallbackProducer 事务 producer 构造失败（保持非事务）: {safe_str(e)}"
            )
            return

        init_result = {"ok": False, "err": None}

        def _try_init_transactions():
            try:
                tx_producer.init_transactions()
                init_result["ok"] = True
            except Exception as e:
                init_result["err"] = e

        t = threading.Thread(target=_try_init_transactions, daemon=True)
        t.start()
        t.join(timeout=15)  # 15 秒超时，防止 init_transactions 无限阻塞

        if init_result["ok"]:
            # 事务化成功：切换到事务 producer，关闭非事务 producer
            try:
                self._producer.close(timeout=1)
            except Exception as close_err:
                logger.error(f"[producer] 关闭非事务 producer 失败（忽略）: {safe_str(close_err)}")
            self._producer = tx_producer
            self._transactional_ok = True
            logger.info(f"KafkaCallbackProducer 升级为事务化，topic={self.topic}")
        else:
            # 事务化失败或超时：保持非事务 producer
            if t.is_alive():
                logger.warning(
                    f"KafkaCallbackProducer init_transactions 超时（15s），保持非事务 producer"
                    f"（事务 coordinator 不可用，后台线程仍在运行）。topic={self.topic}"
                )
                # 不 close tx_producer（避免与后台 init 线程竞争），让其随进程退出
            else:
                logger.warning(
                    f"KafkaCallbackProducer init_transactions 失败（保持非事务）: {safe_str(init_result['err'])} | "
                    f"topic={self.topic}",
                    exc_info=False,
                )
                try:
                    tx_producer.close(timeout=1)
                except Exception as e:
                    logger.warning(f"[kafka-producer] failed to close transactional producer after init failure: {safe_str(e)}")
                    pass

    def _reset_producer_state(self):
        """重置 producer 状态并立即重新初始化（新 PID）。

        用于事务失败后的自愈（InvalidProducerIdMappingError / ProducerFenced /
        TransactionTimeout / 事务状态损坏等）。重置后立即 _init_producer 获取新 PID，
        避免后续文档回调因 _transactional_ok=False 而降级为非事务路径（at-least-once
        语义下回调可能重复）。

        线程安全：与 _ensure_producer 共用 self._lock，重置时持锁；旧 producer 的
        close 在锁外执行（close 可能阻塞，不持锁避免 _ensure_producer 长等待）。
        _init_producer 在锁外调用（内部有 15s 超时保护，不会无限阻塞）。
        """
        with self._lock:
            old_producer = self._producer
            self._producer = None
            self._initialized = False
            self._transactional_ok = False
        # 锁外 close 旧 producer（close 可能阻塞，不持锁）
        if old_producer is not None:
            try:
                old_producer.close(timeout=2)
            except Exception as close_err:
                logger.warning(
                    f"[producer] 重置时关闭旧 producer 失败（忽略）: {safe_str(close_err)}"
                )
        logger.info("[producer] producer 状态已重置，立即重新初始化（新 PID）")
        # 立即重新初始化：避免后续文档回调因 _transactional_ok=False 而降级为非事务路径
        # （_init_producer 内部先建非事务 producer 保证可用，再带 15s 超时尝试事务化，不会无限阻塞）
        self._init_producer()
        self._initialized = self._producer is not None
