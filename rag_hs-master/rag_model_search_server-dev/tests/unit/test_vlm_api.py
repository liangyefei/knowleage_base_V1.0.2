"""VLMClient 白盒单元测试。

覆盖 server.vlm_api.VLMClient 的核心逻辑:
- TestVlmClientInit: 缺 api_key/model/endpoint → VLMClientError(三处);patch httpx.Client 避免真建连接
- TestEncodeImageToBase64: 真实小 .png 成功、不存在路径→VLMImageError、.txt 后缀→VLMImageError、
  open 抛 PermissionError→VLMImageError
- TestParseImage: 200 正常提取、200 回退 str(result)、4xx 立即抛 VLMAPIError(不重试)、
  5xx 重试到 max_retries 后 success=False、httpx.TimeoutException→VLMTimeoutError、
  httpx.ConnectError→VLMConnectionError;patch time.sleep 避免阻塞
- TestBatchParse: 空 image_paths→[]、parse_image 不同 success(含 side_effect 抛异常)验证顺序还原、
  progress_callback 被调用且其异常被吞、pages_metadata 长度不足取 None

所有 HTTP 调用均通过 mock 注入,禁止真实网络请求。
"""
# pylint: disable=no-use-sys-path-insert
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import base64
from unittest.mock import MagicMock, patch

import httpx
import pytest

from server.vlm_api import (
    VLMAPIError,
    VLMClient,
    VLMClientError,
    VLMConnectionError,
    VLMImageError,
    VLMTimeoutError,
)


def _build_client(max_retries=3, timeout=60):
    """构造 VLMClient 实例,patch get_vlm_client_config 与 httpx.Client 避免真连接。"""
    with patch("server.vlm_api.get_vlm_client_config", return_value={}), \
         patch("server.vlm_api.httpx.Client"):
        return VLMClient(
            api_key="fake-key",
            model="fake-model",
            endpoint="http://vlm.example.com/v1/chat/completions",
            timeout=timeout,
            max_retries=max_retries,
        )


class TestVlmClientInit:
    """缺 api_key/model/endpoint → VLMClientError;patch httpx.Client 避免真建连接。"""

    @staticmethod
    def test_missing_api_key_raises():
        config = {"api_key": "", "model": "m", "endpoint": "http://x"}
        with patch("server.vlm_api.get_vlm_client_config", return_value=config), \
             patch("server.vlm_api.httpx.Client") as mock_client:
            with pytest.raises(VLMClientError) as exc:
                VLMClient()
        assert "API Key" in str(exc.value)
        mock_client.assert_not_called()

    @staticmethod
    def test_missing_model_raises():
        config = {"api_key": "k", "model": "", "endpoint": "http://x"}
        with patch("server.vlm_api.get_vlm_client_config", return_value=config), \
             patch("server.vlm_api.httpx.Client"):
            with pytest.raises(VLMClientError) as exc:
                VLMClient()
        assert "Model" in str(exc.value)

    @staticmethod
    def test_missing_endpoint_raises():
        config = {"api_key": "k", "model": "m", "endpoint": ""}
        with patch("server.vlm_api.get_vlm_client_config", return_value=config), \
             patch("server.vlm_api.httpx.Client"):
            with pytest.raises(VLMClientError) as exc:
                VLMClient()
        assert "Endpoint" in str(exc.value)

    @staticmethod
    def test_explicit_params_override_config():
        with patch("server.vlm_api.get_vlm_client_config", return_value={}), \
             patch("server.vlm_api.httpx.Client") as mock_client:
            client = VLMClient(api_key="k", model="m", endpoint="http://x")
        assert client.api_key == "k"
        assert client.model == "m"
        assert client.endpoint == "http://x"
        mock_client.assert_called_once()


class TestEncodeImageToBase64:
    """encode_image_to_base64: 成功/不存在/非图片后缀/权限错误。"""

    @staticmethod
    def test_png_success(tmp_path):
        img = tmp_path / "pic.png"
        raw = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
        img.write_bytes(raw)

        client = _build_client()
        result = client.encode_image_to_base64(str(img))

        assert result["base64"] == base64.b64encode(raw).decode("utf-8")
        assert result["mime_type"] == "image/png"

    @staticmethod
    def test_nonexistent_path_raises():
        client = _build_client()
        with pytest.raises(VLMImageError) as exc:
            client.encode_image_to_base64("/no/such/file.png")
        assert "不存在" in str(exc.value)

    @staticmethod
    def test_txt_suffix_raises(tmp_path):
        txt = tmp_path / "notes.txt"
        txt.write_bytes(b"hello")

        client = _build_client()
        with pytest.raises(VLMImageError) as exc:
            client.encode_image_to_base64(str(txt))
        assert "不支持" in str(exc.value)

    @staticmethod
    def test_permission_error_raises(tmp_path):
        img = tmp_path / "perm.png"
        img.write_bytes(b"\x89PNG\r\n\x1a\n")

        client = _build_client()
        real_open = open

        def fake_open(path, *args, **kwargs):
            if Path(path).name == "perm.png":
                raise PermissionError("denied")
            return real_open(path, *args, **kwargs)

        with patch("builtins.open", side_effect=fake_open):
            with pytest.raises(VLMImageError) as exc:
                client.encode_image_to_base64(str(img))
        assert "权限" in str(exc.value)


