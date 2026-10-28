"""dual_mcp_circuit 单元测试：验证熔断器闭合/打开/恢复逻辑。

复用 mempalace_circuit 同构模式：连续失败 N 次打开熔断，cooldown 期内跳过，
成功清零 fail_streak。覆盖启用/禁用/阈值/恢复/连续计数等边界。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
import time
from pathlib import Path
from unittest.mock import patch

import pytest

_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_SERVER_SRC = _PROJECT_ROOT / "server"
if str(_SERVER_SRC) not in sys.path:
    sys.path.insert(0, str(_SERVER_SRC))

from chat.pipeline import dual_mcp_circuit  # noqa: E402


@pytest.fixture
def reset_circuit():
    """每个测试前后重置熔断器状态（fail_streak=0, open_until=0）。"""
    def _reset():
        with dual_mcp_circuit._circuit_lock:
            dual_mcp_circuit._dual_mcp_circuit["fail_streak"] = 0
            dual_mcp_circuit._dual_mcp_circuit["open_until"] = 0.0
    _reset()
    yield
    _reset()


@pytest.fixture
def enabled_config():
    """patch 配置：启用 + threshold=3 + cooldown=60。"""
    with patch.object(dual_mcp_circuit, "DUAL_MCP_ENABLED", True), \
         patch.object(dual_mcp_circuit, "DUAL_MCP_FAIL_THRESHOLD", 3), \
         patch.object(dual_mcp_circuit, "DUAL_MCP_COOLDOWN", 60.0):
        yield


class TestDualMcpCircuitBasic:
    """基础：启用/禁用/闭合/打开。"""

    @staticmethod
    def test_disabled_when_not_enabled(reset_circuit):
        """DUAL_MCP_ENABLED=False → 一律不可用。"""
        with patch.object(dual_mcp_circuit, "DUAL_MCP_ENABLED", False):
            assert dual_mcp_circuit._dual_mcp_available() is False

    @staticmethod
    def test_available_when_enabled_no_failures(reset_circuit, enabled_config):
        """启用 + 无失败 → 可用。"""
        assert dual_mcp_circuit._dual_mcp_available() is True

    @staticmethod
    def test_failure_below_threshold_stays_available(reset_circuit, enabled_config):
        """失败次数 < threshold → 仍可用。"""
        for _ in range(2):  # threshold=3，失败 2 次
            dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_available() is True

    @staticmethod
    def test_failure_reach_threshold_opens_circuit(reset_circuit, enabled_config):
        """连续失败达 threshold → 熔断打开，不可用。"""
        for _ in range(3):  # 失败 3 次 = threshold
            dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_available() is False
        assert dual_mcp_circuit._dual_mcp_circuit["fail_streak"] == 3


class TestDualMcpCircuitRecovery:
    """恢复：cooldown 到期 / 成功清零。"""

    @staticmethod
    def test_cooldown_expiry_recovers(reset_circuit, enabled_config):
        """熔断打开后 cooldown 到期 → 自动恢复可用（无 half-open，直接全量恢复）。"""
        for _ in range(3):
            dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_available() is False
        # 手动把 open_until 设为过去，模拟 cooldown 到期
        with dual_mcp_circuit._circuit_lock:
            dual_mcp_circuit._dual_mcp_circuit["open_until"] = time.time() - 1
        assert dual_mcp_circuit._dual_mcp_available() is True

    @staticmethod
    def test_success_resets_fail_streak(reset_circuit, enabled_config):
        """成功 → fail_streak 清零，仍可用。"""
        for _ in range(2):  # 失败 2 次（< threshold）
            dual_mcp_circuit._dual_mcp_record_failure()
        dual_mcp_circuit._dual_mcp_record_success()
        assert dual_mcp_circuit._dual_mcp_circuit["fail_streak"] == 0
        assert dual_mcp_circuit._dual_mcp_available() is True

    @staticmethod
    def test_success_does_not_close_open_circuit(reset_circuit, enabled_config):
        """熔断打开后 record_success 清零 fail_streak，但 open_until 仍未来 → 仍不可用。

        cooldown 由时间控制，不因单次成功提前闭合（避免服务不稳定时反复抖动）。
        """
        for _ in range(3):
            dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_available() is False
        dual_mcp_circuit._dual_mcp_record_success()
        assert dual_mcp_circuit._dual_mcp_circuit["fail_streak"] == 0
        # open_until 仍是未来 → cooldown 未到期 → 仍不可用
        assert dual_mcp_circuit._dual_mcp_available() is False


class TestDualMcpCircuitStreak:
    """连续计数：成功后重新开始计数。"""

    @staticmethod
    def test_new_streak_after_success(reset_circuit, enabled_config):
        """失败 2 次 → 成功清零 → 再失败 1 次 → fail_streak=1（< threshold）。"""
        for _ in range(2):
            dual_mcp_circuit._dual_mcp_record_failure()
        dual_mcp_circuit._dual_mcp_record_success()
        dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_circuit["fail_streak"] == 1
        assert dual_mcp_circuit._dual_mcp_available() is True

    @staticmethod
    def test_failure_beyond_threshold_keeps_circuit_open(reset_circuit, enabled_config):
        """失败超过 threshold（4 次）→ 熔断仍打开（不重复触发 open_until 刷新）。"""
        for _ in range(4):  # 超过 threshold=3
            dual_mcp_circuit._dual_mcp_record_failure()
        assert dual_mcp_circuit._dual_mcp_circuit["fail_streak"] == 4
        assert dual_mcp_circuit._dual_mcp_available() is False
