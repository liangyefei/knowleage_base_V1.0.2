"""
VLM API 客户端模块
=================

负责与火山引擎 VLM API 进行通信，支持图片解析和批量处理。

功能说明
--------
- 单图解析：调用 VLM 模型识别图片中的内容（如 PDF 页面文字）
- 批量解析：使用线程池并发处理多张图片
- 完善的错误处理：自定义异常层次结构 + 指数退避重试机制

配置来源
--------
默认配置从 `params/model_config.yaml` 的 `VLM_API_CONFIG` 节读取。
也可以在初始化 VLMClient 时通过参数覆盖默认值。

使用示例
--------
    from server.vlm_api import VLMClient

    # 使用配置文件中的默认配置
    client = VLMClient()

    # 单图解析
    result = client.parse_image(
        'page.png',
        '你是一个文档解析助手，请识别图片中的文字内容。',
        '请完整识别这张PDF页面的所有文字。'
    )

    if result['success']:
        logger.info("{}", result['content'])

    # 批量解析
    results = client.batch_parse(
        ['page1.png', 'page2.png'],
        '你是一个文档解析助手。',
        '请识别第 {page_number}/{total_pages} 页的所有文字。',
        pages_metadata=[
            {'filename': 'doc.pdf', 'page_number': 1, 'total_pages': 2},
            {'filename': 'doc.pdf', 'page_number': 2, 'total_pages': 2},
        ]
    )
"""
import base64
import json
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import httpx

from configs import get_vlm_client_config, log_verbose, logger
from server.aliyun_trace import extract_aliyun_request_id, fmt_req_id, log_dashscope

# ==================== 图片 MIME 类型映射 ====================
_IMAGE_MIME_TYPES = {
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.bmp': 'image/bmp',
}


# ==================== 异常定义 ====================
class VLMClientError(Exception):
    """VLM 客户端错误基类"""


class VLMConnectionError(VLMClientError):
    """网络连接错误"""


class VLMAPIError(VLMClientError):
    """API 返回错误"""


class VLMTimeoutError(VLMClientError):
    """超时错误"""


class VLMImageError(VLMClientError):
    """图片处理错误"""


