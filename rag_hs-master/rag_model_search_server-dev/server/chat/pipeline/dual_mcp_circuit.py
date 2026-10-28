"""dual-mcp 熔断器：连续失败 N 次后打开熔断，cooldown 期内直接跳过检索。

复用 mempalace_circuit 的模式（连续失败计数 + cooldown），避免每请求等满 15s
超时（dual_mcp /query timeout=15s 是三路检索最长，故障时拖垮 TTFT 最严重）。
已知与 mempalace_circuit 共有的局限：无 half-open 状态、用连续失败计数而非
滑动窗口失败率——作为单独的稳定性优化项后续统一处理，此处先保持两路一致。
"""
import threading
import time

from configs import DUAL_MCP_COOLDOWN, DUAL_MCP_ENABLED, DUAL_MCP_FAIL_THRESHOLD, logger

# dual-mcp 熔断器状态：与 mempalace_circuit 同构
_dual_mcp_circuit = {
    "fail_streak": 0,   # 连续失败计数
    "open_until": 0.0,  # 熔断打开的截止时间戳（time.time() 之前保持打开）
}
# 状态锁：fail_streak 的 += 是非原子读-改-写，asyncio.to_thread / 其他线程
# 调用时需保护。与 mempalace_circuit 一致用 threading.Lock 保证线程安全。
_circuit_lock = threading.Lock()


def _dual_mcp_available() -> bool:
    """熔断器闭合且配置启用时才允许检索。"""
    if not DUAL_MCP_ENABLED:
        return False
    with _circuit_lock:
        return time.time() >= _dual_mcp_circuit["open_until"]


def _dual_mcp_record_failure():
    """记录一次失败，达到阈值则打开熔断。"""
    with _circuit_lock:
        _dual_mcp_circuit["fail_streak"] += 1
        _streak = _dual_mcp_circuit["fail_streak"]
        if _streak >= DUAL_MCP_FAIL_THRESHOLD:
            _dual_mcp_circuit["open_until"] = time.time() + DUAL_MCP_COOLDOWN
    # 日志在锁外打印，避免日志 I/O 阻塞其他协程获取锁
    if _streak >= DUAL_MCP_FAIL_THRESHOLD:
        logger.warning(f"[dual-mcp] 熔断打开，连续失败 {_streak} 次，"
                       f"{DUAL_MCP_COOLDOWN:.0f}s 内跳过检索")


def _dual_mcp_record_success():
    """记录一次成功，清零连续失败计数。"""
    with _circuit_lock:
        if _dual_mcp_circuit["fail_streak"] > 0:
            _dual_mcp_circuit["fail_streak"] = 0
