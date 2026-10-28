"""
Elasticsearch API 连通性测试脚本
测试 Elasticsearch 服务的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 19200
- 协议: HTTP
"""

import requests
import json
import sys
from datetime import datetime, timezone

from configs.logger_manager import logger


class ElasticsearchAPITester:
    def __init__(self, host="192.168.80.250", port=19200):
        self.host = host
        self.port = port
        self.base_url = f"http://{host}:{port}"
        self.results = []

    def log(self, test_name, success, message="", response=None):
        """记录测试结果"""
        result = {
            "test": test_name,
            "success": success,
            "message": message,
            "response": response,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }
        self.results.append(result)
        status = "✅" if success else "❌"
        logger.info("{} {}: {}", status, test_name, message)
        if response and not success:
            logger.info("   Response: {}", response)

    def test_connection(self):
        """测试基本连接"""
        _TEST_CONNECTION_LABEL = '基本连接'
        try:
            response = requests.get(f"{self.base_url}/", timeout=10)
            if response.status_code == 200:
                data = response.json()
                self.log(
                    _TEST_CONNECTION_LABEL,
                    True,
                    f"成功连接到 Elasticsearch {data.get('version', {}).get('number', 'unknown')}"
                )
                return True
            else:
                self.log(_TEST_CONNECTION_LABEL, False, f"状态码: {response.status_code}", response.text)
                return False
        except Exception as e:
            self.log(_TEST_CONNECTION_LABEL, False, str(e))
            return False

    def test_cluster_health(self):
        """测试集群健康状态"""
        _TEST_CLUSTER_HE_LABEL = '集群健康状态'
        try:
            response = requests.get(f"{self.base_url}/_cluster/health", timeout=10)
            if response.status_code == 200:
                data = response.json()
                status = data.get("status", "unknown")
                self.log(_TEST_CLUSTER_HE_LABEL, status in ["green", "yellow"], f"状态: {status}")
                return True
            else:
                self.log(_TEST_CLUSTER_HE_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_CLUSTER_HE_LABEL, False, str(e))
            return False

    def test_cluster_info(self):
        """测试集群信息"""
        _TEST_CLUSTER_IN_LABEL = '集群信息'
        try:
            response = requests.get(f"{self.base_url}/_cluster/state", timeout=10)
            if response.status_code == 200:
                data = response.json()
                cluster_name = data.get("cluster_name", "unknown")
                self.log(_TEST_CLUSTER_IN_LABEL, True, f"集群名称: {cluster_name}")
                return True
            else:
                self.log(_TEST_CLUSTER_IN_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_CLUSTER_IN_LABEL, False, str(e))
            return False

    def test_indices(self):
        """测试索引列表"""
        _TEST_INDICES_LABEL = '索引列表'
        try:
            response = requests.get(f"{self.base_url}/_cat/indices?format=json", timeout=10)
            if response.status_code == 200:
                data = response.json()
                self.log(_TEST_INDICES_LABEL, True, f"共有 {len(data)} 个索引")
                return True
            else:
                self.log(_TEST_INDICES_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_INDICES_LABEL, False, str(e))
            return False

    def test_create_index(self):
        """测试创建索引"""
        _TEST_CREATE_IND_LABEL = '创建索引'
        index_name = "test_index_haisi"
        try:
            # 删除可能存在的旧索引
            requests.delete(f"{self.base_url}/{index_name}", timeout=10)

            # 创建新索引
            response = requests.put(
                f"{self.base_url}/{index_name}",
                json={
                    "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0
                    }
                },
                timeout=10
            )
            if response.status_code in [200, 201]:
                self.log(_TEST_CREATE_IND_LABEL, True, f"索引 '{index_name}' 创建成功")
                return True
            else:
                self.log(_TEST_CREATE_IND_LABEL, False, f"状态码: {response.status_code}", response.text)
                return False
        except Exception as e:
            self.log(_TEST_CREATE_IND_LABEL, False, str(e))
            return False

    def test_index_document(self):
        """测试索引文档"""
        _TEST_INDEX_DOCU_LABEL = '索引文档'
        index_name = "test_index_haisi"
        try:
            response = requests.post(
                f"{self.base_url}/{index_name}/_doc",
                json={
                    "title": "测试文档",
                    "content": "这是一个测试文档",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                },
                timeout=10
            )
            if response.status_code in [200, 201]:
                data = response.json()
                self.log(_TEST_INDEX_DOCU_LABEL, True, f"文档ID: {data.get('_id', 'unknown')}")
                return True
            else:
                self.log(_TEST_INDEX_DOCU_LABEL, False, f"状态码: {response.status_code}", response.text)
                return False
        except Exception as e:
            self.log(_TEST_INDEX_DOCU_LABEL, False, str(e))
            return False

    def test_search(self):
        """测试搜索功能"""
        _TEST_SEARCH_LABEL = '搜索功能'
        index_name = "test_index_haisi"
        try:
            response = requests.post(
                f"{self.base_url}/{index_name}/_search",
                json={
                    "query": {
                        "match_all": {}
                    }
                },
                timeout=10
            )
            if response.status_code == 200:
                data = response.json()
                hits = data.get("hits", {}).get("total", {}).get("value", 0)
                self.log(_TEST_SEARCH_LABEL, True, f"找到 {hits} 条文档")
                return True
            else:
                self.log(_TEST_SEARCH_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_SEARCH_LABEL, False, str(e))
            return False

    def test_delete_index(self):
        """测试删除索引"""
        _TEST_DELETE_IND_LABEL = '删除索引'
        index_name = "test_index_haisi"
        try:
            response = requests.delete(f"{self.base_url}/{index_name}", timeout=10)
            if response.status_code == 200:
                self.log(_TEST_DELETE_IND_LABEL, True, f"索引 '{index_name}' 已删除")
                return True
            else:
                self.log(_TEST_DELETE_IND_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_DELETE_IND_LABEL, False, str(e))
            return False

    def run_all_tests(self):
        """运行所有测试"""
        logger.info("=" * 60)
        logger.info("Elasticsearch API 连通性测试")
        logger.info("服务地址: {}", self.base_url)
        logger.info("测试时间: {}", datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info("=" * 60)
        logger.info("")

        # 基本连接测试
        if not self.test_connection():
            logger.info("\n❌ 无法连接到 Elasticsearch，请检查服务是否启动")
            return False

        # 功能测试
        self.test_cluster_health()
        self.test_cluster_info()
        self.test_indices()
        self.test_create_index()
        self.test_index_document()
        self.test_search()
        self.test_delete_index()

        # 汇总结果
        logger.info("")
        logger.info("=" * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info("测试汇总: {}/{} 通过", passed, total)
        logger.info("=" * 60)

        return passed == total


def main():
    tester = ElasticsearchAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()