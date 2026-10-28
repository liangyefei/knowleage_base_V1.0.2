"""
Redis API 连通性测试脚本
测试 Redis 缓存服务的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 16379
- 密码: farai123
"""


import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import sys
from datetime import datetime, timezone

try:
    import redis
    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False
    logger.info("%s", '警告: redis 未安装，请执行: pip install redis')


class RedisAPITester:
    def __init__(self, host="192.168.80.250", port=16379, password="farai123"):
        self.host = host
        self.port = port
        self.password = password
        self.results = []
        self.client = None

    def log(self, test_name, success, message=""):
        """记录测试结果"""
        result = {
            "test": test_name,
            "success": success,
            "message": message,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }
        self.results.append(result)
        status = "✅" if success else "❌"
        logger.info('%s %s: %s', status, test_name, message)

    def test_connection(self):
        """测试连接"""
        _TEST_CONNECTION_LABEL = '连接测试'
        if not REDIS_AVAILABLE:
            self.log(_TEST_CONNECTION_LABEL, False, "redis 模块未安装")
            return False

        try:
            self.client = redis.Redis(
                host=self.host,
                port=self.port,
                password=self.password,
                decode_responses=True,
                socket_connect_timeout=10
            )
            # 测试连接
            if self.client.ping():
                self.log(_TEST_CONNECTION_LABEL, True, f"成功连接到 {self.host}:{self.port}")
                return True
            else:
                self.log(_TEST_CONNECTION_LABEL, False, "PING 返回 False")
                return False
        except Exception as e:
            self.log(_TEST_CONNECTION_LABEL, False, str(e))
            return False

    def test_server_info(self):
        """测试服务器信息"""
        _TEST_SERVER_INF_LABEL = '服务器信息'
        if not self.client:
            self.log(_TEST_SERVER_INF_LABEL, False, "未连接")
            return False

        try:
            info = self.client.info("server")
            version = info.get("redis_version", "unknown")
            used_memory = info.get("used_memory_human", "unknown")
            self.log(_TEST_SERVER_INF_LABEL, True, f"Redis {version}, 内存使用: {used_memory}")
            return True
        except Exception as e:
            self.log(_TEST_SERVER_INF_LABEL, False, str(e))
            return False

    def test_set_get(self):
        """测试 SET/GET 操作"""
        _SETGET_KEY = 'SET/GET 操作'
        if not self.client:
            self.log(_SETGET_KEY, False, "未连接")
            return False

        try:
            test_key = "test_key_haisi"
            test_value = "test_value_haisi"

            # SET
            self.client.set(test_key, test_value)

            # GET
            result = self.client.get(test_key)

            if result == test_value:
                self.log(_SETGET_KEY, True, f"SET/GET 成功: {test_key}={result}")
                return True
            else:
                self.log(_SETGET_KEY, False, f"值不匹配: 期望 {test_value}, 实际 {result}")
                return False
        except Exception as e:
            self.log(_SETGET_KEY, False, str(e))
            return False

    def test_setex(self):
        """测试 SETEX 过期时间"""
        _SETEX_KEY = 'SETEX 操作'
        if not self.client:
            self.log(_SETEX_KEY, False, "未连接")
            return False

        try:
            test_key = "test_key_ex_haisi"

            # 设置 60 秒过期
            self.client.setex(test_key, 60, "test_value_ex")

            # 检查 TTL
            ttl = self.client.ttl(test_key)

            if ttl > 0:
                self.log(_SETEX_KEY, True, f"键设置过期成功, TTL: {ttl}秒")
                return True
            else:
                self.log(_SETEX_KEY, False, f"TTL 异常: {ttl}")
                return False
        except Exception as e:
            self.log(_SETEX_KEY, False, str(e))
            return False

    def test_delete(self):
        """测试 DEL 操作"""
        _DEL_KEY = 'DEL 操作'
        if not self.client:
            self.log(_DEL_KEY, False, "未连接")
            return False

        try:
            test_key = "test_key_haisi"

            # 删除
            result = self.client.delete(test_key)

            self.log(_DEL_KEY, True, f"删除 {result} 个键")
            return True
        except Exception as e:
            self.log(_DEL_KEY, False, str(e))
            return False

    def test_list_operations(self):
        """测试 List 操作"""
        _LIST_KEY = 'List 操作'
        if not self.client:
            self.log(_LIST_KEY, False, "未连接")
            return False

        try:
            test_key = "test_list_haisi"

            # 清空
            self.client.delete(test_key)

            # LPUSH
            self.client.lpush(test_key, "item1", "item2", "item3")

            # LRANGE
            items = self.client.lrange(test_key, 0, -1)

            self.log(_LIST_KEY, True, f"List 包含 {len(items)} 个元素: {items}")

            # 清理
            self.client.delete(test_key)
            return True
        except Exception as e:
            self.log(_LIST_KEY, False, str(e))
            return False

    def test_hash_operations(self):
        """测试 Hash 操作"""
        _HASH_KEY = 'Hash 操作'
        if not self.client:
            self.log(_HASH_KEY, False, "未连接")
            return False

        try:
            test_key = "test_hash_haisi"

            # 清空
            self.client.delete(test_key)

            # HSET
            self.client.hset(test_key, mapping={"field1": "value1", "field2": "value2"})

            # HGETALL
            data = self.client.hgetall(test_key)

            self.log(_HASH_KEY, True, f"Hash 包含 {len(data)} 个字段")

            # 清理
            self.client.delete(test_key)
            return True
        except Exception as e:
            self.log(_HASH_KEY, False, str(e))
            return False

    def test_key_count(self):
        """测试键数量"""
        _TEST_KEY_COUNT_LABEL = '键数量'
        if not self.client:
            self.log(_TEST_KEY_COUNT_LABEL, False, "未连接")
            return False

        try:
            count = self.client.dbsize()
            self.log(_TEST_KEY_COUNT_LABEL, True, f"数据库共有 {count} 个键")
            return True
        except Exception as e:
            self.log(_TEST_KEY_COUNT_LABEL, False, str(e))
            return False

    def cleanup(self):
        """清理连接"""
        if self.client:
            try:
                self.client.close()
            except Exception as e:
                self.log("清理连接", False, str(e))

    def run_all_tests(self):
        """运行所有测试"""
        _S_KEY = '%s'
        logger.info(_S_KEY, '=' * 60)
        logger.info(_S_KEY, 'Redis API 连通性测试')
        logger.info('服务地址: %s:%s', self.host, self.port)
        logger.info('测试时间: %s', datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info(_S_KEY, '=' * 60)
        logger.info("")

        if not REDIS_AVAILABLE:
            logger.info(_S_KEY, '❌ 请先安装 redis: pip install redis')
            return False

        # 基本连接测试
        if not self.test_connection():
            logger.info(_S_KEY, '\n❌ 无法连接到 Redis，请检查服务是否启动')
            self.cleanup()
            return False

        # 功能测试
        self.test_server_info()
        self.test_set_get()
        self.test_setex()
        self.test_delete()
        self.test_list_operations()
        self.test_hash_operations()
        self.test_key_count()

        # 清理
        self.cleanup()

        # 汇总结果
        logger.info("")
        logger.info(_S_KEY, '=' * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info('测试汇总: %s/%s 通过', passed, total)
        logger.info(_S_KEY, '=' * 60)

        return passed == total


def main():
    tester = RedisAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()