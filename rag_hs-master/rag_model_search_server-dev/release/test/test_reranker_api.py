"""
Coreb-Code-Reranker API 连通性测试脚本
测试 Coreb-Code-Reranker 模型服务的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 16603
"""


import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import requests
import json
import sys
from datetime import datetime, timezone

_UNKNOWN_KEY = 'unknown'


class RerankerAPITester:
    def __init__(self, host="192.168.80.250", port=16603):
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
        logger.info('%s %s: %s', status, test_name, message)

    def test_health(self):
        """测试服务健康状态接口"""
        _HEALTH_CHECK_LABEL = '健康检查'
        try:
            response = requests.get(f"{self.base_url}/health", timeout=30)
            if response.status_code == 200:
                data = response.json()
                status = data.get("status", _UNKNOWN_KEY)
                model_loaded = data.get("model_loaded", False)
                device = data.get("device", _UNKNOWN_KEY)

                if status == "healthy" and model_loaded:
                    gpu_info = data.get("gpu_info", {})
                    gpu_name = gpu_info.get("name", _UNKNOWN_KEY) if gpu_info else "CPU"
                    self.log(_HEALTH_CHECK_LABEL, True, f"状态: {status}, 设备: {device}, GPU: {gpu_name}")
                    return True
                else:
                    self.log(_HEALTH_CHECK_LABEL, False, f"状态: {status}, 模型加载: {model_loaded}")
                    return False
            else:
                self.log(_HEALTH_CHECK_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_HEALTH_CHECK_LABEL, False, str(e))
            return False

    def test_text_to_code(self):
        """测试 TEXT_TO_CODE 任务"""
        _TEXT_TO_CODE_KEY = 'TEXT_TO_CODE'
        try:
            data = {
                "query": "Write a function to add two numbers",
                "document": "def add(a, b): return a + b",
                "task_type": _TEXT_TO_CODE_KEY
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                score = result.get("score", 0)

                # 正相关表示匹配
                if score > 0:
                    self.log(_TEXT_TO_CODE_KEY, True, f"相关性分数: {score:.4f} (正相关)")
                    return True
                else:
                    self.log(_TEXT_TO_CODE_KEY, True, f"相关性分数: {score:.4f} (负相关)")
                    return True
            else:
                self.log(_TEXT_TO_CODE_KEY, False, f"状态码: {response.status_code}", response.text)
                return False
        except Exception as e:
            self.log(_TEXT_TO_CODE_KEY, False, str(e))
            return False

    def test_code_to_code(self):
        """测试 CODE_TO_CODE 任务"""
        _CODE_TO_CODE_KEY = 'CODE_TO_CODE'
        try:
            data = {
                "query": "def add(a, b): return a + b",
                "document": "def sum(x, y): return x + y",
                "task_type": _CODE_TO_CODE_KEY
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                score = result.get("score", 0)

                self.log(_CODE_TO_CODE_KEY, True, f"相关性分数: {score:.4f}")
                return True
            else:
                self.log(_CODE_TO_CODE_KEY, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_CODE_TO_CODE_KEY, False, str(e))
            return False

    def test_code_to_text(self):
        """测试 CODE_TO_TEXT 任务"""
        _CODE_TO_TEXT_KEY = 'CODE_TO_TEXT'
        try:
            data = {
                "query": "def factorial(n): return 1 if n <= 1 else n * factorial(n-1)",
                "document": "Calculate the factorial of a number",
                "task_type": _CODE_TO_TEXT_KEY
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                score = result.get("score", 0)

                self.log(_CODE_TO_TEXT_KEY, True, f"相关性分数: {score:.4f}")
                return True
            else:
                self.log(_CODE_TO_TEXT_KEY, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_CODE_TO_TEXT_KEY, False, str(e))
            return False

    def test_invalid_task_type(self):
        """测试无效任务类型"""
        _TEST_INVALID_TA_LABEL = '无效任务类型'
        try:
            data = {
                "query": "test query",
                "document": "test document",
                "task_type": "INVALID_TYPE"
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            # 422 Unprocessable Entity 是 FastAPI 验证错误的标准响应
            if response.status_code == 422:
                self.log(_TEST_INVALID_TA_LABEL, True, "正确返回 422 错误")
                return True
            else:
                self.log(_TEST_INVALID_TA_LABEL, False, f"期望 422, 实际 {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_INVALID_TA_LABEL, False, str(e))
            return False

    def test_chinese_query(self):
        """测试中文查询"""
        _TEST_CHINESE_QU_LABEL = '中文查询'
        try:
            data = {
                "query": "写一个函数计算两个数的和",
                "document": "def add(a, b): return a + b",
                "task_type": "TEXT_TO_CODE"
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                score = result.get("score", 0)

                self.log(_TEST_CHINESE_QU_LABEL, True, f"相关性分数: {score:.4f}")
                return True
            else:
                self.log(_TEST_CHINESE_QU_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_CHINESE_QU_LABEL, False, str(e))
            return False

    def test_long_code(self):
        """测试长代码片段"""
        _TEST_LONG_CODE_LABEL = '长代码片段'
        try:
            long_code = '''
def quicksort(arr):
    """快速排序算法实现"""
    if len(arr) <= 1:
        return arr

    pivot = arr[len(arr) // 2]
    left = [x for x in arr if x < pivot]
    middle = [x for x in arr if x == pivot]
    right = [x for x in arr if x > pivot]

    return quicksort(left) + middle + quicksort(right)

# 使用示例
if __name__ == "__main__":
    test_array = [3, 6, 8, 10, 1, 2, 1]
    print(quicksort(test_array))
'''

            data = {
                "query": "Implement quicksort algorithm",
                "document": long_code,
                "task_type": "TEXT_TO_CODE"
            }

            response = requests.post(f"{self.base_url}/score", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                score = result.get("score", 0)

                self.log(_TEST_LONG_CODE_LABEL, True, f"相关性分数: {score:.4f}")
                return True
            else:
                self.log(_TEST_LONG_CODE_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_LONG_CODE_LABEL, False, str(e))
            return False

    def run_all_tests(self):
        """运行所有测试"""
        _S_KEY = '%s'
        logger.info(_S_KEY, '=' * 60)
        logger.info(_S_KEY, 'Coreb-Code-Reranker API 连通性测试')
        logger.info('服务地址: %s', self.base_url)
        logger.info('测试时间: %s', datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info(_S_KEY, '=' * 60)
        logger.info("")

        # 基本连接测试
        if not self.test_health():
            logger.info(_S_KEY, '\n❌ 无法连接到 Reranker 服务，请检查服务是否启动')
            return False

        # 功能测试
        self.test_text_to_code()
        self.test_code_to_code()
        self.test_code_to_text()
        self.test_invalid_task_type()
        self.test_chinese_query()
        self.test_long_code()

        # 汇总结果
        logger.info("")
        logger.info(_S_KEY, '=' * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info('测试汇总: %s/%s 通过', passed, total)
        logger.info(_S_KEY, '=' * 60)

        return passed == total


def main():
    tester = RerankerAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()