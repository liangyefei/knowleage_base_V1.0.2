from collections.abc import AsyncIterator
from typing import Any

from langchain.callbacks.base import AsyncCallbackHandler
from langchain.schema import LLMResult


class DeepSeekCallbackHandler(AsyncCallbackHandler):
    """专门处理 DeepSeek 模型的 reasoning_content 回调"""

    def __init__(self):
        self.reasoning_buffer = ""
        self.content_buffer = ""
        self.queue = asyncio.Queue()
        self.done = False

    async def on_llm_new_token(self, token: str, **kwargs: Any) -> None:
        """处理每个新 token"""
        # 这里需要根据实际传入的 token 格式来判断
        # DeepSeek 的 token 可能包含 reasoning 标记
        await self.queue.put({"token": token, "type": "content"})

    async def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        self.done = True
        await self.queue.put({"type": "done"})

    async def aiter(self) -> AsyncIterator[dict[str, Any]]:
        while not self.done or not self.queue.empty():
            try:
                item = await asyncio.wait_for(self.queue.get(), timeout=0.1)
                yield item
            except asyncio.TimeoutError:
                continue
