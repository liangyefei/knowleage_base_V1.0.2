"""mempalace 熔断器：连续失败 N 次后打开熔断，cooldown 期内直接跳过检索。

避免每请求等满超时（生产环境 mempalace 不可用时曾导致 30s/请求的 TTFT 灾难）。
"""
import threading
import time

from configs import MEMPALACE_COOLDOWN, MEMPALACE_ENABLED, MEMPALACE_FAIL_THRESHOLD, logger

# mempalace 熔断器状态：连续失败 N 次后打开熔断，cooldown 期内直接跳过检索
_mempalace_circuit = {
    "fail_streak": 0,   # 连续失败计数
    "open_until": 0.0,  # 熔断打开的截止时间戳（time.time() 之前保持打开）
}
# 状态锁：fail_streak 的 += 是非原子读-改-写，并发请求下会丢失计数；
# open_until 的写入也需保护。asyncio 协程在单线程内运行，但这些函数
# 可能被 asyncio.to_thread 或其他线程调用，用 threading.Lock 保证线程安全。
_circuit_lock = threading.Lock()


def _mempalace_available() -> bool:
    """熔断器闭合且配置启用时才允许检索。"""
    if not MEMPALACE_ENABLED:
        return False
    with _circuit_lock:
        return time.time() >= _mempalace_circuit["open_until"]


def _mempalace_record_failure():
    """记录一次失败，达到阈值则打开熔断。"""
    with _circuit_lock:
        _mempalace_circuit["fail_streak"] += 1
        _streak = _mempalace_circuit["fail_streak"]
        if _streak >= MEMPALACE_FAIL_THRESHOLD:
            _mempalace_circuit["open_until"] = time.time() + MEMPALACE_COOLDOWN
    # 日志在锁外打印，避免日志 I/O 阻塞其他协程获取锁
    if _streak >= MEMPALACE_FAIL_THRESHOLD:
        logger.warning(f"[mempalace] 熔断打开，连续失败 {_streak} 次，"
                       f"{MEMPALACE_COOLDOWN:.0f}s 内跳过检索")


def _mempalace_record_success():
    """记录一次成功，清零连续失败计数。"""
    with _circuit_lock:
        if _mempalace_circuit["fail_streak"] > 0:
            _mempalace_circuit["fail_streak"] = 0
