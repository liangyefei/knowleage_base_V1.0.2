#!/usr/bin/env python3
# @Time : 2026/8/20
# @Author : Hetao
# @File : [Code] --> offset_tracker.py
# @Software : PyCharm
# @Function: 按分区跟踪已完成 offset，推进连续已完成的前缀，支持乱序完成与优雅停止批量提交。
import threading

from kafka.structs import OffsetAndMetadata, TopicPartition

from configs import logger


class OffsetTracker:
    """按分区跟踪已完成 offset，推进连续已完成的前缀。

    语义：
    - base[tp] = 最小未完成 offset（所有 < base 的 offset 已确认完成并已提交）
    - done[tp] = 已完成但 >= base 的 offset 集合（乱序完成填补空洞用）
    - commit target = base[tp]（kafka 语义：committed offset = next-to-consume = base）

    线程安全：所有状态读写加锁。
    """

    def __init__(self):
        self._base: dict = {}  # TopicPartition -> int
        self._done: dict = {}  # TopicPartition -> set of int
        self._lock = threading.Lock()

    @staticmethod
    def tp_key(topic, partition) -> TopicPartition:
        """构造 TopicPartition 键（供外部调用方使用）。"""
        return TopicPartition(topic, partition)

    def get_base(self, tp):
        """获取分区当前 base offset（日志/调试用）。

        未派发过的分区返回 None（与 _base 字典语义对齐）。
        """
        with self._lock:
            return self._base.get(tp)

    def get_commit_offsets(self):
        """获取所有分区当前可提交的 {tp: OffsetAndMetadata(base)} 映射。

        用于优雅停止时批量提交所有已推进的 base。
        """
        with self._lock:
            return {
                tp: OffsetAndMetadata(base, b"")
                for tp, base in self._base.items()
            }

    def is_redelivery(self, tp, offset) -> bool:
        """判断是否为重投递（offset < base，已处理过的老消息）。

        用于 _execute_transaction 前置检测：rebalance / at-least-once 重投递会
        把已处理过的消息再次送入消费循环，offset < base 说明该消息的事务已提交、
        base 已推进。再次执行事务会提交回退的 offset（offset+1 <= base），
        导致 broker committed offset 回退、consumer 一直 poll 老消息、
        向量化无限重跑的死循环。

        Returns:
            True: offset < base，是重投递，调用方应跳过事务（offset 已处理过）
            False: offset >= base（或该 tp 未派发过），非重投递，正常执行事务
        """
        with self._lock:
            base = self._base.get(tp)
            return base is not None and offset < base

    def on_complete(self, tp, offset):
        """记录完成。

        Returns:
            (tp, new_base) 元组：表示需要提交 new_base（= next-to-consume offset）；
            None：无可推进（offset 落在 base 之前的已提交区，或 offset > base 入 done 等空洞填补）。
        """
        with self._lock:
            base = self._base.get(tp)
            if base is None:
                # 未派发过就完成（不应发生），容错忽略
                logger.warning(
                    f"OffsetTracker: 未派发就完成（不应发生），容错忽略: "
                    f"tp={tp}, offset={offset}"
                )
                return None
            if offset < base:
                # P1-2 可观测性：已提交区间的重投递完成（offset < base）。
                # 常见触发：rebalance 后 Kafka 从旧 committed offset 重投递，
                # task.run 重新执行（向量化重跑，at-least-once 正常），但
                # on_complete 返回 None → 调用方 _advance_offset 不推进内存 base
                # → 非事务降级路径 commit_offsets 不会包含该 tp 的新状态，
                # 回调可能静默不发（后端状态停留"处理中"）。
                # 此处仅记 warning 不改行为（完整修复需 in-flight 集合 + 重投递回调重发，
                # 属设计重构，单独跟踪），用于线上定位"后端一直处理中"的根因。
                logger.warning(
                    f"OffsetTracker: 重投递完成 offset < base（回调可能不发，需人工确认）: "
                    f"tp={tp}, offset={offset}, base={base}"
                )
                return None
            if offset == base:
                # 推进 base，连续填补空洞
                new_base = base + 1
                done = self._done.get(tp, set())
                while new_base in done:
                    done.discard(new_base)
                    new_base += 1
                self._base[tp] = new_base
                logger.debug(
                    f"OffsetTracker: 推进 tp={tp}, base {base}->{new_base}"
                )
                return (tp, new_base)
            # offset > base：乱序完成，入 done 等空洞填满
            self._done.setdefault(tp, set()).add(offset)
            logger.debug(
                f"OffsetTracker: 乱序完成入 done tp={tp}, offset={offset}, base={base}"
            )
            return None

    def on_dispatch(self, tp, offset) -> None:
        """记录派发：offset 进入 in-flight。首个派发 offset 作为 base 起点。"""
        with self._lock:
            if tp not in self._base:
                self._base[tp] = offset
            self._done.setdefault(tp, set())
