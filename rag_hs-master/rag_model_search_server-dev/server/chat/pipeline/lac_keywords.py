"""LAC 词法分析服务提取检索关键词（10ms 级，替代 LLM 关键词提取）。"""
import time

import httpx

from configs import LAC_SERVICE_BASE_URL, logger

# 模块级 AsyncClient 单例：跨请求复用连接池，省 TLS 握手；进程退出时由 httpx 自动清理
# 连接限制：防止高并发下连接池无上限增长（默认 httpx 无 max_connections 限制）
_lac_client = httpx.AsyncClient(
    timeout=2.0,
    limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
)

# LAC 词性标注提取的关键词词性集合（名词/其他专名/动名词/英文/数词）
_LAC_KW_TAGS = ("n", "nz", "vn", "eng", "m", "f", "PER", "ORG", "LOC", "TIME")
# 无区分度的单字词（LAC 会把"中/上/下"等方位词标为 f）
_LAC_SKIP_WORDS = {"中", "上", "下", "内", "外", "前", "后", "里"}


async def _extract_keywords_lac(query: str) -> list[str]:
    """用 LAC 词法分析服务提取检索关键词（10ms 级，替代 LLM 关键词提取）。

    只取名词性词（n/nz/vn/eng/m），去重、去无区分度词，最多 4 个。
    任何异常（服务未配置/超时/解析失败）返回空列表，由调用方回退到 LLM keywords。
    """
    if not LAC_SERVICE_BASE_URL:
        return []
    _lac_t0 = time.time()
    try:
        resp = await _lac_client.post(
            f"{LAC_SERVICE_BASE_URL.rstrip('/')}/p/lac",
            json={"base_word": query},
            headers={"content-type": "application/json"},
        )
        data = resp.json()
        words = data.get("output", [[], []])[0]
        tags = data.get("output", [[], []])[1]
        kws: list[str] = []
        for w, t in zip(words, tags, strict=False):
            w = str(w).strip()
            if len(w) < 2 or w in _LAC_SKIP_WORDS or w == query:
                continue
            if t in _LAC_KW_TAGS or (isinstance(t, str) and t[:1] in ("n", "f")):
                if w not in kws:
                    kws.append(w)
            if len(kws) >= 4:
                break
        _lac_t1 = time.time()
        logger.info(f"[timing] lac: {_lac_t1 - _lac_t0:.3f}s | kws={kws} | query={query[:40]!r}")
        return kws
    except Exception as e:
        _lac_t1 = time.time()
        logger.warning(f"[timing] lac: {_lac_t1 - _lac_t0:.3f}s (FAIL) | 回退 LLM keywords: {e}")
        return []
