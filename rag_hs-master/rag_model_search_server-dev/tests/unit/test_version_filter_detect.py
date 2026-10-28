"""单版本检索意图识别：_detect_version_filter 单元测试。

纯函数测试，只 import version_diff（仅依赖 configs），不需要 langchain shim。
"""
from server.chat.pipeline.version_diff import _detect_version_filter


def test_detect_version_filter_single_three_parts():
    """三点版本串（v 前缀）→ 返回该串。"""
    assert _detect_version_filter("v1.2.0 的 HDMI 配置") == "v1.2.0"


def test_detect_version_filter_single_three_parts_no_v():
    """三点版本串（无 v 前缀）→ 返回该串。"""
    assert _detect_version_filter("1.2.0 版本的 HDMI 配置") == "1.2.0"


def test_detect_version_filter_single_two_parts_with_v():
    """两点版本串 + v 前缀 → 返回该串。"""
    assert _detect_version_filter("v1.2 的 HDMI 配置") == "v1.2"


def test_detect_version_filter_skipped_when_diff_signal():
    """有对比信号词 → 让 verdiff 接管，返回 None。"""
    assert _detect_version_filter("v1.0.0 和 v2.0.0 有什么差异") is None
    assert _detect_version_filter("对比 v1.0 和 v2.0 的配置") is None


def test_detect_version_filter_multi_versions_no_signal():
    """≥2 版本串 + 无对比信号 → 交 verdiff（多版本默认对比意图），返回 None。"""
    assert _detect_version_filter("v1.0.0 和 v2.0.0 的配置") is None


def test_detect_version_filter_no_version():
    """无版本串 → 返回 None（走默认当前版本过滤）。"""
    assert _detect_version_filter("HDMI 怎么配置") is None


def test_detect_version_filter_pure_number_not_matched():
    """纯数值两点无 v 前缀（如"1.2 米"）不匹配，避免误识别。"""
    assert _detect_version_filter("1.2 米的线缆长度") is None
