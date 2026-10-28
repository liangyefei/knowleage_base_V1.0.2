"""阿里云联网搜索功能测试脚本

使用前需要先配置，在params/basic_config.yaml中设置：

INTERNET_SEARCH:
  enable: true
  mode: "http"  # 或 "official"
  # HTTP模式配置
  api_url: "https://cloud-igs.alixuncs.com/search/unified"
  api_token: "vt0HhU8Ph-gbEvusEPD6FkHyuiZMeb42YTg0MWRiNA"
  app_id: "2109528528619499522"
  num_results: 5
"""
# pylint: disable=no-use-sys-path-insert
import asyncio
import sys
sys.path.insert(0, 'd:\\Code\\haisi\\haisi_rag_models_server')

from configs.logger_manager import logger
from server.internet_search.aliyun_search import get_aliyun_search_client


async def test_search():
    """测试联网搜索功能"""
    _NA_KEY = 'N/A'
    client = get_aliyun_search_client()
    if client is None:
        logger.info("❌ 联网搜索客户端创建失败，请检查配置")
        logger.info("\n请确保在params/basic_config.yaml中配置正确：")
        logger.info("  INTERNET_SEARCH:")
        logger.info("    enable: true")
        logger.info("    mode: http  # 或 official")
        logger.info("    api_token: your-api-token  # HTTP模式")
        return False

    mode = client.__class__.__name__
    logger.info("✅ 联网搜索客户端创建成功，模式: {}", mode)

    query = "上海车展2025最火爆的品牌"
    logger.info("\n🔍 开始搜索: {}", query)

    docs = await client.search(query)
    if docs is None:
        logger.info("❌ 搜索失败")
        return False

    if len(docs) == 0:
        logger.info("⚠️ 搜索返回空结果")
        return True

    logger.info("✅ 搜索成功，返回 {} 条结果:\n", len(docs))
    for i, doc in enumerate(docs):
        logger.info("=" * 60)
        logger.info("结果 {}:", i + 1)
        logger.info("  标题: {}", doc.metadata.get('title', _NA_KEY))
        logger.info("  链接: {}", doc.metadata.get('source', _NA_KEY))
        logger.info("  网站: {}", doc.metadata.get('hostname', _NA_KEY))
        logger.info("  重排序分数: {}", doc.metadata.get('rerank_score', _NA_KEY))
        logger.info("  发布时间: {}", doc.metadata.get('published_time', _NA_KEY))
        logger.info("  文档类型: {}", doc.metadata.get('doc_type', _NA_KEY))
        logger.info("  内容:\n{}", doc.page_content)

    logger.info("\n" + "=" * 60)
    logger.info("✅ 测试完成")
    return True


if __name__ == "__main__":
    success = asyncio.run(test_search())
    sys.exit(0 if success else 1)