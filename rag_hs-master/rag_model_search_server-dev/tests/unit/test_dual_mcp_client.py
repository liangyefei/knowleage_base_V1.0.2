#!/usr/bin/env python3
"""
server.comm.dual_mcp_client 单元测试。

覆盖:
- normalize: 纯函数,小写 + 去非字母数字,含 None 安全
- is_code_file: 纯函数,按扩展名判断代码文件
- get_base_url: 三级 fallback(kb_loader.get_config → kbs_config.dual_mcp.base_url → 默认值)
- resolve_project: 精确命中 / normalize 唯一模糊命中 / 多命中 / 零命中 / 异常 / 非200
- _poll_job: 立即 done / 立即 failed / 非200 / 循环超时

所有用例均使用 mock,不连真实 dual-mcp。_http 在 server.comm.dual_mcp_client 命名空间 patch。
模块级状态 _BASE_URL 每例前 reset。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

# 将项目根目录加入 sys.path,保证可直连 import server / configs
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from server.comm import dual_mcp_client
from server.comm.dual_mcp_client import (
    _CODE_EXTS,
    _poll_job,
    get_base_url,
    is_code_file,
    normalize,
    resolve_project,
)

# ==================== normalize 测试 ====================

class TestNormalize:
    @staticmethod
    def test_already_normalized():
        assert normalize("abc123") == "abc123"

    @staticmethod
    def test_empty_string():
        assert normalize("") == ""

    @staticmethod
    def test_mixed_case_and_special():
        assert normalize("Projects_HI3516-CV610") == "projectshi3516cv610"

    @staticmethod
    def test_none_safe():
        # None → (None or "").lower() → "" → re.sub → ""
        assert normalize(None) == ""

    @staticmethod
    def test_special_chars_removed():
        # 破折号、下划线、点、空格 全部去掉
        assert normalize("projects-hi3516cv610") == "projectshi3516cv610"
        assert normalize("a.b-c_d") == "abcd"
        assert normalize("hello world") == "helloworld"
        assert normalize("a@b#c$") == "abc"

    @staticmethod
    def test_unicode_passthrough_lowered():
        # 非字母数字(含中文)被去掉
        assert normalize("项目abc") == "abc"

    @staticmethod
    def test_uppercase_to_lowercase():
        assert normalize("ABC") == "abc"
        assert normalize("Hello") == "hello"


# ==================== is_code_file 测试 ====================

class TestIsCodeFile:
    @pytest.mark.parametrize("ext", [".py", ".ts", ".go", ".js", ".java", ".rs"])
    @staticmethod
    def test_code_exts_hit(ext):
        assert is_code_file(f"foo{ext}") is True

    @staticmethod
    def test_code_exts_set_contains_common():
        # 防止 _CODE_EXTS 集合被改动导致测试失真
        assert ".py" in _CODE_EXTS
        assert ".ts" in _CODE_EXTS
        assert ".go" in _CODE_EXTS
        assert ".txt" not in _CODE_EXTS

    @staticmethod
    def test_empty_string():
        assert is_code_file("") is False

    @staticmethod
    def test_no_extension():
        # 无后缀(文件名不含 .)
        assert is_code_file("README") is False

    @pytest.mark.parametrize("ext", [".txt", ".md", ".png", ".pdf", ".jpg", ".docx"])
    @staticmethod
    def test_non_code_exts_miss(ext):
        assert is_code_file(f"foo{ext}") is False

    @staticmethod
    def test_none_safe():
        # None → (None or "") → splitext → ("","") → "" not in _CODE_EXTS → False
        assert is_code_file(None) is False

    @staticmethod
    def test_path_with_dirs():
        assert is_code_file("/path/to/file.py") is True
        assert is_code_file("/path/to/file.txt") is False

    @staticmethod
    def test_uppercase_ext_normalized():
        # 后缀大写 → lower → 命中
        assert is_code_file("foo.PY") is True
        assert is_code_file("bar.GO") is True


# ==================== get_base_url 测试 ====================

class TestGetBaseUrl:
    @pytest.fixture(autouse=True)
    @staticmethod
    def reset_base_url(monkeypatch):
        # 每例前重置模块级缓存
        monkeypatch.setattr("server.comm.dual_mcp_client._BASE_URL", None)

    @staticmethod
    def test_both_fail_uses_default(monkeypatch):
        # get_config 返回 None AND kbs_config 无 dual_mcp.base_url → 默认值
        monkeypatch.setattr("configs.kb_loader.get_config", lambda *a, **kw: None)
        monkeypatch.setattr("server.comm.dual_mcp_client.kbs_config", {})
        assert get_base_url() == "http://localhost:9789"
        assert dual_mcp_client._BASE_URL == "http://localhost:9789"

    @staticmethod
    def test_cached_value_short_circuits(monkeypatch):
        # 已缓存 → 直接返回,不调用 get_config
        monkeypatch.setattr("server.comm.dual_mcp_client._BASE_URL", "http://cached:9789")
        # 若 get_config 被调用,会抛异常使测试失败
        def _should_not_call(*a, **kw):
            raise AssertionError("get_config should not be called when cached")

        monkeypatch.setattr("configs.kb_loader.get_config", _should_not_call)
        assert get_base_url() == "http://cached:9789"

    @staticmethod
    def test_get_config_raises_and_kbs_empty_uses_default(monkeypatch):
        # get_config 抛异常 AND kbs_config 无 base_url → 默认值
        def _raise(*a, **kw):
            raise RuntimeError("kb_loader unavailable")

        monkeypatch.setattr("configs.kb_loader.get_config", _raise)
        monkeypatch.setattr("server.comm.dual_mcp_client.kbs_config", {})
        assert get_base_url() == "http://localhost:9789"

    @staticmethod
    def test_get_config_raises_falls_to_kbs_config(monkeypatch):
        _UPSTREAM_URL_KEY = 'http://from-kbs:9789'
        # get_config 抛异常 → 走 kbs_config.dual_mcp.base_url
        def _raise(*a, **kw):
            raise RuntimeError("kb_loader unavailable")

        monkeypatch.setattr("configs.kb_loader.get_config", _raise)
        monkeypatch.setattr(
            "server.comm.dual_mcp_client.kbs_config",
            {"dual_mcp": {"base_url": _UPSTREAM_URL_KEY}},
        )
        assert get_base_url() == _UPSTREAM_URL_KEY
        assert dual_mcp_client._BASE_URL == _UPSTREAM_URL_KEY

    @staticmethod
    def test_get_config_returns_none_falls_to_kbs_config(monkeypatch):
        # get_config 返回 None → 走 kbs_config.dual_mcp.base_url
        monkeypatch.setattr("configs.kb_loader.get_config", lambda *a, **kw: None)
        monkeypatch.setattr(
            "server.comm.dual_mcp_client.kbs_config",
            {"dual_mcp": {"base_url": "http://from-kbs2:9789"}},
        )
        assert get_base_url() == "http://from-kbs2:9789"

    @staticmethod
    def test_get_config_returns_value_cached(monkeypatch):
        # kb_loader.get_config 返回有值 → 缓存 + 返回
        _HTTPFROMLOADER9789_KEY = 'http://from-loader:9789'
        monkeypatch.setattr(
            "configs.kb_loader.get_config",
            lambda *a, **kw: _HTTPFROMLOADER9789_KEY,
        )
        assert get_base_url() == _HTTPFROMLOADER9789_KEY
        # 缓存到模块级 _BASE_URL
        assert dual_mcp_client._BASE_URL == _HTTPFROMLOADER9789_KEY


# ==================== resolve_project 测试 ====================

class TestResolveProject:
    @staticmethod
    def test_http_raises_returns_none(monkeypatch):
        def _raise(*a, **kw):
            raise RuntimeError("network down")

        monkeypatch.setattr("server.comm.dual_mcp_client._http", _raise)
        assert resolve_project("kb-1") is None

    @staticmethod
    def _make_http(status_code, projects):
        """构造 mock _http,返回 status_code + projects 列表。"""
        mock_resp = MagicMock()
        mock_resp.status_code = status_code
        mock_resp.json.return_value = {"projects": projects}
        return MagicMock(return_value=mock_resp)

    def test_empty_kb_id_no_fuzzy_match(self, monkeypatch):
        # kb_id="" → normalize="" → nkb="" → `nkb and ...` 为 False → 无模糊命中
        # 精确也不命中(projects 中无 name=="") → None
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(200, [{"name": "kb-1"}]),
        )
        assert resolve_project("") is None

    def test_exact_match(self, monkeypatch):
        _KB1_KEY = 'kb-1'
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(200, [{"name": _KB1_KEY}, {"name": "kb-2"}]),
        )
        assert resolve_project(_KB1_KEY) == _KB1_KEY

    def test_multiple_matches_returns_best_by_nodes(self, monkeypatch):
        # 多命中时按图谱节点数择优：kb-1-alpha 空壳(nodes=0)让位给 kb-1-beta(有数据)
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(200, [{"name": "kb-1-alpha", "nodes": 0},
                                  {"name": "kb-1-beta", "nodes": 5}]),
        )
        assert resolve_project("kb-1") == "kb-1-beta"

    def test_normalized_unique_match(self, monkeypatch):
        # 精确不命中,normalize 后唯一命中
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(200, [{"name": "projects-hi3516cv610"}]),
        )
        # kbId normalize 后 = "projectshi3516cv610",包含于 normalize("projects-hi3516cv610")
        assert resolve_project("projectshi3516cv610") == "projects-hi3516cv610"

    def test_status_404_returns_none(self, monkeypatch):
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(404, []),
        )
        assert resolve_project("kb-1") is None

    def test_status_non_200_returns_none(self, monkeypatch):
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(500, []),
        )
        assert resolve_project("kb-1") is None

    def test_zero_matches_returns_none(self, monkeypatch):
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            self._make_http(200, [{"name": "kb-2"}, {"name": "kb-3"}]),
        )
        assert resolve_project("kb-1") is None


# ==================== _poll_job 测试 ====================

class TestPollJob:
    @pytest.fixture(autouse=True)
    @staticmethod
    def patch_time_sleep(monkeypatch):
        # 所有 _poll_job 测试均 patch time.sleep 为 no-op,避免阻塞
        mock_time = MagicMock()
        monkeypatch.setattr("server.comm.dual_mcp_client.time", mock_time)
        return mock_time

    @staticmethod
    def test_http_raises_returns_false_after_loop(monkeypatch):
        # _http 抛异常 → 被 except 捕获 → 循环 360 次后超时返 False
        # 为避免 360 次循环开销,用 side_effect 一直抛,并依赖 time.sleep no-op
        def _raise(*a, **kw):
            raise RuntimeError("network down")

        monkeypatch.setattr("server.comm.dual_mcp_client._http", _raise)
        assert _poll_job("job-1", "label") is False

    @staticmethod
    def test_immediate_done_returns_true(monkeypatch):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "done"}
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[mock_resp]),
        )
        assert _poll_job("job-1", "label") is True

    @staticmethod
    def test_immediate_failed_returns_false(monkeypatch):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "failed", "error": "boom"}
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[mock_resp]),
        )
        assert _poll_job("job-1", "label") is False

    @staticmethod
    def test_loop_timeout_returns_false(monkeypatch):
        # side_effect 一直返 running,循环 360 次后超时返 False
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "running"}
        # 用函数 side_effect 让每次调用都返回 running 响应(避免 StopIteration)
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=lambda *a, **kw: mock_resp),
        )
        assert _poll_job("job-1", "label") is False

    @staticmethod
    def test_non_200_returns_false(monkeypatch):
        mock_resp = MagicMock()
        mock_resp.status_code = 404
        mock_resp.json.return_value = {}
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[mock_resp]),
        )
        assert _poll_job("job-1", "label") is False

    @staticmethod
    def test_running_then_done_returns_true(monkeypatch):
        # 第一次 running,第二次 done → True
        running_resp = MagicMock()
        running_resp.status_code = 200
        running_resp.json.return_value = {"status": "running"}
        done_resp = MagicMock()
        done_resp.status_code = 200
        done_resp.json.return_value = {"status": "done"}
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[running_resp, done_resp]),
        )
        assert _poll_job("job-1", "label") is True

    @staticmethod
    def test_running_then_failed_returns_false(monkeypatch):
        # 第一次 running,第二次 failed → False
        running_resp = MagicMock()
        running_resp.status_code = 200
        running_resp.json.return_value = {"status": "running"}
        failed_resp = MagicMock()
        failed_resp.status_code = 200
        failed_resp.json.return_value = {"status": "failed", "error": "boom"}
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[running_resp, failed_resp]),
        )
        assert _poll_job("job-1", "label") is False

    @staticmethod
    def test_stages_logged_on_status_change(monkeypatch, patch_time_sleep):
        _DONE_KEY = 'done'
        # 验证 status 变化时记录 stages(仅做行为覆盖,不抛异常即可)
        running_resp = MagicMock()
        running_resp.status_code = 200
        running_resp.json.return_value = {
            "status": "running",
            "stages": {"scan": "in_progress"},
        }
        done_resp = MagicMock()
        done_resp.status_code = 200
        done_resp.json.return_value = {
            "status": _DONE_KEY,
            "stages": {"scan": _DONE_KEY, "index": _DONE_KEY},
        }
        monkeypatch.setattr(
            "server.comm.dual_mcp_client._http",
            MagicMock(side_effect=[running_resp, done_resp]),
        )
        with patch("server.comm.dual_mcp_client.logger"):
            assert _poll_job("job-1", "label") is True