# LAC 同步客户端归档代码

本文件归档了原 `server/paddle_service/lac_service.py` 的同步实现代码。
该模块已被 `server/chat/pipeline/lac_keywords.py`（异步 httpx 实现，带词性过滤与降级）取代，
`server/paddle_service/` 目录已删除。代码保存于此供历史参考或未来恢复。

## 功能说明

通过调用独立部署的 LAC HTTP 微服务（`release/images/lac`）实现中文分词与词性标注。
主进程无需安装 paddlepaddle / paddlenlp / LAC。

服务地址通过 `params/server_config.yaml` 的 `LAC_SERVICE_BASE_URL` 配置。
接口契约：`POST /p/lac`，请求 `{"base_word": text}`，响应 `{"output": [[词列表],[词性列表]]}`

> 注意：当前在用的 LAC 客户端是 `server/chat/pipeline/lac_keywords.py` 的 `_extract_keywords_lac`（异步 httpx + 词性过滤 + 降级回退 LLM），由 multi chat 主流程路由阶段调用。本归档的同步实现仅为历史版本，恢复前请先评估是否能直接复用 `lac_keywords.py`。

## 归档代码

```python
import requests

from configs import logger, LAC_SERVICE_BASE_URL


def _call_http_lac(text: str):
    """通过 HTTP 微服务调用 LAC 分词

    接口契约：POST /p/lac，请求 {"base_word": text}，响应 {"output": [[词列表],[词性列表]]}
    """
    if not LAC_SERVICE_BASE_URL:
        raise RuntimeError(
            "LAC_SERVICE_BASE_URL 未配置，"
            "请在 params/server_config.yaml 设置 LAC_SERVICE_BASE_URL"
        )
    url = f"{LAC_SERVICE_BASE_URL.rstrip('/')}/p/lac"
    try:
        response = requests.post(
            url,
            json={"base_word": text},
            timeout=10
        )
    except requests.RequestException as e:
        raise RuntimeError(f"LAC HTTP 调用网络错误 ({url}): {e}") from e
    if response.status_code != 200:
        raise RuntimeError(
            f"LAC HTTP 调用失败: {response.status_code} {response.text}"
        )
    return response.json().get("output", [[], []])


def get_lac():
    """启动时对 LAC 微服务做健康检查（由 `server/api.py` 的 startup 事件触发）

    Returns:
        None（HTTP 模式下无本地模型实例）
    """
    if not LAC_SERVICE_BASE_URL:
        logger.warning("LAC_SERVICE_BASE_URL 未配置，LAC 分词功能将不可用")
        return None
    url = f"{LAC_SERVICE_BASE_URL.rstrip('/')}/health"
    try:
        resp = requests.get(url, timeout=5)
        if resp.status_code == 200:
            logger.info(f"LAC HTTP 微服务已就绪: {LAC_SERVICE_BASE_URL}")
        else:
            logger.warning(f"LAC HTTP 微服务健康检查异常: {resp.status_code}")
    except Exception as e:
        logger.warning(f"LAC HTTP 微服务健康检查失败: {e}（run_lac 调用时将抛错）")
    return None


def run_lac(text: str):
    """调用 LAC 分词

    Args:
        text: 待分词的文本

    Returns:
        [分词列表, 词性列表]
        例: [['百度', '是', '一家', '高科技', '公司'], ['ORG', 'v', 'm', 'n', 'n']]
    """
    return _call_http_lac(text)
```

## 恢复步骤（如需）

1. 在合适位置（如 `server/paddle_service/lac_service.py` 或新模块）创建文件，粘贴上方代码
2. 确认 `params/server_config.yaml` 中配置了 `LAC_SERVICE_BASE_URL`
3. 确认 `release/images/lac` 微服务已部署并可达
4. 在需要同步分词的模块中 `from <模块路径> import run_lac`
5. 若仅需异步关键词提取，优先复用 `server/chat/pipeline/lac_keywords.py` 的 `_extract_keywords_lac`
