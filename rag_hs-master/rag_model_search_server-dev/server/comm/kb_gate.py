#!/usr/bin/env python3
# @Time : 2026/8/20
# @Author : Hetao
# @File : [Code] --> kb_gate.py
# @Software : PyCharm
# @Function: KB 级顺序门。维护 kb_state 状态机，保证 KB_CREATED 先于文档事件、KB_DELETED 晚于在途文档事件。
#           文档级 FIFO 由调用方（ProcessingEngine）的 submit_fn 通过 hash 路由保证，gate 仅负责 KB 屏障与等待队列。
import threading
from collections.abc import Callable
from typing import Any

from configs import logger
from server.comm.callback_utils import safe_str

# KB 状态取值
STATE_INITIALIZING = "initializing"  # KB_CREATED 已收到、create_kb 尚未完成
STATE_READY = "ready"                 # KB 已就绪，文档事件可直接并行
STATE_DELETING = "deleting"            # KB_DELETED 已收到，等在途文档完成
STATE_DELETED = "deleted"              # KB 已删除，后续文档事件拒绝


class KBGate:
    """KB 级顺序门：维护 kb_state[kbId] 状态机与 pending_docs[kbId] 等待队列。

    - 文档事件到达时若 state=ready：直接调 submit_fn(document_id, msg)（非阻塞，由调用方路由到 worker pool）
    - 若 state=initializing：入 pending_docs 等待队列，KB_CREATED 完成后 flush
    - 若 state in (deleting, deleted)：拒绝（返回 False），调用方按 no-op + 成功回调处理
    - 同 documentId 的 FIFO 由调用方的 submit_fn 保证（hash 路由到固定 worker），gate 不在 acquire 持锁期间
      调 submit_fn，避免跨 documentId 被串行化
    """

    def __init__(self, submit_fn: Callable[[str, Any], None],
                 on_discard_fn: Callable[[str, Any], None] = None):
        """
        Args:
            submit_fn: 默认提交回调 (document_id, msg) -> None。acquire 时若未传 submit_fn 则用此默认值。
            on_discard_fn: 丢弃回调 (document_id, msg) -> None。on_kb_deleted 丢弃 pending 等待队列时
                对每个文档事件调用，由调用方推进 offset 并投递 no-op 成功回调，避免 pending 消息
                offset 卡死。为 None 时仅丢弃（不推进 offset，仅用于测试或无回调场景）。
        """
        self._default_submit_fn = submit_fn
        self._on_discard_fn = on_discard_fn
        self._kb_state: dict[str, str] = {}
        self._pending: dict[str, list] = {}  # kb_id -> [(document_id, msg, submit_fn), ...]
        self._lock = threading.Lock()

    def get_state(self, kb_id: str) -> str:
        """获取 KB 状态。未登记的 kbId 默认 ready（兼容先有文档事件、后收到 KB_CREATED 的极端时序）。"""
        with self._lock:
            return self._kb_state.get(kb_id, STATE_READY)

    def reset(self, kb_id: str) -> None:
        """清除该 kbId 的状态与 pending 队列（置为未登记，下次事件默认 ready）。

        供 ProcessingEngine 在 KB_DELETED 等在途超时分支调用：避免保留 deleting 状态
        导致该 KB 后续文档事件被永久拒绝（进程不重启就无法恢复）。reset 后该 KB 残留
        模型端，需人工介入清理，但分区 offset 能推进、其他事件能正常处理。
        """
        with self._lock:
            self._kb_state.pop(kb_id, None)
            self._pending.pop(kb_id, None)
            logger.info(f"KBGate: reset kbId={kb_id}（状态与 pending 已清除）")

    def mark_initializing(self, kb_id: str) -> None:
        """显式标记 KB 为 initializing（KB_CREATED 控制事件派发前调用）。"""
        with self._lock:
            self._kb_state[kb_id] = STATE_INITIALIZING
            logger.debug(f"KBGate: mark_initializing kbId={kb_id}")

    def acquire(self, kb_id: str, document_id: str, msg: Any,
                submit_fn: Callable[[str, Any], None] = None) -> bool:
        """文档事件请求进入执行。

        Returns:
            True  - 已提交（state=ready）或已入等待队列（state=initializing）
            False - 被拒绝（state in deleting/deleted），调用方按 no-op + 成功回调处理
        """
        fn = submit_fn if submit_fn is not None else self._default_submit_fn
        with self._lock:
            state = self._kb_state.get(kb_id, STATE_READY)
            if state in (STATE_DELETING, STATE_DELETED):
                logger.info(
                    f"KBGate: 拒绝文档事件（KB {state}）kbId={kb_id}, documentId={document_id}"
                )
                return False
            if state == STATE_INITIALIZING:
                self._pending.setdefault(kb_id, []).append((document_id, msg, fn))
                logger.debug(
                    f"KBGate: 入队等待 kbId={kb_id}, documentId={document_id}, "
                    f"pending_size={len(self._pending[kb_id])}"
                )
                return True
            # state == ready：不在持锁期间调 submit_fn，避免跨 documentId 串行化
        # 锁外提交
        fn(document_id, msg)
        return True

    def on_kb_created(self, kb_id: str) -> None:
        """KB_CREATED 控制事件执行完成后调用：置 ready，flush 等待队列。

        幂等：重复调用不报错；pending 为空则不 flush。
        """
        with self._lock:
            self._kb_state[kb_id] = STATE_READY
            pending = self._pending.pop(kb_id, [])
            logger.info(
                f"KBGate: on_kb_created kbId={kb_id}, flush pending={len(pending)}"
            )
        # 锁外按入队顺序 flush（保证同 documentId FIFO）
        for document_id, msg, fn in pending:
            fn(document_id, msg)

    def on_kb_deleted(self, kb_id: str) -> None:
        """KB_DELETED 控制事件执行后调用：置 deleting（在途文档任务由 worker pool 自然完成，
        新到达的文档事件被拒绝）。pending 队列对每个文档事件调 on_discard_fn（若构造时传入），
        由调用方推进 offset + 投递 no-op 成功回调，避免 pending 消息 offset 卡死。

        注：KB_DELETED 本身的"等在途完成"由 ProcessingEngine 在执行 delete_kb 前等待
        doc_worker_pool 中该 kbId 的在途任务完成实现，gate 只负责状态标记与拒绝新事件。
        """
        with self._lock:
            self._kb_state[kb_id] = STATE_DELETING
            discarded = self._pending.pop(kb_id, [])
            logger.info(
                f"KBGate: on_kb_deleted kbId={kb_id}, discarded pending={len(discarded)}"
            )
        # 锁外调 discard 回调，推进 offset + 投递 no-op 成功回调，避免 pending 消息卡死
        if self._on_discard_fn and discarded:
            for document_id, msg, _fn in discarded:
                try:
                    self._on_discard_fn(document_id, msg)
                except Exception as e:
                    logger.error(
                        f"KBGate: on_discard_fn 异常 kbId={kb_id}, documentId={document_id}: {safe_str(e)}",
                        exc_info=True,
                    )
