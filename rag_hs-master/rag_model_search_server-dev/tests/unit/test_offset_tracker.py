#!/usr/bin/env python3
"""
OffsetTracker 单元测试：按分区跟踪已完成 offset，乱序完成填补空洞后推进。

覆盖 spec.md 场景：
- 乱序完成：12 先完成、10 最后完成时，仅在 10 完成时一次性推进到 13
- 连续完成：10→11→12 顺序完成时逐条推进
- 已提交过的 offset（重投递完成）忽略
- get_commit_offsets 返回所有分区当前 base
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest
from kafka.structs import OffsetAndMetadata, TopicPartition

from server.comm.offset_tracker import OffsetTracker


class TestOffsetTracker:
    @staticmethod
    def test_out_of_order_complete_advances_on_gap_fill():
        """乱序完成：12 先、11 后、10 最后，仅在 10 完成时一次性推进到 13。"""
        ot = OffsetTracker()
        tp = TopicPartition("kb-document-processing", 0)
        ot.on_dispatch(tp, 10)
        ot.on_dispatch(tp, 11)
        ot.on_dispatch(tp, 12)

        assert ot.on_complete(tp, 12) is None  # 乱序入 done
        assert ot.on_complete(tp, 11) is None  # 乱序入 done
        advance = ot.on_complete(tp, 10)
        assert advance is not None
        tp_key, new_base = advance
        assert tp_key == tp
        assert new_base == 13  # 10+11+12 全完成，base 推进到 13

    @staticmethod
    def test_sequential_complete_advances_one_by_one():
        """连续完成：10→11→12 顺序完成时逐条推进。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 1)
        ot.on_dispatch(tp, 10)
        ot.on_dispatch(tp, 11)
        ot.on_dispatch(tp, 12)

        advance = ot.on_complete(tp, 10)
        assert advance == (tp, 11)

        advance = ot.on_complete(tp, 11)
        assert advance == (tp, 12)

        advance = ot.on_complete(tp, 12)
        assert advance == (tp, 13)

    @staticmethod
    def test_already_committed_offset_ignored():
        """已提交过的 offset（< base）完成时忽略，返回 None。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 2)
        ot.on_dispatch(tp, 10)
        ot.on_dispatch(tp, 11)

        assert ot.on_complete(tp, 10) == (tp, 11)  # base 推进到 11
        # 重投递的 offset=10 再次完成
        assert ot.on_complete(tp, 10) is None

    @staticmethod
    def test_get_commit_offsets_returns_all_bases():
        """get_commit_offsets 返回所有分区当前 base 的 OffsetAndMetadata 映射。"""
        ot = OffsetTracker()
        tp0 = TopicPartition("t", 0)
        tp1 = TopicPartition("t", 1)
        ot.on_dispatch(tp0, 100)
        ot.on_dispatch(tp1, 200)
        ot.on_complete(tp0, 100)  # base 100 -> 101

        offsets = ot.get_commit_offsets()
        assert tp0 in offsets
        assert tp1 in offsets
        assert offsets[tp0] == OffsetAndMetadata(101, b"")
        assert offsets[tp1] == OffsetAndMetadata(200, b"")  # 未完成，base 仍是起点

    @staticmethod
    def test_dispatch_then_complete_single():
        """单条派发完成：base 推进到 offset+1。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 3)
        ot.on_dispatch(tp, 50)
        advance = ot.on_complete(tp, 50)
        assert advance == (tp, 51)

    @staticmethod
    def test_partial_gap_not_advances():
        """有空洞时只推进到空洞前。10、12 完成（11 未完成）→ 只推进到 10→11？不，
        10 完成推进到 11，12 入 done，11 未完成，base 停在 11。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 4)
        ot.on_dispatch(tp, 10)
        ot.on_dispatch(tp, 11)
        ot.on_dispatch(tp, 12)

        assert ot.on_complete(tp, 12) is None  # 入 done
        advance = ot.on_complete(tp, 10)
        assert advance == (tp, 11)  # base 推进到 11（11 未完成，停在 11）
        # 11 完成：11==base，推进到 12，检查 12 in done（是），推进到 13
        advance = ot.on_complete(tp, 11)
        assert advance == (tp, 13)

    # ==================== is_redelivery / get_base（重投递前置检测）====================

    @staticmethod
    def test_is_redelivery_true_when_offset_below_base():
        """offset < base（已处理过的老消息）返回 True：重投递。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        ot.on_dispatch(tp, 10)
        ot.on_complete(tp, 10)  # base 10→11
        # offset=10 < base=11 → 重投递
        assert ot.is_redelivery(tp, 10) is True

    @staticmethod
    def test_is_redelivery_false_when_offset_equals_base():
        """offset == base（当前待处理 offset）返回 False：非重投递。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        ot.on_dispatch(tp, 10)  # base=10
        assert ot.is_redelivery(tp, 10) is False

    @staticmethod
    def test_is_redelivery_false_when_offset_above_base():
        """offset > base（乱序的未来 offset）返回 False：非重投递。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        ot.on_dispatch(tp, 10)  # base=10
        assert ot.is_redelivery(tp, 15) is False

    @staticmethod
    def test_is_redelivery_false_for_undispatched_tp():
        """未派发过的 tp（base=None）返回 False：非重投递（正常首派）。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        assert ot.is_redelivery(tp, 0) is False

    @staticmethod
    def test_is_redelivery_true_after_gap_fill_advances_base():
        """乱序完成后 base 推进，旧 offset 变为重投递。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        ot.on_dispatch(tp, 10)
        ot.on_dispatch(tp, 11)
        ot.on_dispatch(tp, 12)
        # 12 先完成（入 done），10 最后完成触发空洞填补，base 一次推进到 13
        ot.on_complete(tp, 12)
        ot.on_complete(tp, 11)
        ot.on_complete(tp, 10)
        # 10/11/12 都 < base=13，都是重投递
        assert ot.is_redelivery(tp, 10) is True
        assert ot.is_redelivery(tp, 11) is True
        assert ot.is_redelivery(tp, 12) is True
        # 13 == base，不是重投递
        assert ot.is_redelivery(tp, 13) is False

    @staticmethod
    def test_get_base_returns_current_base():
        """get_base 返回分区当前 base offset。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        ot.on_dispatch(tp, 100)
        ot.on_complete(tp, 100)  # base 100→101
        assert ot.get_base(tp) == 101

    @staticmethod
    def test_get_base_none_for_undispatched():
        """get_base 对未派发过的分区返回 None。"""
        ot = OffsetTracker()
        tp = TopicPartition("t", 0)
        assert ot.get_base(tp) is None
