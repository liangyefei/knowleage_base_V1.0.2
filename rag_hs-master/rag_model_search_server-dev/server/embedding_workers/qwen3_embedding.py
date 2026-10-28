"""
Qwen3 Embedding Worker

Qwen3 模型的向量化实现，继承自 HttpEmbeddings
提供针对查询和文档的专业化向量化处理
"""

from configs import logger

from .base import HttpEmbeddings


class Qwen3Embedding(HttpEmbeddings):
    """
    Qwen3 Embedding 实现类 / Qwen3 Embedding Implementation

    针对 Qwen3 模型的向量化服务，继承自 HttpEmbeddings
    提供针对查询（query）和文档（document）的专业化向量化处理

    Inherits from HttpEmbeddings for Qwen3 model vectorization service.
    Provides specialized vectorization for queries and documents.
    """

    def __repr__(self) -> str:
        """
        返回对象的字符串表示 / Return String Representation of Object

        Returns:
            包含类名和关键参数的字符串 / String containing class name and key parameters
        """
        return f"Qwen3Embedding(api_base_url='{self.api_base_url}', embedding_dim={self.embedding_dim}, timeout={self.timeout}, max_workers={self.max_workers}, batch_size={self.batch_size})"  # pylint: disable=line-too-long

    def embed_query(self, text: str) -> list[float]:
        """
        单个查询向量化 / Single Query Vectorization

        对查询文本进行向量化，自动添加 prompt_name="query" 参数以优化查询效果
        Vectorizes query text, automatically adds prompt_name="query" parameter
        to optimize query performance.

        Args:
            text: 待向量化的查询文本 / Query text to be vectorized

        Returns:
            向量，一个浮点数列表 / Vector, a list of floats

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not text:
            logger.warning("embed_query: 传入的文本为空 / Input text is empty")
            return []

        try:
            url = f"{self.api_base_url}/embed"
            # 为查询添加 prompt_name 参数 / Add prompt_name parameter for queries
            payload = {
                "texts": [text],
                "prompt_name": "query"
            }

            logger.debug(f"embed_query: 请求 {url}，文本长度: {len(text)} / Request text length: {len(text)}")

            response = self._session.post(
                url,
                json=payload,
                timeout=self.timeout
            )
            response.raise_for_status()

            result = response.json()
            embeddings = result.get("embeddings", [])

            if not embeddings or len(embeddings) == 0:
                logger.warning(f"embed_query: API 返回空向量，响应: {result} / API returned empty vector")
                return []

            embedding = embeddings[0]
            logger.debug(f"embed_query: 成功获取向量，维度: {len(embedding)} / Successfully obtained vector")

            return embedding

        except Exception as e:
            error_msg = f"embed_query: 请求失败: {e} / Request failed: {e}"
            logger.error(error_msg)
            raise ValueError(error_msg) from e

    def embed_texts_with_prompt(self, texts: list[str], prompt_name: str) -> list[list[float]]:
        """
        使用自定义 prompt 进行文本向量化 / Text Vectorization with Custom Prompt

        支持使用不同的 prompt_name 对文本进行向量化，适用于不同的应用场景
        Supports vectorization with different prompt_name values for various use cases.

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized
            prompt_name: prompt 名称，如 "query"、"document" 等
                         Prompt name, such as "query", "document", etc.

        Returns:
            向量列表，每个向量是一个浮点数列表
            List of vectors, each vector is a list of floats

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails

        Example:
            # 对查询文本进行向量化 / Vectorize query texts
            query_embeddings = model.embed_texts_with_prompt(
                texts=["什么是机器学习？"],
                prompt_name="query"
            )

            # 对文档文本进行向量化 / Vectorize document texts
            doc_embeddings = model.embed_texts_with_prompt(
                texts=["机器学习是人工智能的一个分支..."],
                prompt_name="document"
            )
        """
        if not texts:
            logger.warning("embed_texts_with_prompt: 传入的文本列表为空 / Input text list is empty")
            return []

        if not prompt_name:
            logger.warning("embed_texts_with_prompt: prompt_name 为空，使用默认值 / prompt_name is empty, using default")
            prompt_name = "query"

        try:
            url = f"{self.api_base_url}/embed"
            payload = {
                "texts": texts,
                "prompt_name": prompt_name
            }

            logger.debug(
                f"embed_texts_with_prompt: 请求 {url}，"
                f"文本数量: {len(texts)}，prompt_name: {prompt_name} / "
                f"Request text count: {len(texts)}, prompt_name: {prompt_name}"
            )

            response = self._session.post(
                url,
                json=payload,
                timeout=self.timeout
            )
            response.raise_for_status()

            result = response.json()
            embeddings = result.get("embeddings", [])

            if not embeddings:
                logger.warning(
                    f"embed_texts_with_prompt: API 返回空向量，响应: {result} / "
                    f"API returned empty vector"
                )
                return []

            logger.info(
                f"embed_texts_with_prompt: 成功获取 {len(embeddings)} 个向量，"
                f"维度: {result.get('embedding_dim', self.embedding_dim)} / "
                f"Successfully obtained {len(embeddings)} vectors"
            )

            return embeddings

        except Exception as e:
            error_msg = (
                f"embed_texts_with_prompt: 请求失败: {e} / "
                f"Request failed: {e}"
            )
            logger.error(error_msg)
            raise ValueError(error_msg) from e

    async def aembed_texts_with_prompt(self, texts: list[str], prompt_name: str) -> list[list[float]]:
        """
        异步使用自定义 prompt 进行文本向量化 / Async Text Vectorization with Custom Prompt

        复用基类 _aembed_batch 的分片并发逻辑，支持指定 prompt_name。

        Args:
            texts: 待向量化的文本列表 / List of texts to be vectorized
            prompt_name: prompt 名称，如 "query"、"document" 等
                         Prompt name, such as "query", "document", etc.

        Returns:
            向量列表，每个向量是一个浮点数列表
            List of vectors, each vector is a list of floats

        Raises:
            ValueError: 当 API 请求失败时抛出 / Raised when API request fails
        """
        if not texts:
            logger.warning("aembed_texts_with_prompt: 传入的文本列表为空 / Input text list is empty")
            return []

        if not prompt_name:
            logger.warning("aembed_texts_with_prompt: prompt_name 为空，使用默认值 / prompt_name is empty, using default")
            prompt_name = "query"

        return await self._aembed_batch(texts, prompt_name)