# ==================== VLM 客户端 ====================
class VLMClient:
    """
    VLM API 客户端类

    封装火山引擎 API 调用，支持图片解析和批量处理。
    默认配置通过 configs.get_vlm_client_config() 从 model_config.yaml 的
    VLM_API_CONFIG 节读取，也可以通过构造函数参数覆盖默认值。

    特性：
    - 复用 httpx.Client 连接池，提升批量处理性能
    - 根据图片扩展名动态生成 MIME 类型
    - 指数退避重试机制（4xx 错误不重试，5xx 错误重试）
    - 完善的异常层次结构，便于调用方精准捕获
    """

    def __init__(
        self,
        api_key: str = None,
        model: str = None,
        endpoint: str = None,
        timeout: int = None,
        max_retries: int = None,
    ):
        """
        初始化 VLM API 客户端

        Args:
            api_key: API 密钥，默认从 configs.get_vlm_client_config() 读取
            model: 模型 ID，默认从 configs.get_vlm_client_config() 读取
            endpoint: API 端点 URL，默认从 configs.get_vlm_client_config() 读取
            timeout: 请求超时时间（秒），默认从 configs.get_vlm_client_config() 读取
            max_retries: 最大重试次数，默认从 configs.get_vlm_client_config() 读取
        """
        # 从 configs 模块获取默认配置
        default_config = get_vlm_client_config()

        self.api_key = api_key or default_config.get("api_key", "")
        self.model = model or default_config.get("model", "")
        self.endpoint = endpoint or default_config.get("endpoint", "")
        self.timeout = timeout or default_config.get("timeout", 60)
        self.max_retries = max_retries or default_config.get("max_retries", 4)

        if not self.api_key:
            raise VLMClientError("VLM API Key 未配置，请检查 model_config.yaml 中的 VLM_API_CONFIG")
        if not self.model:
            raise VLMClientError("VLM Model ID 未配置，请检查 model_config.yaml 中的 VLM_API_CONFIG")
        if not self.endpoint:
            raise VLMClientError("VLM API Endpoint 未配置，请检查 model_config.yaml 中的 VLM_API_CONFIG")

        # 创建复用的 httpx 客户端（连接池复用，提升批量处理性能）
        # 使用 httpx.Timeout 细化超时控制：连接/读取/写入分别为 timeout
        self._http_client = httpx.Client(
            timeout=httpx.Timeout(self.timeout, connect=10.0),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
        )

        logger.info(f"VLM 客户端初始化完成，模型: {self.model}")
        if log_verbose:
            logger.debug(f"API 端点: {self.endpoint}, 超时: {self.timeout}s, 最大重试: {self.max_retries}")

    def __del__(self):
        """析构时关闭 httpx 客户端，释放连接池资源"""
        try:
            if hasattr(self, "_http_client"):
                self._http_client.close()
        except Exception as e:
            logger.debug(f"[vlm-api] failed to close http client in destructor: {e}")
            pass

    @staticmethod
    def encode_image_to_base64(image_path: str) -> dict:
        """
        将图片文件转换为 Base64 编码字符串及对应的 MIME 类型

        Args:
            image_path: 图片文件路径

        Returns:
            包含 base64 编码和 mime_type 的字典:
            - base64: Base64 编码的字符串
            - mime_type: 图片的 MIME 类型（如 image/png）

        Raises:
            VLMImageError: 文件不存在或读取错误
        """
        path = Path(image_path)

        # 检查文件是否存在
        if not path.exists():
            error_msg = f"图片文件不存在: {image_path}"
            logger.error(error_msg)
            raise VLMImageError(error_msg)

        # 检查是否为图片文件
        suffix = path.suffix.lower()
        if suffix not in _IMAGE_MIME_TYPES:
            error_msg = f"不支持的图片格式: {suffix}"
            logger.error(error_msg)
            raise VLMImageError(error_msg)

        try:
            with open(image_path, 'rb') as f:
                image_data = f.read()

            base64_str = base64.b64encode(image_data).decode('utf-8')
            if log_verbose:
                logger.debug(f"图片编码成功: {image_path}, 大小: {len(image_data)} bytes, 类型: {_IMAGE_MIME_TYPES[suffix]}")

            return {
                "base64": base64_str,
                "mime_type": _IMAGE_MIME_TYPES[suffix],
            }

        except PermissionError as e:
            error_msg = f"无法读取图片文件（权限错误）: {image_path}"
            logger.error(error_msg)
            raise VLMImageError(error_msg) from e

        except Exception as e:
            error_msg = f"读取图片文件失败: {image_path}, 错误: {str(e)}"
            logger.error(error_msg)
            raise VLMImageError(error_msg) from e

    def close(self):
        """显式关闭 httpx 客户端，释放连接池资源"""
        if hasattr(self, "_http_client"):
            self._http_client.close()

    def parse_image(
        self,
        image_path: str,
        system_prompt: str,
        user_prompt: str,
        metadata: dict | None = None,
    ) -> dict:
        """
        解析单张图片，调用 VLM API

        Args:
            image_path: 图片文件路径
            system_prompt: 系统提示词
            user_prompt: 用户提示词（可以是模板）
            metadata: 可选的元信息字典，用于格式化提示词，包含：
                - filename: 文件名
                - page_number: 页码
                - total_pages: 总页数
                - timestamp: 解析时间

        Returns:
            解析结果字典，包含:
            - success: 是否成功
            - content: 解析内容（成功时）
            - error: 错误信息（失败时）
            - response_time: 响应时间（秒）

        Raises:
            VLMConnectionError: 网络连接错误
            VLMAPIError: API 返回错误
            VLMTimeoutError: 请求超时
            VLMImageError: 图片处理错误
        """
        _CONTENT_KEY = 'content'
        start_time = time.time()

        logger.info(f"开始解析图片: {image_path}")

        # 编码图片，获取 base64 和 MIME 类型
        image_data = self.encode_image_to_base64(image_path)
        base64_image = image_data["base64"]
        mime_type = image_data["mime_type"]

        # 如果提供了元信息，格式化用户提示词
        final_user_prompt = user_prompt
        if metadata:
            try:
                final_user_prompt = user_prompt.format(
                    filename=metadata.get('filename', '未知'),
                    page_number=metadata.get('page_number', 0),
                    total_pages=metadata.get('total_pages', 0),
                    timestamp=metadata.get('timestamp', ''),
                )
            except KeyError as e:
                logger.warning(f"提示词模板格式化失败，使用原始提示词: {str(e)}")
                final_user_prompt = user_prompt

        # 构建 API 请求体（OpenAI 兼容格式，MIME 类型根据实际扩展名动态生成）
        request_body = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    _CONTENT_KEY: system_prompt,
                },
                {
                    "role": "user",
                    _CONTENT_KEY: [
                        {"type": "text", "text": final_user_prompt},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:{mime_type};base64,{base64_image}"
                            },
                        },
                    ],
                },
            ],
        }

        if log_verbose:
            logger.debug(f"VLM 请求: endpoint={self.endpoint}, model={self.model}, mime={mime_type}")

        # 发送请求（带重试机制）
        last_exception = None

        _ERROR_KEY = 'error'
        _WARNING_KEY = 'warning'
        for attempt in range(1, self.max_retries + 1):
            try:
                if log_verbose:
                    logger.debug(f"API 调用尝试 {attempt}/{self.max_retries}")

                # 复用共享的 httpx 客户端（连接池复用）
                response = self._http_client.post(
                    self.endpoint,
                    json=request_body,
                )
                # httpx 直调：从响应头取 DashScope requestID（阿里云限流排查用）
                _req_id = extract_aliyun_request_id(response)
                log_dashscope(self.model, "vlm_call_done", _req_id, level="debug",
                              attempt=f"{attempt}/{self.max_retries}")

                # 检查 HTTP 状态码
                if response.status_code != 200:
                    error_msg = f"API 返回错误状态码: {response.status_code}"
                    try:
                        error_detail = response.json()
                        error_msg += f", 详情: {error_detail}"
                    except json.JSONDecodeError:
                        error_msg += f", 响应: {response.text[:200]}"

                    logger.error(f"{error_msg}{fmt_req_id(_req_id)}")
                    log_dashscope(self.model, "vlm_non_200", _req_id, level=_ERROR_KEY,
                                  status=response.status_code, attempt=f"{attempt}/{self.max_retries}")

                    # 对于客户端错误（4xx），不重试
                    if 400 <= response.status_code < 500:
                        raise VLMAPIError(error_msg)

                    # 对于服务端错误（5xx），继续重试
                    last_exception = VLMAPIError(error_msg)

                else:
                    # 请求成功
                    response_time = time.time() - start_time
                    result = response.json()

                    # 提取解析内容
                    try:
                        content = result.get('choices', [{}])[0].get('message', {}).get(_CONTENT_KEY, '')
                    except (KeyError, IndexError):
                        content = str(result)
                        logger.warning("无法提取标准响应格式，返回原始结果")

                    logger.info(f"图片解析成功: {image_path}, 响应时间: {response_time:.2f}s{fmt_req_id(_req_id)}")

                    return {
                        "success": True,
                        _CONTENT_KEY: content,
                        _ERROR_KEY: None,
                        "response_time": response_time,
                        "image_path": image_path,
                        "metadata": metadata,
                    }

            except httpx.TimeoutException:
                error_msg = f"API 请求超时: {self.timeout}s"
                logger.warning(f"{error_msg}, 尝试 {attempt}/{self.max_retries}")
                log_dashscope(self.model, "vlm_timeout", None, level=_WARNING_KEY,
                              reason="httpx TimeoutException", attempt=f"{attempt}/{self.max_retries}")
                last_exception = VLMTimeoutError(error_msg)

            except httpx.ConnectError as e:
                error_msg = f"网络连接错误: {str(e)}"
                logger.warning(f"{error_msg}, 尝试 {attempt}/{self.max_retries}")
                log_dashscope(self.model, "vlm_connect_error", None, level=_WARNING_KEY,
                              reason=str(e)[:150], attempt=f"{attempt}/{self.max_retries}")
                last_exception = VLMConnectionError(error_msg)

            except httpx.HTTPError as e:
                error_msg = f"请求异常: {str(e)}"
                logger.warning(f"{error_msg}, 尝试 {attempt}/{self.max_retries}")
                log_dashscope(self.model, "vlm_http_error", None, level=_WARNING_KEY,
                              reason=str(e)[:150], attempt=f"{attempt}/{self.max_retries}")
                last_exception = VLMConnectionError(error_msg)

            # 如果还有重试机会，使用指数退避
            if attempt < self.max_retries:
                wait_time = 2 ** attempt  # 指数退避: 2, 4, 8...
                logger.info(f"等待 {wait_time}s 后重试...")
                time.sleep(wait_time)

        # 所有重试都失败
        response_time = time.time() - start_time
        error_msg = f"API 调用失败，已重试 {self.max_retries} 次"
        logger.error(error_msg)

        return {
            "success": False,
            _CONTENT_KEY: None,
            _ERROR_KEY: str(last_exception) if last_exception else error_msg,
            "response_time": response_time,
            "image_path": image_path,
            "metadata": metadata,
        }

    def batch_parse(
        self,
        image_paths: list[str],
        system_prompt: str,
        user_prompt_template: str,
        **kwargs,
    ) -> list[dict]:
        """
        批量处理多个图片

        Args:
            image_paths: 图片路径列表
            system_prompt: 系统提示词
            user_prompt_template: 用户提示词模板（可包含 {filename}, {page_number} 等占位符）
            pages_metadata: 每页的元信息列表，与 image_paths 一一对应（默认 None，通过 kwargs 传入）
            progress_callback: 进度回调函数，签名: callback(current, total, result)（默认 None，通过 kwargs 传入）
            max_workers: 最大并发数，默认 8（通过 kwargs 传入）

        Returns:
            解析结果列表，每个元素为 parse_image 返回的字典
        """
        pages_metadata = kwargs.get("pages_metadata")
        progress_callback = kwargs.get("progress_callback")
        max_workers = kwargs.get("max_workers", 8)
        _SUCCESS_KEY = 'success'
        if not image_paths:
            logger.warning("图片路径列表为空，无内容需要处理")
            return []

        total = len(image_paths)
        logger.info(f"开始批量处理 {total} 张图片，并发数: {max_workers}")

        # 如果没有提供元信息，创建空列表
        if pages_metadata is None:
            pages_metadata = [None] * total

        # 使用线程池并发处理
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            # 提交所有任务
            future_to_info = {}
            for idx, path in enumerate(image_paths):
                metadata = pages_metadata[idx] if idx < len(pages_metadata) else None
                future_to_info[
                    executor.submit(
                        self.parse_image,
                        path,
                        system_prompt,
                        user_prompt_template,
                        metadata,
                    )
                ] = (idx, path)

            # 收集结果（按原始顺序）
            results = [None] * total
            completed = 0

            for future in as_completed(future_to_info):
                idx, path = future_to_info.get(future, (None, None))

                try:
                    result = future.result()
                    results[idx] = result
                    completed += 1

                    # 调用进度回调
                    if progress_callback:
                        try:
                            progress_callback(completed, total, result)
                        except Exception as e:
                            logger.warning(f"进度回调执行失败: {str(e)}")

                    # 记录进度
                    if log_verbose:
                        if result[_SUCCESS_KEY]:
                            logger.debug(f"进度: {completed}/{total}, 成功: {path}")
                        else:
                            logger.warning(f"进度: {completed}/{total}, 失败: {path}, 错误: {result.get('error')}")

                except Exception as e:
                    # 任务执行异常
                    error_result = {
                        _SUCCESS_KEY: False,
                        "content": None,
                        "error": str(e),
                        "response_time": 0,
                        "image_path": path,
                        "metadata": pages_metadata[idx] if idx < len(pages_metadata) else None,
                    }
                    results[idx] = error_result
                    completed += 1

                    logger.error(f"任务执行异常: {path}, 错误: {str(e)}")

                    if progress_callback:
                        try:
                            progress_callback(completed, total, error_result)
                        except Exception as cb_e:
                            logger.warning(f"进度回调执行失败: {str(cb_e)}")

        # 统计结果
        success_count = sum(1 for r in results if r and r[_SUCCESS_KEY])
        fail_count = total - success_count

        logger.info(f"批量处理完成: 成功 {success_count}/{total}, 失败 {fail_count}/{total}")

        return results


if __name__ == "__main__":
    logger.info("VLMClient 模块加载成功")
    logger.info("使用方法:")
    logger.info("  from server.vlm_api import VLMClient")
    logger.info("  client = VLMClient()  # 使用配置文件中的默认配置")
    logger.info("  result = client.parse_image('image.png', 'system prompt', 'user prompt')")
    logger.info("  result = client.parse_image('image.png', 'system', 'user', metadata={'filename': 'doc.pdf', 'page_number': 1})")  # pylint: disable=line-too-long
