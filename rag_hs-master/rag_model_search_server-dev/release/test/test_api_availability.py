"""测试阿里云联网搜索API可用性

测试两种方式：
1. HTTP REST接口（当前配置方式）
2. 官方SDK接口（备选方式）
"""

# pylint: disable=redefined-outer-name
# pylint: disable=no-use-sys-path-insert
import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import asyncio
import sys
import os
# 添加项目根目录到Python路径
sys.path.insert(0, '/mnt/d/Code/haisi/haisi_rag_models_server')

from configs import KB_CONFIG
from server.internet_search.aliyun_search import get_aliyun_search_client

_S_KEY = '%s'
_NA_KEY = 'N/A'


async def test_http_api():
    """测试当前配置的HTTP API"""
    logger.info(_S_KEY, "=" * 60)
    logger.info(_S_KEY, '🧪 测试 HTTP REST 接口方式')
    logger.info(_S_KEY, '=' * 60)

    config = KB_CONFIG.get("INTERNET_SEARCH", {})
    logger.info('\n配置信息:')
    logger.info('  全局启用: %s', config.get('enable'))
    logger.info('  API URL: %s', config.get('api_url'))
    logger.info('  API Token: %s... (已隐藏)', config.get('api_token')[:10])
    logger.info('  返回条数: %s', config.get('num_results'))

    client = get_aliyun_search_client()
    if client is None:
        logger.info(_S_KEY, '\n❌ 客户端创建失败，请检查配置')
        return False

    logger.info('\n✅ 客户端创建成功，类型: %s', client.__class__.__name__)

    query = "杭州美食"
    logger.info("\n🔍 开始搜索: query=_S_KEY", query)

    try:
        docs = await client.search(query)
    except Exception as e:
        logger.info('\n❌ 搜索抛出异常: %s', str(e))
        import traceback
        traceback.print_exc()
        return False

    if docs is None:
        logger.info(_S_KEY, '\n❌ 搜索失败，返回None')
        return False

    if len(docs) == 0:
        logger.info(_S_KEY, '\n⚠️ 搜索成功但返回空结果')
        return True

    logger.info('\n✅ 搜索成功！返回 %s 条结果:\n', len(docs))

    for i, doc in enumerate(docs):
        logger.info('%s. %s', i + 1, doc.metadata.get('title', '无标题'))
        logger.info('   链接: %s', doc.metadata.get('source', _NA_KEY))
        logger.info('   网站: %s', doc.metadata.get('hostname', _NA_KEY))
        logger.info('   分数: %s', doc.metadata.get('rerank_score', _NA_KEY))
        logger.info(_S_KEY, f'   内容: {doc.page_content[:100]}...' if len(doc.page_content) > 100 else f'   内容: {doc.page_content}')
        logger.info("")

    logger.info(_S_KEY, '=' * 60)
    logger.info(_S_KEY, '✅ HTTP REST API 测试完成，接口可用！')
    logger.info(_S_KEY, '=' * 60)
    return True


async def test_official_sdk():
    """测试官方SDK方式（如果配置了AK/SK）"""
    logger.info(_S_KEY, '\n')
    logger.info(_S_KEY, '=' * 60)
    logger.info(_S_KEY, '🧪 测试 官方SDK 方式')
    logger.info(_S_KEY, '=' * 60)

    config = KB_CONFIG.get("INTERNET_SEARCH", {})
    if config.get("mode") != "official":
        logger.info("\n当前模式是 _S_KEY，跳过官方SDK测试", config.get('mode'))
        return None

    access_key_id = config.get("access_key_id", "")
    access_key_secret = config.get("access_key_secret", "")
    if not access_key_id or not access_key_secret:
        logger.info(_S_KEY, '\n⚠️ 未配置AK/SK，跳过官方SDK测试')
        return None

    logger.info('\n配置信息:')
    logger.info('  AK: %s... (已隐藏)', access_key_id[:8])
    logger.info('  Endpoint: %s', config.get('endpoint'))
    logger.info('  引擎类型: %s', config.get('engine_type'))

    # 使用官方SDK异步调用测试（参考你提供的官网例子）
    try:
        from Tea.exceptions import TeaException
        from alibabacloud_iqs20241111 import models
        from alibabacloud_iqs20241111.client import Client
        from alibabacloud_tea_openapi import models as open_api_models

        config_tea = open_api_models.Config(
            access_key_id=access_key_id,
            access_key_secret=access_key_secret
        )
        config_tea.endpoint = 'iqs.cn-zhangjiakou.aliyuncs.com'
        client = Client(config_tea)

        query = '杭州美食'
        logger.info("\n🔍 开始搜索: query=_S_KEY", query)

        search_request = models.UnifiedSearchRequest(
            body=models.UnifiedSearchInput(
                query=query,
                time_range='NoLimit',
                engine_type='LiteAdvanced',
                contents=models.RequestContents(
                    summary=False,
                    main_text=True,
                )
            )
        )

        response = await client.unified_search_async(search_request)
        logger.info('✅ api success, request_id:%s, size :%s, server_cost:%sms', response.body.request_id, len(response.body.page_items), response.body.search_information.search_time)

        if len(response.body.scene_items) > 0:
            logger.info('scene_items: %s', response.body.scene_items[0])

        for index, item in enumerate(response.body.page_items):
            logger.info('%s. %s', index, '-' * 20)
            logger.info('title:%s', item.title)
            logger.info('snippet:%s', item.snippet)
            logger.info('summary:%s', item.summary)
            logger.info('published_time:%s', item.published_time)
            logger.info('link:%s', item.link)
            logger.info('rerank_score:%s', item.rerank_score)

        logger.info(_S_KEY, '\n' + '=' * 60)
        logger.info(_S_KEY, '✅ 官方SDK测试完成，接口可用！')
        logger.info(_S_KEY, '=' * 60)
        return True

    except ImportError as e:
        logger.info('\n❌ 未安装官方SDK，请执行: pip install alibabacloud-iqs20241111 alibabacloud-tea-openapi')
        return False
    except TeaException as e:
        code = e.code
        request_id = e.data.get("requestId") if e.data else None
        message = e.message
        logger.info('\n❌ API异常: requestId=%s, code=%s, message=%s', request_id, code, message)
        return False
    except Exception as e:
        logger.info('\n❌ 异常: %s', str(e))
        import traceback
        traceback.print_exc()
        return False


async def main():
    logger.info(_S_KEY, '\n🚀 开始测试阿里云联网搜索API可用性\n')

    # 先测试HTTP方式（当前配置）
    http_ok = await test_http_api()

    # 再测试官方SDK方式（如果配置了）
    sdk_ok = await test_official_sdk()

    logger.info(_S_KEY, '\n📊 测试汇总:')
    logger.info('  HTTP REST API: %s', '✅ 通过' if http_ok else '❌ 失败')
    if sdk_ok is not None:
        logger.info('  官方SDK: %s', '✅ 通过' if sdk_ok else '❌ 失败')
    else:
        logger.info('  官方SDK: ⚠️  跳过（未配置）')

    logger.info("")
    if http_ok:
        logger.info(_S_KEY, '🎉 API测试通过，可以正常使用！')
        return 0
    else:
        logger.info(_S_KEY, '💥 API测试失败，请检查配置和网络！')
        return 1


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
