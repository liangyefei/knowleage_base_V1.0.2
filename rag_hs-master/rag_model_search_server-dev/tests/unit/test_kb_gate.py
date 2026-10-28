#!/usr/bin/env python3
"""
KBGate 单元测试：KB 级顺序门（状态机 + 文档级 FIFO 等待队列）。

覆盖 spec.md 场景：
- KB_CREATED 屏障：文档事件在 KB 就绪前排入等待队列，就绪后 flush
- KB 删除中/已删除：拒绝后续文档事件
- 同一 documentId 多条消息按入队顺序提交（flush 时 FIFO）
- 不同 documentId 可并发提交（submit_fn 被并发调用）
- 重复 on_kb_created 幂等
- 未知 KB 默认 ready
"""
# pylint: disable=no-use-sys-path-insert
import sys
import threading
from pathlib import Path

# 将项目根目录加入 sys.path，保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from server.comm.kb_gate import KBGate


class TestKBGate:
    _KB_NAME_KEY = 'k1'
    _DOC_ID_KEY = 'd1'

    @staticmethod
    def test_kb_created_barrier():
        """KB_CREATED 前文档事件入等待队列，on_kb_created 后 flush 提交。"""
        _K1_KEY = 'k1'
        submitted = []
        lock = threading.Lock()

        def submit_fn(document_id, msg):
            with lock:
                submitted.append((document_id, msg))

        gate = KBGate(submit_fn)
        gate.mark_initializing(_K1_KEY)
        assert gate.get_state(_K1_KEY) == "initializing"

        ok = gate.acquire(_K1_KEY, "d1", {"eventType": "DOCUMENT_PUBLISHED"}, submit_fn)
        assert ok is True
        assert not submitted  # 未提交，仍在等待队列

        gate.on_kb_created(_K1_KEY)
        assert submitted == [("d1", {"eventType": "DOCUMENT_PUBLISHED"})]
        assert gate.get_state(_K1_KEY) == "ready"

    @staticmethod
    def test_kb_deleted_wait_inflight():
        """on_kb_deleted 置 deleting，后续文档事件被拒绝。"""
        submitted = []

        def submit_fn(document_id, msg):
            submitted.append(document_id)

        gate = KBGate(submit_fn)
        gate.on_kb_deleted(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "deleting"

        ok = gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, {"eventType": "DOCUMENT_PUBLISHED"}, submit_fn)
        assert ok is False
        assert not submitted

    @staticmethod
    def test_same_document_id_fifo():
        """同一 documentId 两条事件按入队顺序提交（flush 时 FIFO）。"""
        order = []
        lock = threading.Lock()

        def submit_fn(document_id, msg):
            with lock:
                order.append((document_id, msg))

        gate = KBGate(submit_fn)
        gate.mark_initializing(TestKBGate._KB_NAME_KEY)
        gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, "msg1", submit_fn)
        gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, "msg2", submit_fn)

        gate.on_kb_created(TestKBGate._KB_NAME_KEY)  # flush 等待队列
        assert order == [(TestKBGate._DOC_ID_KEY, "msg1"), (TestKBGate._DOC_ID_KEY, "msg2")]

    @staticmethod
    def test_cross_document_id_parallel():
        """不同 documentId 可同时提交（验证 submit_fn 被并发调用）。"""
        entered = []
        lock = threading.Lock()
        both_entered = threading.Event()

        def submit_fn(document_id, msg):
            with lock:
                entered.append(document_id)
                if len(entered) >= 2:
                    both_entered.set()
            # 阻塞直到两个 documentId 都进入 submit_fn；若被串行化则永远等不到
            both_entered.wait(timeout=5.0)

        gate = KBGate(submit_fn)
        t1 = threading.Thread(target=gate.acquire, args=("k1", "d1", "m1", submit_fn))
        t2 = threading.Thread(target=gate.acquire, args=("k1", "d2", "m2", submit_fn))
        t1.start()
        t2.start()
        t1.join(timeout=10.0)
        t2.join(timeout=10.0)

        assert both_entered.is_set()  # 两个 documentId 并发进入 submit_fn
        assert sorted(entered) == ["d1", "d2"]

    @staticmethod
    def test_duplicate_kb_created_idempotent():
        """state 已 ready 时再次 on_kb_created 不报错，pending 队列已空则不 flush。"""
        submitted = []

        def submit_fn(document_id, msg):
            submitted.append(document_id)

        gate = KBGate(submit_fn)
        gate.on_kb_created(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "ready"

        gate.on_kb_created(TestKBGate._KB_NAME_KEY)  # 重复调用不报错
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "ready"
        assert not submitted  # pending 为空，不 flush

    @staticmethod
    def test_unknown_kb_defaults_ready():
        """未收过 KB_CREATED 的 kbId，文档事件直接提交（state 默认 ready）。"""
        submitted = []

        def submit_fn(document_id, msg):
            submitted.append((document_id, msg))

        gate = KBGate(submit_fn)
        ok = gate.acquire("k_unknown", "d1", "msg", submit_fn)
        assert ok is True
        assert submitted == [("d1", "msg")]
        assert gate.get_state("k_unknown") == "ready"

    # ==================== P0-2 回归测试 ====================

    @staticmethod
    def test_on_kb_deleted_discards_pending_with_callback():
        """P0-2: on_kb_deleted 丢弃 pending 时调 on_discard_fn，每个文档事件均被回调。

        契约：on_kb_deleted 必须对 pending 中每个文档事件调 on_discard_fn，
        由调用方推进 offset + 投递 no-op 成功回调，避免 pending 消息 offset 卡死。
        """
        submitted = []
        discarded = []
        lock = threading.Lock()

        def submit_fn(document_id, msg):
            with lock:
                submitted.append(document_id)

        def on_discard_fn(document_id, msg):
            with lock:
                discarded.append((document_id, msg))

        gate = KBGate(submit_fn, on_discard_fn=on_discard_fn)
        gate.mark_initializing(TestKBGate._KB_NAME_KEY)
        # 2 条文档事件入 pending（state=initializing）
        gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, "msg1", submit_fn)
        gate.acquire(TestKBGate._KB_NAME_KEY, "d2", "msg2", submit_fn)
        assert not submitted  # 仍在 pending，未提交

        gate.on_kb_deleted(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "deleting"
        # pending 被丢弃，on_discard_fn 对每个文档事件调用
        assert len(discarded) == 2, f"预期 2 条 discard 回调，实际: {discarded}"
        discarded_ids = sorted(d[0] for d in discarded)
        assert discarded_ids == [TestKBGate._DOC_ID_KEY, "d2"]
        # submit_fn 未被调用（pending 未 flush，被丢弃）
        assert not submitted, f"pending 应被丢弃而非 flush，实际 submit_fn 被调: {submitted}"

    @staticmethod
    def test_on_kb_deleted_no_discard_fn():
        """on_discard_fn 为 None 时仅丢弃 pending，不回调（兼容无回调场景，不抛异常）。"""
        submitted = []

        def submit_fn(document_id, msg):
            submitted.append(document_id)

        gate = KBGate(submit_fn, on_discard_fn=None)
        gate.mark_initializing(TestKBGate._KB_NAME_KEY)
        gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, "msg1", submit_fn)
        gate.acquire(TestKBGate._KB_NAME_KEY, "d2", "msg2", submit_fn)

        # on_discard_fn=None，不抛异常
        gate.on_kb_deleted(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "deleting"
        assert not submitted  # pending 被丢弃，未 flush

    # ==================== P2 on_kb_recreated 删除回归测试 ====================

    @staticmethod
    def test_on_kb_recreated_not_exists():
        """P2: on_kb_recreated 已删除（死代码复现 P0-2），调用应抛 AttributeError。"""
        gate = KBGate(submit_fn=lambda d, m: None)
        with pytest.raises(AttributeError):
            gate.on_kb_recreated("k1")

    # ==================== N1 KB_DELETED 超时 reset 测试 ====================

    @staticmethod
    def test_reset_clears_kb_state():
        """N1: reset 清除 kb_state + pending，下次该 KB 事件默认 ready。

        契约：reset 后 get_state 返回 ready（未登记），pending 队列也被清空。
        供 ProcessingEngine 在 KB_DELETED 超时分支调用，避免该 KB 永久拒绝新事件。
        """
        submitted = []

        def submit_fn(document_id, msg):
            submitted.append(document_id)

        gate = KBGate(submit_fn)
        gate.mark_initializing(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "initializing"
        # 入 pending 队列
        gate.acquire(TestKBGate._KB_NAME_KEY, TestKBGate._DOC_ID_KEY, "msg1", submit_fn)
        assert not submitted

        # reset：清除状态 + pending
        gate.reset(TestKBGate._KB_NAME_KEY)
        assert gate.get_state(TestKBGate._KB_NAME_KEY) == "ready"  # 未登记 → 默认 ready
        # reset 后该 KB 文档事件能正常 acquire（不再被 deleting 拒绝）
        ok = gate.acquire(TestKBGate._KB_NAME_KEY, "d2", "msg2", submit_fn)
        assert ok is True
        assert submitted == ["d2"]  # 直接提交，未入 pending

    @staticmethod
    def test_reset_nonexistent_kb_no_op():
        """N1: reset 不存在的 kbId 不抛异常（no-op）。"""
        gate = KBGate(submit_fn=lambda d, m: None)
        gate.reset("nonexistent")  # 不抛异常
        assert gate.get_state("nonexistent") == "ready"
