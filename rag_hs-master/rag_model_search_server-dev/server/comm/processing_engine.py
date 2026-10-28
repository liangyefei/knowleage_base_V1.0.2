#!/usr/bin/env python3
# @Time : 2026/8/20
# @Author : Hetao
# @File : [Code] --> processing_engine.py
# @Software : PyCharm
# @Function: Kafka 消息处理引擎。解耦 poll 与 exec：
#   - dispatch(msg) 由 poller 调用，按 eventType 分流到控制车道（串行）或文档车道（并行）
#   - KBGate 保证 KB_CREATED 先于文档事件、KB_DELETED 拒绝新事件并等在途完成
#   - OffsetTracker 按分区跟踪已完成 offset，连续前缀推进后提交
#   - VLM 调用并发由 Semaphore 限流
import threading
import time
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

from kafka.structs import OffsetAndMetadata, TopicPartition

from configs import (
    COMM_KAFKA_CALLBACK_MAX_RETRIES,
    COMM_KAFKA_CONTROL_WORKER_POOL_SIZE,
    COMM_KAFKA_DOC_WORKER_POOL_SIZE,
    COMM_KAFKA_SHUTDOWN_TIMEOUT_MS,
    COMM_KAFKA_VLM_CONCURRENCY_LIMIT,
    logger,
)
from server.comm.callback_utils import (
    NO_CALLBACK_EVENTS,
    _format_duration_hms,
    _now_iso,
    build_completed_payload,
    build_failed_payload,
    safe_json_dumps,
    safe_str,
)
from server.comm.kb_gate import KBGate
from server.comm.offset_tracker import OffsetTracker
from server.comm.task_factory import KafkaTaskFactory
from server.utils import BaseResponse

# 控制类事件（KB 生命周期），走 control_executor 串行处理
CONTROL_EVENTS = {"KB_CREATED", "KB_UPDATED", "KB_DELETED"}

# 需 VLM 的文档事件（用 Semaphore 限流）
VLM_EVENTS = {"DOCUMENT_PUBLISHED", "DOCUMENT_RETRY_PROCESS"}


