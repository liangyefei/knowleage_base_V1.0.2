"""
Milvus API 连通性测试脚本
测试 Milvus 向量数据库的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 19530
- 用户名: root
- 密码: Milvus
"""


import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import sys
from datetime import datetime, timezone

try:
    from pymilvus import connections, utility, Collection, FieldSchema, CollectionSchema, DataType
    MILVUS_AVAILABLE = True
except ImportError:
    MILVUS_AVAILABLE = False
    logger.info("%s", '警告: pymilvus 未安装，请执行: pip install pymilvus')


class MilvusAPITester:
    def __init__(self, host="192.168.80.250", port="19530", user="root", password="Milvus"):
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.alias = "default"
        self.results = []
        self.connected = False

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
        if not MILVUS_AVAILABLE:
            self.log(_TEST_CONNECTION_LABEL, False, "pymilvus 未安装")
            return False

        try:
            connections.connect(
                alias=self.alias,
                host=self.host,
                port=self.port,
                user=self.user,
                password=self.password
            )
            self.connected = True
            self.log(_TEST_CONNECTION_LABEL, True, f"成功连接到 {self.host}:{self.port}")
            return True
        except Exception as e:
            self.log(_TEST_CONNECTION_LABEL, False, str(e))
            return False

    def test_server_version(self):
        """测试服务器版本"""
        _TEST_SERVER_VER_LABEL = '服务器版本'
        if not self.connected:
            self.log(_TEST_SERVER_VER_LABEL, False, "未连接")
            return False

        try:
            version = utility.get_server_version()
            self.log(_TEST_SERVER_VER_LABEL, True, f"Milvus 版本: {version}")
            return True
        except Exception as e:
            self.log(_TEST_SERVER_VER_LABEL, False, str(e))
            return False

    def test_list_collections(self):
        """测试列出集合"""
        _TEST_LIST_COLLE_LABEL = '列出集合'
        if not self.connected:
            self.log(_TEST_LIST_COLLE_LABEL, False, "未连接")
            return False

        try:
            collections = utility.list_collections()
            self.log(_TEST_LIST_COLLE_LABEL, True, f"共有 {len(collections)} 个集合")
            return True
        except Exception as e:
            self.log(_TEST_LIST_COLLE_LABEL, False, str(e))
            return False

    def test_create_collection(self):
        """测试创建集合"""
        _TEST_CREATE_COL_LABEL = '创建集合'
        if not self.connected:
            self.log(_TEST_CREATE_COL_LABEL, False, "未连接")
            return False

        collection_name = "test_collection_haisi"

        try:
            # 删除可能存在的旧集合
            if utility.has_collection(collection_name):
                utility.drop_collection(collection_name)

            # 定义字段
            fields = [
                FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
                FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=128)
            ]

            # 创建集合
            schema = CollectionSchema(fields, description="测试集合")
            collection = Collection(name=collection_name, schema=schema)

            self.log(_TEST_CREATE_COL_LABEL, True, f"集合 '{collection_name}' 创建成功")
            return True
        except Exception as e:
            self.log(_TEST_CREATE_COL_LABEL, False, str(e))
            return False

    def test_insert_data(self):
        """测试插入数据"""
        _TEST_INSERT_DAT_LABEL = '插入数据'
        if not self.connected:
            self.log(_TEST_INSERT_DAT_LABEL, False, "未连接")
            return False

        collection_name = "test_collection_haisi"

        try:
            collection = Collection(name=collection_name)

            # 生成测试数据
            import random
            data = [[random.random() for _ in range(128)] for _ in range(10)]

            # 插入数据
            result = collection.insert([data])
            collection.flush()

            self.log(_TEST_INSERT_DAT_LABEL, True, f"成功插入 {len(data)} 条向量")
            return True
        except Exception as e:
            self.log(_TEST_INSERT_DAT_LABEL, False, str(e))
            return False

    def test_create_index(self):
        """测试创建索引"""
        _TEST_CREATE_IND_LABEL = '创建索引'
        if not self.connected:
            self.log(_TEST_CREATE_IND_LABEL, False, "未连接")
            return False

        collection_name = "test_collection_haisi"

        try:
            collection = Collection(name=collection_name)

            # 创建索引
            index_params = {
                "metric_type": "L2",
                "index_type": "IVF_FLAT",
                "params": {"nlist": 128}
            }
            collection.create_index(field_name="embedding", index_params=index_params)

            self.log(_TEST_CREATE_IND_LABEL, True, "索引创建成功")
            return True
        except Exception as e:
            self.log(_TEST_CREATE_IND_LABEL, False, str(e))
            return False

    def test_search(self):
        """测试搜索"""
        _TEST_SEARCH_LABEL = '向量搜索'
        if not self.connected:
            self.log(_TEST_SEARCH_LABEL, False, "未连接")
            return False

        collection_name = "test_collection_haisi"

        try:
            collection = Collection(name=collection_name)
            collection.load()

            # 生成查询向量
            import random
            search_vector = [[random.random() for _ in range(128)]]

            # 搜索
            search_params = {"metric_type": "L2", "params": {"nprobe": 10}}
            results = collection.search(
                data=search_vector,
                anns_field="embedding",
                param=search_params,
                limit=5
            )

            self.log(_TEST_SEARCH_LABEL, True, f"返回 {len(results[0])} 条结果")
            return True
        except Exception as e:
            self.log(_TEST_SEARCH_LABEL, False, str(e))
            return False

    def test_delete_collection(self):
        """测试删除集合"""
        _TEST_DELETE_COL_LABEL = '删除集合'
        if not self.connected:
            self.log(_TEST_DELETE_COL_LABEL, False, "未连接")
            return False

        collection_name = "test_collection_haisi"

        try:
            utility.drop_collection(collection_name)
            self.log(_TEST_DELETE_COL_LABEL, True, f"集合 '{collection_name}' 已删除")
            return True
        except Exception as e:
            self.log(_TEST_DELETE_COL_LABEL, False, str(e))
            return False

    def cleanup(self):
        """清理连接"""
        if self.connected:
            try:
                connections.disconnect(self.alias)
            except Exception as e:
                self.log("清理连接", False, str(e))

    def run_all_tests(self):
        """运行所有测试"""
        _S_KEY = '%s'
        logger.info(_S_KEY, '=' * 60)
        logger.info(_S_KEY, 'Milvus API 连通性测试')
        logger.info('服务地址: %s:%s', self.host, self.port)
        logger.info('测试时间: %s', datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info(_S_KEY, '=' * 60)
        logger.info("")

        if not MILVUS_AVAILABLE:
            logger.info(_S_KEY, '❌ 请先安装 pymilvus: pip install pymilvus')
            return False

        # 基本连接测试
        if not self.test_connection():
            logger.info(_S_KEY, '\n❌ 无法连接到 Milvus，请检查服务是否启动')
            self.cleanup()
            return False

        # 功能测试
        self.test_server_version()
        self.test_list_collections()
        self.test_create_collection()
        self.test_insert_data()
        self.test_create_index()
        self.test_search()
        self.test_delete_collection()

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
    tester = MilvusAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()