class TestParseImage:
    """parse_image: 200 正常/200 回退/4xx 立即抛/5xx 重试/超时/连接错误。"""

    @staticmethod
    def _make_image(tmp_path):
        img = tmp_path / "page.png"
        img.write_bytes(b"\x89PNG\r\n\x1a\n")
        return img

    def test_200_extracts_content(self, tmp_path):
        client = _build_client(max_retries=1)
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {
            "choices": [{"message": {"content": "parsed text"}}]
        }
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep"):
            mock_http.post.return_value = mock_resp
            result = client.parse_image(str(self._make_image(tmp_path)), "sys", "user")

        assert result["success"] is True
        assert result["content"] == "parsed text"
        assert result["error"] is None

    def test_200_fallback_to_str_result(self, tmp_path):
        client = _build_client(max_retries=1)
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"choices": []}
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep"):
            mock_http.post.return_value = mock_resp
            result = client.parse_image(str(self._make_image(tmp_path)), "sys", "user")

        assert result["success"] is True
        assert result["content"] == str({"choices": []})

    def test_4xx_raises_immediately_no_retry(self, tmp_path):
        client = _build_client(max_retries=3)
        mock_resp = MagicMock()
        mock_resp.status_code = 404
        mock_resp.json.return_value = {"error": "not found"}
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep") as mock_sleep:
            mock_http.post.return_value = mock_resp
            with pytest.raises(VLMAPIError):
                client.parse_image(str(self._make_image(tmp_path)), "sys", "user")
        mock_http.post.assert_called_once()
        mock_sleep.assert_not_called()

    def test_5xx_retries_then_returns_failure(self, tmp_path):
        client = _build_client(max_retries=2)
        mock_resp = MagicMock()
        mock_resp.status_code = 503
        mock_resp.json.return_value = {"error": "server error"}
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep") as mock_sleep:
            mock_http.post.return_value = mock_resp
            result = client.parse_image(str(self._make_image(tmp_path)), "sys", "user")

        assert result["success"] is False
        assert mock_http.post.call_count == 2
        assert mock_sleep.call_count == 1
        assert "503" in str(result["error"])

    def test_timeout_returns_failure_with_timeout_error(self, tmp_path):
        client = _build_client(max_retries=2)
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep") as mock_sleep:
            mock_http.post.side_effect = httpx.TimeoutException("read timeout")
            result = client.parse_image(str(self._make_image(tmp_path)), "sys", "user")

        assert result["success"] is False
        assert mock_http.post.call_count == 2
        assert mock_sleep.call_count == 1
        assert "超时" in str(result["error"])

    def test_connect_error_returns_failure_with_connection_error(self, tmp_path):
        client = _build_client(max_retries=2)
        with patch.object(client, "_http_client") as mock_http, \
             patch("server.vlm_api.time.sleep") as mock_sleep:
            mock_http.post.side_effect = httpx.ConnectError("refused")
            result = client.parse_image(str(self._make_image(tmp_path)), "sys", "user")

        assert result["success"] is False
        assert mock_http.post.call_count == 2
        assert mock_sleep.call_count == 1
        assert "连接" in str(result["error"])


class TestBatchParse:
    """batch_parse: 空列表、顺序还原、progress_callback 异常被吞、pages_metadata 不足取 None。"""

    @staticmethod
    def test_empty_image_paths_returns_empty():
        client = _build_client()
        assert not client.batch_parse([], "sys", "user")

    @staticmethod
    def test_results_order_restored_with_partial_failure():
        _IMAGE_PATH_KEY = 'image_path'
        _SUCCESS_KEY = 'success'
        client = _build_client(max_retries=1)

        def fake_parse(path, sp, up, metadata=None):
            if path == "p1":
                return {_SUCCESS_KEY: True, "content": "c1", _IMAGE_PATH_KEY: path,
                        "metadata": metadata, "error": None, "response_time": 0.1}
            if path == "p2":
                raise RuntimeError("parse boom")
            return {_SUCCESS_KEY: False, "content": None, _IMAGE_PATH_KEY: path,
                    "metadata": metadata, "error": "fail", "response_time": 0.2}

        with patch.object(client, "parse_image", side_effect=fake_parse):
            results = client.batch_parse(["p1", "p2", "p3"], "sys", "user")

        assert len(results) == 3
        assert results[0][_IMAGE_PATH_KEY] == "p1"
        assert results[0][_SUCCESS_KEY] is True
        assert results[1][_IMAGE_PATH_KEY] == "p2"
        assert results[1][_SUCCESS_KEY] is False
        assert "parse boom" in str(results[1]["error"])
        assert results[2][_IMAGE_PATH_KEY] == "p3"
        assert results[2][_SUCCESS_KEY] is False

    @staticmethod
    def test_progress_callback_called_and_exception_swallowed():
        client = _build_client(max_retries=1)
        calls = []

        def cb(cur, total, result):
            calls.append((cur, total))
            raise RuntimeError("cb boom")

        def fake_parse(path, sp, up, metadata=None):
            return {"success": True, "content": "c", "image_path": path,
                    "metadata": metadata, "error": None, "response_time": 0}

        with patch.object(client, "parse_image", side_effect=fake_parse):
            results = client.batch_parse(["p1", "p2"], "sys", "user", progress_callback=cb)

        assert len(calls) == 2
        assert len(results) == 2

    @staticmethod
    def test_pages_metadata_length_insufficient_takes_none():
        client = _build_client(max_retries=1)
        captured = []

        def fake_parse(path, sp, up, metadata=None):
            captured.append(metadata)
            return {"success": True, "content": "c", "image_path": path,
                    "metadata": metadata, "error": None, "response_time": 0}

        with patch.object(client, "parse_image", side_effect=fake_parse):
            results = client.batch_parse(
                ["p1", "p2"], "sys", "user",
                pages_metadata=[{"filename": "f1"}],
            )

        assert {"filename": "f1"} in captured
        assert None in captured
        assert results[0]["image_path"] == "p1"
        assert results[1]["image_path"] == "p2"