class ProcessingEngine:
    """Kafka 消息处理引擎：调度 + 并行执行 + 顺序门 + offset 跟踪。

    与 KafkaPollerThread 配合：poller 负责按分区顺序 poll 并调 engine.dispatch(msg)，
    engine 负责按 eventType 分流、并行执行、回调投递、offset 提交。
    """

    def __init__(self, producer, commit_fn=None, config=None, group_id=None):
        """
        Args:
            producer: KafkaCallbackProducer 实例（回调投递 + 事务化 offset 提交）
            commit_fn: 回调函数 (offsets_dict) -> None，事务化后保留为 None（兼容测试签名，
                       正常流程不调用，wait_shutdown 兜底改用 poller.commit_offsets）。
            config: 配置 dict
            group_id: consumer group_id，事务化 offset 提交用（send_offsets_to_transaction）
        """
        self._producer = producer
        self._commit_fn = commit_fn
        self._config = config or {}
        self._group_id = group_id

        # KB 级顺序门：submit_fn 由 engine 提供，路由到固定 executor；
        # on_discard_fn 用于 on_kb_deleted 丢弃 pending 时推进 offset，避免 pending 消息卡死
        self._gate = KBGate(
            submit_fn=None,
            on_discard_fn=self._on_pending_discard,
        )

        # 文档车道：N 个单线程 executor，hash(documentId) % N 路由，保证同 doc FIFO + 跨 doc 并行
        self._doc_pool_size = int(COMM_KAFKA_DOC_WORKER_POOL_SIZE) if COMM_KAFKA_DOC_WORKER_POOL_SIZE else 16
        self._doc_executors = [
            ThreadPoolExecutor(max_workers=1, thread_name_prefix=f"DocWorker-{i}")
            for i in range(self._doc_pool_size)
        ]

        # 控制车道：单线程串行（KB 生命周期事件少，无需并行）
        control_size = int(COMM_KAFKA_CONTROL_WORKER_POOL_SIZE) if COMM_KAFKA_CONTROL_WORKER_POOL_SIZE else 1
        self._control_executor = ThreadPoolExecutor(
            max_workers=max(1, control_size), thread_name_prefix="ControlWorker"
        )

        # VLM 并发限流
        vlm_limit = int(COMM_KAFKA_VLM_CONCURRENCY_LIMIT) if COMM_KAFKA_VLM_CONCURRENCY_LIMIT else 8
        self._vlm_semaphore = threading.Semaphore(vlm_limit)

        # offset 跟踪
        self._offset_tracker = OffsetTracker()

        # per-kb_id in-flight 文档计数（KB_DELETED 等在途完成用）
        self._inflight_kb: dict = {}
        self._inflight_kb_lock = threading.Lock()
        self._inflight_kb_cond = threading.Condition(self._inflight_kb_lock)

        # 全局 in-flight 文档计数（优雅停止等待用）
        self._inflight_doc = 0
        self._inflight_doc_cond = threading.Condition()

        # 控制车道待完成计数（优雅停止先等控制车道排空，保证 KB_CREATED flush + KB_DELETED 等 docs 完成后再提交 offset）
        self._pending_control = 0
        self._pending_control_cond = threading.Condition()

        self._running = True
        self._commit_lock = threading.Lock()  # 序列化 commit 调用

        # 回调投递重试计数（按 (TopicPartition, offset) 跟踪），达阈值后强制推进 offset + 死信日志，
        # 打破"回调永久失败 → offset 永不推进 → Kafka at-least-once 无限重投递 → 每轮重跑 task.run"的死循环。
        # max_retries <= 0 时退化为纯 at-least-once（永不强制推进，保留死循环风险，向后兼容用）。
        self._callback_retry: dict = {}  # TopicPartition -> {offset: retry_count}
        self._callback_retry_lock = threading.Lock()
        self._callback_max_retries = int(COMM_KAFKA_CALLBACK_MAX_RETRIES) if COMM_KAFKA_CALLBACK_MAX_RETRIES else 5

    @staticmethod
    def _build_callback_envelope(event_type, kb_id, document_id, payload):
        """构造回调信封（与 KafkaCallbackProducer.send_callback 的 envelope 形状一致）。"""
        return {
            "messageId": str(uuid.uuid4()),
            "eventType": event_type,
            "kbId": kb_id,
            "documentId": document_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "payload": payload or {},
        }

    # ==================== 主入口 ====================

    def dispatch(self, msg) -> None:
        """poller 调用：按 eventType 分流。不阻塞（提交到 executor 即返回）。"""
        if not self._running:
            return
        tp = TopicPartition(msg.topic, msg.partition)
        self._offset_tracker.on_dispatch(tp, msg.offset)

        envelope = msg.value
        if not isinstance(envelope, dict):
            logger.error(f"消息非 dict，commit 跳过: {safe_str(envelope)}")
            self._finish_skip(msg, tp, BaseResponse(code=200, msg="非 dict，跳过", data=None))
            return

        message_id = envelope.get("messageId")
        event_type = envelope.get("eventType")
        kb_id = envelope.get("kbId")
        document_id = envelope.get("documentId")
        logger.info(
            f"[engine] 派发: messageId={message_id}, eventType={event_type}, "
            f"kbId={kb_id}, documentId={document_id}, offset={msg.offset}, tp={tp}"
        )

        # 任务分发：create_task 仅实例化（无 I/O），异常为永久消息错误
        try:
            task = KafkaTaskFactory.create_task(envelope)
        except Exception as e:
            logger.error(
                f"[engine] 任务分发失败（永久消息错误），skip: messageId={message_id}, error={safe_str(e)}",
                exc_info=True,
            )
            self._finish_skip(msg, tp, BaseResponse(code=500, msg=f"分发失败: {e}", data=None))
            return
        logger.debug(
            f"[engine] 任务创建成功: messageId={message_id}, eventType={event_type}, offset={msg.offset}"
        )

        if event_type in CONTROL_EVENTS:
            # KB_CREATED：同步 mark_initializing，保证后续 doc 事件看到 initializing 入等待队列
            if event_type == "KB_CREATED":
                self._gate.mark_initializing(kb_id)
            # KB_DELETED 的 on_kb_deleted 不在 dispatch 调，交给 _run_control_task
            # （control_executor 单线程串行，保证 KB_CREATED 的 create_kb + flush 先完成）
            with self._pending_control_cond:
                self._pending_control += 1
            self._control_executor.submit(self._run_control_task, msg, task, tp)
        else:
            # 文档类：通过 gate 路由（闭包捕获 task）
            def submit_fn(document_id_, msg_, task_=task):
                self._submit_doc_task(document_id_, msg_, task_)

            ok = self._gate.acquire(kb_id, document_id, msg, submit_fn=submit_fn)
            if not ok:
                # KB deleting/deleted：按 no-op 成功回调
                self._finish_skip(
                    msg, tp, BaseResponse(code=200, msg="KB 已删除，跳过文档事件", data=None)
                )

    # ==================== 优雅停止 ====================

    def wait_shutdown(self, timeout_ms=None) -> None:
        """优雅停止：停止派发，等控制车道排空 + 在途文档任务完成或超时，提交所有已推进 offset，关闭 executor。"""
        self._running = False
        deadline = None if timeout_ms is None else time.time() + timeout_ms / 1000.0

        # 先等控制车道排空：保证 KB_CREATED 的 create_kb + flush 已完成（submit 所有 doc 任务），
        # 且 KB_DELETED 的 _wait_inflight_zero 已完成（doc 任务全部跑完）。
        with self._pending_control_cond:
            while self._pending_control > 0:
                if deadline is None:
                    self._pending_control_cond.wait(timeout=5.0)
                else:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        logger.warning(
                            f"[engine] wait_shutdown 控制车道等待超时，剩余 pending_control={self._pending_control}"
                        )
                        break
                    self._pending_control_cond.wait(timeout=min(5.0, remaining))

        # 等待在途文档任务（KB_DELETED 已等过该 KB 的在途，但保险起见再等全局）
        with self._inflight_doc_cond:
            while self._inflight_doc > 0:
                if deadline is None:
                    self._inflight_doc_cond.wait(timeout=5.0)
                else:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        logger.warning(
                            f"[engine] wait_shutdown 在途文档等待超时，剩余 in-flight={self._inflight_doc}"
                        )
                        break
                    self._inflight_doc_cond.wait(timeout=min(5.0, remaining))

        # 关闭 executor（不阻塞，已等在途）
        for ex in self._doc_executors:
            try:
                ex.shutdown(wait=False)
            except Exception as e:
                logger.warning(f"[processing-engine] failed to shutdown doc executor: {safe_str(e)}")
                pass
        try:
            self._control_executor.shutdown(wait=False)
        except Exception as e:
            logger.warning(f"[processing-engine] failed to shutdown control executor: {safe_str(e)}")
            pass

        # 事务化：正常流程每条消息已事务提交，shutdown 时理论上无残余。
        # 兜底防御异常情况：用 poller.commit_offsets 提交残余 offset（非事务）。
        offsets = self._offset_tracker.get_commit_offsets()
        if offsets:
            fallback_commit = getattr(self, "_poller", None)
            if fallback_commit is not None and hasattr(fallback_commit, "commit_offsets"):
                with self._commit_lock:
                    try:
                        fallback_commit.commit_offsets(offsets)
                        logger.info(f"[engine] wait_shutdown 兜底提交 offsets: {list(offsets.items())}")
                    except Exception as e:
                        logger.error(f"[engine] wait_shutdown 兜底 commit 失败: {safe_str(e)}")
            elif self._commit_fn is not None:
                # 向后兼容：_poller 未注入时退用 _commit_fn（e2e 测试走此分支）
                with self._commit_lock:
                    try:
                        self._commit_fn(offsets)
                        logger.info(f"[engine] wait_shutdown 提交 offsets: {list(offsets.items())}")
                    except Exception as e:
                        logger.error(f"[engine] wait_shutdown commit 失败: {safe_str(e)}")
        logger.info("[engine] wait_shutdown 完成")

    # ==================== 控制车道（串行） ====================

    def _run_control_task(self, msg, task, tp) -> None:
        """控制车道 worker：执行 KB 生命周期事件，完成后回调 + offset 推进。"""
        envelope = msg.value
        event_type = envelope.get("eventType")
        kb_id = envelope.get("kbId")
        logger.info(
            f"[engine] 控制任务开始: eventType={event_type}, kbId={kb_id}, offset={msg.offset}"
        )
        try:
            if event_type == "KB_DELETED":
                # 先置 deleting（拒绝新事件、discard pending + 调 on_discard_fn 推进 offset），
                # 再等在途文档完成，最后执行 delete_kb，避免删除时在途向量化写入竞态。
                # 注意：on_kb_deleted 必须在 control_executor 串行执行，保证 KB_CREATED 的
                # on_kb_created + flush 先完成（pending 已空）。
                self._gate.on_kb_deleted(kb_id)
                wait_timeout = (int(COMM_KAFKA_SHUTDOWN_TIMEOUT_MS) if COMM_KAFKA_SHUTDOWN_TIMEOUT_MS else 7200000) / 1000.0  # pylint: disable=line-too-long
                if not self._wait_inflight_zero(kb_id, timeout=wait_timeout):
                    # 超时：在途文档任务卡死（如 VLM 调用挂起），等下去也无意义。
                    # 处理策略（避免永久卡死该 KB + 该分区）：
                    #   1. reset gate 状态：允许后续同 KB 文档事件正常处理（不再被 deleting 拒绝）
                    #   2. 不执行 delete_kb：保留 KB（向量化数据残留，需人工介入清理）
                    #   3. 推进 offset + 投递 PROCESSING_FAILED 回调：告知平台删除未执行，依赖人工清理
                    #   4. 不阻塞分区：offset 推进后，后续消息（含 KB_CREATED 重建）能正常处理
                    # 取代旧策略"保留 deleting 状态 + 不推进 offset"（会导致 KB 永久拒绝 + 分区永久卡死）。
                    logger.error(
                        f"[engine] KB_DELETED 等在途文档超时，reset gate + 推进 offset + 投递 FAILED，"
                        f"KB 残留需人工介入清理: kbId={kb_id}"
                    )
                    self._gate.reset(kb_id)
                    self._finish_skip(
                        msg, tp,
                        BaseResponse(code=500, msg=f"KB_DELETED 等在途超时，未执行删除: {kb_id}", data=None),
                    )
                    return  # 不执行 delete_kb，但 offset 已推进、gate 已 reset

            result = task.run(envelope)  # 内部已 try/except，返回 {code,msg,data}

            if event_type == "KB_CREATED" and result.get("code") == 200:
                # create_kb 成功：置 ready 并 flush 等待队列
                self._gate.on_kb_created(kb_id)
            elif event_type == "KB_DELETED":
                # delete_kb 完成：在途已等完，状态保持 deleting（继续拒绝新事件）
                logger.info(f"[engine] KB_DELETED 完成 kbId={kb_id}")

            self._finish_task(msg, result, event_type)
        finally:
            with self._pending_control_cond:
                self._pending_control -= 1
                if self._pending_control <= 0:
                    self._pending_control_cond.notify_all()

    # ==================== 文档车道（并行 + FIFO） ====================

    def _submit_doc_task(self, document_id, msg, task) -> None:
        """gate 调用（state=ready 或 flush 等待队列时）：路由到固定 executor。

        zlib.crc32(document_id) % pool_size 保证同 documentId FIFO（同一 executor 串行），
        不同 documentId 路由到不同 executor，跨文档并行。使用 crc32 而非内置 hash()，
        避免 PYTHONHASHSEED 随机化导致重启后路由变化。
        """
        kb_id = msg.value.get("kbId")
        self._incr_inflight(kb_id)
        with self._inflight_doc_cond:
            self._inflight_doc += 1
        if document_id is None:
            # 无 documentId 时按 kb_id 路由，避免全部集中到 executor 0
            idx = zlib.crc32((kb_id or "").encode("utf-8")) % self._doc_pool_size
        else:
            idx = zlib.crc32(document_id.encode("utf-8")) % self._doc_pool_size
        logger.debug(
            f"[engine] 文档任务提交: documentId={document_id}, kbId={kb_id}, "
            f"executor={idx}, offset={msg.offset}"
        )
        self._doc_executors[idx].submit(self._run_doc_task, msg, task, kb_id, document_id)

    def _run_doc_task(self, msg, task, kb_id, document_id) -> None:
        """文档车道 worker：执行任务（VLM 事件加 Semaphore 限流），完成后回调 + offset 推进 + in-flight 递减。

        注意：_finish_task 必须在 in-flight 递减之前调用——_finish_task 内部推进 offset，
        若先递减 in-flight 再 _finish_task，wait_shutdown 可能在 offset 推进前就提交，
        导致"提交早于 offset 推进"的竞态（测试 test_kb_deleted_timeout_resets_gate_and_advances_offset 曾因此 flaky）。
        """
        event_type = msg.value.get("eventType")
        # 记录任务开始时间，用于构造回调 metrics（总时长 + 回调时间点）
        task_start_time = time.time()
        try:
            logger.info(
                f"[engine] 文档任务开始: eventType={event_type}, documentId={document_id}, "
                f"kbId={kb_id}, offset={msg.offset}"
            )
            if event_type in VLM_EVENTS:
                with self._vlm_semaphore:
                    result = task.run(msg.value)
            else:
                result = task.run(msg.value)
            logger.info(
                f"[engine] 文档任务完成: eventType={event_type}, documentId={document_id}, "
                f"code={result.get('code')}, offset={msg.offset}, "
                f"durationMs={int((time.time() - task_start_time) * 1000)}"
            )
            # 先完成回调投递 + offset 推进（在递减 in-flight 之前，避免 wait_shutdown 提交早于 offset 推进）
            self._finish_task(msg, result, event_type, task_start_time)
        except Exception as e:
            # task.run 或 _finish_task 异常：offset 必须推进，否则运行时该分区 base 永久不前进，
            # 后续乱序完成的 offset 全积压在 OffsetTracker._done，分区运行时卡死
            # （只有重启后 Kafka 重投递才能恢复，运行时无法自愈）。
            # 兜底：投递 PROCESSING_FAILED 回调 + 事务化推进 offset（_finish_skip 内部走
            # _execute_transaction，有重试 + 死信路径，最终会 commit offset 到 broker）。
            logger.error(
                f"[engine] 文档任务异常: eventType={event_type}, documentId={document_id}, "
                f"kbId={kb_id}, offset={msg.offset}, error={safe_str(e)}",
                exc_info=True,
            )
            tp = TopicPartition(msg.topic, msg.partition)
            try:
                self._finish_skip(
                    msg, tp,
                    BaseResponse(code=500, msg=f"文档任务异常: {safe_str(e)}", data=None),
                    task_start_time=task_start_time,
                )
            except Exception as skip_err:
                # _finish_skip 也失败（极端情况）：强制推进内存 base，避免分区永久卡死
                # （offset 未 commit broker，重启后会重投递，但运行时不卡死后续 offset）
                logger.critical(
                    f"[engine] 兜底 _finish_skip 也失败，强制推进 offset: "
                    f"tp={tp}, offset={msg.offset}, skip_err={safe_str(skip_err)}"
                )
                self._advance_offset(tp, msg.offset)
        finally:
            self._decr_inflight(kb_id)
            with self._inflight_doc_cond:
                self._inflight_doc -= 1
                if self._inflight_doc <= 0:
                    self._inflight_doc_cond.notify_all()

    # ==================== 完成处理（回调 + offset 推进） ====================

    def _finish_task(self, msg, result, event_type, task_start_time: float = None) -> None:
        """任务完成后：事务化投递回调 + offset 提交（_execute_transaction 原子完成）。

        Args:
            task_start_time: 任务开始时间戳（time.time()），用于计算总时长。
                            None 时跳过 metrics 字段（向后兼容）。
        """
        tp = TopicPartition(msg.topic, msg.partition)
        envelope = msg.value
        kb_id = envelope.get("kbId")
        document_id = envelope.get("documentId")

        if event_type not in NO_CALLBACK_EVENTS and self._producer is not None:
            code = result.get("code")
            # 构造 metrics：总时长（HH:MM:SS）+ 回调时间点。task_start_time 缺失时不传 metrics。
            metrics = None
            if task_start_time is not None:
                metrics = {
                    "totalDuration": _format_duration_hms(time.time() - task_start_time),
                    "callbackedAt": _now_iso(),
                }
            if code == 200:
                callback_event = "PROCESSING_COMPLETED"
                payload = build_completed_payload(result.get("data"), metrics=metrics)
            else:
                callback_event = "PROCESSING_FAILED"
                payload = build_failed_payload(code, result.get("msg"), metrics=metrics)
            logger.info(
                f"[engine] 回调被触发: eventType={event_type}, callback_event={callback_event}, "
                f"code={code}, kbId={kb_id}, documentId={document_id}, offset={msg.offset}"
                + (f", totalDuration={metrics['totalDuration']}" if metrics else "")
                + f", payload={safe_json_dumps(payload)}"
            )
            # 构造回调 envelope（事务化：一次事务完成回调投递 + offset 提交）
            callback_value = self._build_callback_envelope(callback_event, kb_id, document_id, payload)
            callback_key = document_id or kb_id
            # _execute_transaction 成功 → 内部已推进 base；失败未达阈值 → return（重投递自愈）
            self._execute_transaction(tp, msg.offset, callback_key, callback_value)
            return

        # 重投递前置检测（与 _execute_transaction 一致）：offset < base 说明是已处理过的老消息，
        # 跳过 _commit_offset_fallback（避免提交回退的 offset+1 到 broker 引发死循环）。
        # 此处同时覆盖 NO_CALLBACK 与 producer is None 两条分支（两者均调 _commit_offset_fallback
        # 提交 offset+1，同样会触发 broker committed 回退 → consumer 一直 poll 老消息死循环）。
        # on_complete 在 offset < base 时返回 None，_advance_offset 不会推进，故此处直接 return。
        if self._offset_tracker.is_redelivery(tp, msg.offset):
            logger.info(
                f"[engine] 重投递跳过 NO_CALLBACK commit（offset < base，避免 broker committed 回退）: "
                f"tp={tp}, offset={msg.offset}, base={self._offset_tracker.get_base(tp)}, "
                f"eventType={event_type}"
            )
            return

        # 无回调事件（KB_UPDATED 等）或 producer 未初始化：仅推进 offset
        if event_type in NO_CALLBACK_EVENTS:
            logger.info(
                f"[engine] 跳过回调（NO_CALLBACK_EVENTS）: eventType={event_type}, "
                f"kbId={kb_id}, documentId={document_id}, offset={msg.offset}"
            )
            # NO_CALLBACK_EVENTS 不投递回调，不走事务路径，需主动 commit offset 到 broker，
            # 否则重启后 consumer 从旧 committed offset 开始，重复消费 KB_UPDATED/DOCUMENT_ARCHIVED
            self._commit_offset_fallback(tp, msg.offset)
        elif self._producer is None:
            # producer 未初始化（init_transactions 失败或 bootstrap_servers 未配置）：
            # 回调链路被静默跳过——后端永远收不到这条消息的回调。
            # 这里仅推进 offset（避免无限重投递），但回调丢失，需排查 producer 初始化日志。
            logger.error(
                f"[engine] 跳过回调（producer 未初始化，回调丢失）: eventType={event_type}, "
                f"kbId={kb_id}, documentId={document_id}, offset={msg.offset}"
            )
            self._commit_offset_fallback(tp, msg.offset)
        self._advance_offset(tp, msg.offset)

    def _finish_skip(self, msg, tp, result, task_start_time: float = None) -> None:
        """永久错误/no-op 成功：事务化投递回调 + offset 提交。

        - 非 dict / NO_CALLBACK_EVENTS 事件：仅推进 offset
        - code==200：投递 PROCESSING_COMPLETED（no-op 成功）
        - code!=200：投递 PROCESSING_FAILED（永久消息错误）

        Args:
            task_start_time: 任务开始时间戳，用于构造 metrics（总时长+回调时间点）。
        """
        envelope = msg.value if isinstance(msg.value, dict) else {}
        event_type = envelope.get("eventType")
        document_id = envelope.get("documentId")
        kb_id = envelope.get("kbId")

        if isinstance(msg.value, dict) and event_type not in NO_CALLBACK_EVENTS \
                and self._producer is not None:
            # 构造 metrics（与 _finish_task 保持一致）
            metrics = None
            if task_start_time is not None:
                metrics = {
                    "totalDurationMs": int((time.time() - task_start_time) * 1000),
                    "callbackedAt": _now_iso(),
                }
            if result.code == 200:
                callback_event = "PROCESSING_COMPLETED"
                payload = build_completed_payload(None, metrics=metrics)
            else:
                callback_event = "PROCESSING_FAILED"
                payload = build_failed_payload(result.code, result.msg, metrics=metrics)
            callback_value = self._build_callback_envelope(callback_event, kb_id, document_id, payload)
            callback_key = document_id or kb_id
            self._execute_transaction(tp, msg.offset, callback_key, callback_value)
            return

        self._advance_offset(tp, msg.offset)

    def _execute_transaction(self, tp, offset, callback_key, callback_value) -> bool:
        """事务化投递回调 + 提交 offset，带重试计数与死信。

        替换 _deliver_callback 的重试计数逻辑，事务原子性保证回调投递与 offset 提交一致。

        Returns:
            True: offset 已推进（事务成功，或达到最大重试次数后强制推进并记死信日志）
            False: offset 未推进（事务失败但未达阈值，依赖 Kafka at-least-once 重投递）

        注意：成功路径与死信路径均已在内部调 _advance_offset 推进内存 base，
        调用方（_finish_task / _finish_skip）无需再调 _advance_offset。

        死循环打破策略：
        - 重投递（offset < base）：跳过事务，直接 return True（offset 已在之前处理过，
          再次提交会回退 broker committed offset，导致 consumer 一直 poll 老消息死循环）
        - 事务成功：清理 retry_count + _advance_offset 推进 base（offset 已由事务提交到 broker）→ return True
        - 事务失败 + retry_count < max_retries：递增 retry_count → return False（等重投递自愈）
        - 事务失败 + retry_count >= max_retries：强制 poller.commit_offsets（放弃回调投递，非事务提交到 broker）
          + 死信日志 + _advance_offset 推进 base + 清理 retry_count → return True
        - max_retries <= 0：永不强制推进，return False（向后兼容纯 at-least-once）
        """
        # 重投递前置检测：offset < base 说明是已处理过的老消息（rebalance / at-least-once 重投递）。
        # 必须在事务提交之前拦截，避免事务提交回退的 offset（offset+1 <= base）导致
        # broker committed offset 回退，引发"consumer 一直 poll 老消息，向量化无限重跑"的死循环。
        # 注意：此检测同时覆盖事务路径与降级路径（降级路径的 _commit_offset_fallback 也会提交
        # 回退的 offset 到 broker，同样会触发死循环）。
        if self._offset_tracker.is_redelivery(tp, offset):
            logger.info(
                f"[engine] 重投递跳过事务（offset < base，避免 broker committed 回退死循环）: "
                f"tp={tp}, offset={offset}, base={self._offset_tracker.get_base(tp)}, "
                f"callback_key={callback_key}"
            )
            return True

        # 事务降级：init_transactions 失败（broker 不支持事务 / 单节点无 coordinator）时，
        # 走 send_callback（非事务，同步等 ACK）+ poller.commit_offsets 分离提交，
        # 保证回调不丢失（at-least-once，回调可能重复但后端能收到）。
        if not self._producer.is_transactional_available():
            logger.warning(
                f"[engine] 事务不可用，降级非事务投递回调: tp={tp}, offset={offset}, "
                f"callback_key={callback_key}"
            )
            ok = self._producer.send_callback(
                callback_value.get("eventType") if isinstance(callback_value, dict) else None,
                callback_value.get("kbId"),
                callback_value.get("documentId"),
                callback_value.get("payload", {}),
            )
            if ok:
                self._clear_retry_count(tp, offset)
                self._commit_offset_fallback(tp, offset)
                self._advance_offset(tp, offset)
                logger.info(
                    f"[engine] 降级回调已投递（非事务）: tp={tp}, offset={offset}, "
                    f"callback_key={callback_key}"
                )
                return True
            return self._handle_callback_failure(tp, offset, callback_key, callback_value, RuntimeError("send_callback failed"))  # pylint: disable=line-too-long

        try:
            offsets = {tp: OffsetAndMetadata(offset + 1, b"")}  # 提交到 offset+1（下一次消费位置）
            self._producer.send_callback_transactional(
                key=callback_key, value=callback_value,
                offsets=offsets, group_id=self._group_id,
            )
        except Exception as e:
            return self._handle_callback_failure(tp, offset, callback_key, callback_value, e)

        # 事务成功：清理 retry + 推进内存 base（offset 已由事务提交到 broker）
        self._clear_retry_count(tp, offset)
        self._advance_offset(tp, offset)
        logger.info(
            f"[engine] 事务成功（回调已投递+offset已提交）: tp={tp}, offset={offset}, "
            f"callback_key={callback_key}, callback_event={callback_value.get('eventType') if isinstance(callback_value, dict) else None}"  # pylint: disable=line-too-long
        )
        return True

    def _handle_callback_failure(self, tp, offset, callback_key, callback_value, err) -> bool:
        """回调投递失败统一处理：重试计数 + 达阈值后死信。

        事务路径与降级路径失败后均走此方法，保证重试策略一致。
        """
        retry_count = self._incr_retry_count(tp, offset)
        max_retries = self._callback_max_retries

        if max_retries <= 0:
            logger.error(
                f"[engine] 回调失败（max_retries<=0 不强制推进，将重投递）: "
                f"tp={tp}, offset={offset}, retry={retry_count}, error={safe_str(err)}",
                exc_info=True,
            )
            return False

        if retry_count < max_retries:
            logger.error(
                f"[engine] 回调失败（retry={retry_count}/{max_retries}，未达阈值，将重投递）: "
                f"tp={tp}, offset={offset}, error={safe_str(err)}",
                exc_info=True,
            )
            return False

        # 达到阈值：强制提交 offset 到 broker（放弃回调投递）+ 死信日志 + 推进 base + 清理 retry_count
        self._commit_offset_fallback(tp, offset)
        try:
            envelope_json = safe_json_dumps(callback_value)
        except Exception as json_err:
            envelope_json = f"<序列化失败: {json_err}>"
        callback_event = callback_value.get("eventType") if isinstance(callback_value, dict) else None
        logger.error(
            f"[engine] 回调达最大重试次数 {max_retries}，强制提交 offset（放弃回调投递）+ 死信日志（人工介入重放）: "
            f"tp={tp}, offset={offset}, callback_key={callback_key}, "
            f"callback_event={callback_event}, envelope={envelope_json}"
        )
        self._clear_retry_count(tp, offset)
        self._advance_offset(tp, offset)
        return True

    def _commit_offset_fallback(self, tp, offset) -> None:
        """非事务提交 offset（降级路径与死信路径共用）。"""
        dead_offsets = {tp: OffsetAndMetadata(offset + 1, b"")}
        fallback_commit = getattr(self, "_poller", None)
        if fallback_commit is not None and hasattr(fallback_commit, "commit_offsets"):
            try:
                fallback_commit.commit_offsets(dead_offsets)
            except Exception as commit_err:
                logger.error(f"[engine] poller.commit_offsets 失败: {safe_str(commit_err)}")
        else:
            logger.error(
                f"[engine] poller 未注入，offset 无法提交: tp={tp}, offset={offset}"
            )

    def _incr_retry_count(self, tp, offset) -> int:
        """递增 (tp, offset) 的回调投递重试次数，返回递增后的值。"""
        with self._callback_retry_lock:
            per_tp = self._callback_retry.setdefault(tp, {})
            cnt = per_tp.get(offset, 0) + 1
            per_tp[offset] = cnt
            return cnt

    def _clear_retry_count(self, tp, offset) -> None:
        """清理 (tp, offset) 的重试计数（投递成功或达阈值强制推进后）。"""
        with self._callback_retry_lock:
            per_tp = self._callback_retry.get(tp)
            if not per_tp:
                return
            if offset in per_tp:
                per_tp.pop(offset, None)
                if not per_tp:
                    self._callback_retry.pop(tp, None)

    def _on_pending_discard(self, document_id, msg) -> None:
        """KBGate.on_kb_deleted 丢弃 pending 文档事件时回调：推进 offset + no-op 成功回调。

        pending 中的文档事件在 acquire 时返回 True（已入等待队列），dispatch 不会再调 _finish_skip。
        若 on_kb_deleted 直接丢弃 pending 而不推进 offset，会导致该分区 offset 卡在 pending
        最小 offset 之前（即使后面的消息都完成也无法推进），需重启重投递才能解锁。
        """
        tp = TopicPartition(msg.topic, msg.partition)
        self._finish_skip(
            msg, tp,
            BaseResponse(code=200, msg="KB 已删除，pending 丢弃", data=None),
        )

    def _advance_offset(self, tp, offset) -> None:
        """推进 offset：调 OffsetTracker.on_complete 推进内存 base。

        事务化后 offset 提交由 producer 的 send_callback_transactional 事务完成，
        此处仅推进内存 base（_commit_fn 不再调用，保留字段为 None 兼容测试签名）。
        """
        advance = self._offset_tracker.on_complete(tp, offset)
        if advance is None:
            return
        # 仅推进内存 base，offset 提交已由 producer 事务完成
        # （advance 返回的 new_base 仅用于日志/调试，无需再调 _commit_fn）
        tp_key, new_base = advance
        logger.debug(f"[engine] offset 推进 tp={tp_key}, base={new_base}（事务已提交）")

    # ==================== per-kb_id in-flight 计数（KB_DELETED 等在途） ====================

    def _incr_inflight(self, kb_id) -> None:
        if kb_id is None:
            return
        with self._inflight_kb_cond:
            self._inflight_kb[kb_id] = self._inflight_kb.get(kb_id, 0) + 1

    def _decr_inflight(self, kb_id) -> None:
        if kb_id is None:
            return
        with self._inflight_kb_cond:
            cnt = self._inflight_kb.get(kb_id, 0) - 1
            if cnt <= 0:
                self._inflight_kb.pop(kb_id, None)
                self._inflight_kb_cond.notify_all()
            else:
                self._inflight_kb[kb_id] = cnt

    def _wait_inflight_zero(self, kb_id, timeout=None) -> bool:
        """等待 kb_id 的在途文档任务归零。返回是否成功（True=归零，False=超时）。"""
        if kb_id is None:
            return True
        deadline = None if timeout is None else time.time() + timeout
        with self._inflight_kb_cond:
            while self._inflight_kb.get(kb_id, 0) > 0:
                if deadline is None:
                    self._inflight_kb_cond.wait()
                else:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        return False
                    if not self._inflight_kb_cond.wait(timeout=remaining):
                        return False
            return True
