"""
Qwen3-Embedding API 连通性测试脚本
测试 Qwen3-Embedding 模型服务的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 16604
"""


import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import requests
import json
import sys
from datetime import datetime, timezone

_UNKNOWN_KEY = 'unknown'


class EmbeddingAPITester:
    def __init__(self, host="192.168.80.250", port=16604, expected_dim=2560):
        self.host = host
        self.port = port
        self.base_url = f"http://{host}:{port}"
        self.expected_dim = expected_dim  # Qwen3-Embedding-4B 默认维度
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

    def test_single_embed(self):
        """测试单条文本编码"""
        _TEST_SINGLE_EMB_LABEL = '单条文本编码'
        try:
            data = {
                "texts": "What is the capital of China?"
            }

            response = requests.post(f"{self.base_url}/embed", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                embeddings = result.get("embeddings", [])
                text_count = result.get("text_count", 0)
                embedding_dim = result.get("embedding_dim", 0)

                if len(embeddings) == 1 and embedding_dim > 0:
                    # 验证维度是否匹配
                    if embedding_dim == self.expected_dim:
                        self.log(
                            _TEST_SINGLE_EMB_LABEL,
                            True,
                            f"向量维度: {embedding_dim} (匹配), 文本数: {text_count}"
                        )
                    else:
                        self.log(
                            _TEST_SINGLE_EMB_LABEL,
                            False,
                            f"维度不匹配: 期望 {self.expected_dim}, 实际 {embedding_dim}"
                        )
                        return False
                    return True
                else:
                    self.log(_TEST_SINGLE_EMB_LABEL, False, f"返回数据异常")
                    return False
            else:
                self.log(_TEST_SINGLE_EMB_LABEL, False, f"状态码: {response.status_code}", response.text)
                return False
        except Exception as e:
            self.log(_TEST_SINGLE_EMB_LABEL, False, str(e))
            return False

    def test_batch_embed(self):
        """测试批量文本编码"""
        _TEST_BATCH_EMBE_LABEL = '批量文本编码'
        try:
            data = {
                "texts": [
                    "What is the capital of China?",
                    "Explain gravity",
                    "How to write a function in Python?"
                ]
            }

            response = requests.post(f"{self.base_url}/embed", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                embeddings = result.get("embeddings", [])
                text_count = result.get("text_count", 0)

                if len(embeddings) == 3 and text_count == 3:
                    self.log(_TEST_BATCH_EMBE_LABEL, True, f"成功编码 {text_count} 条文本")
                    return True
                else:
                    self.log(_TEST_BATCH_EMBE_LABEL, False, f"期望 3 条, 实际 {len(embeddings)} 条")
                    return False
            else:
                self.log(_TEST_BATCH_EMBE_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_BATCH_EMBE_LABEL, False, str(e))
            return False

    def test_embed_with_prompt(self):
        """测试带 prompt 的编码"""
        _PROMPT_KEY = '带 Prompt 编码'
        try:
            data = {
                "texts": ["What is the capital of China?"],
                "prompt_name": "query"
            }

            response = requests.post(f"{self.base_url}/embed", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                self.log(_PROMPT_KEY, True, f"使用 prompt_name='query' 编码成功")
                return True
            else:
                self.log(_PROMPT_KEY, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_PROMPT_KEY, False, str(e))
            return False

    def test_similarity(self):
        """测试相似度计算"""
        _TEST_SIMILARITY_LABEL = '相似度计算'
        try:
            data = {
                "queries": ["What is the capital of China?", "Explain gravity"],
                "documents": [
                    "The capital of China is Beijing.",
                    "Gravity is a force that attracts two bodies towards each other."
                ]
            }

            response = requests.post(f"{self.base_url}/similarity", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                similarities = result.get("similarities", [])
                query_count = result.get("query_count", 0)
                doc_count = result.get("document_count", 0)

                if len(similarities) == 2 and len(similarities[0]) == 2:
                    self.log(
                        _TEST_SIMILARITY_LABEL,
                        True,
                        f"相似度矩阵: {query_count}x{doc_count}, 样本值: {similarities[0][0]:.4f}"
                    )
                    return True
                else:
                    self.log(_TEST_SIMILARITY_LABEL, False, f"返回数据异常")
                    return False
            else:
                self.log(_TEST_SIMILARITY_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_SIMILARITY_LABEL, False, str(e))
            return False

    def test_chinese_text(self):
        """测试中文文本编码"""
        _TEST_CHINESE_TE_LABEL = '中文文本编码'
        try:
            data = {
                "texts": ["中国的首都是北京", "请解释什么是重力"]
            }

            response = requests.post(f"{self.base_url}/embed", json=data, timeout=60)

            if response.status_code == 200:
                result = response.json()
                embeddings = result.get("embeddings", [])

                if len(embeddings) == 2:
                    self.log(_TEST_CHINESE_TE_LABEL, True, "成功编码中文文本")
                    return True
                else:
                    self.log(_TEST_CHINESE_TE_LABEL, False, "编码失败")
                    return False
            else:
                self.log(_TEST_CHINESE_TE_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_CHINESE_TE_LABEL, False, str(e))
            return False

    def run_all_tests(self):
        """运行所有测试"""
        _S_KEY = '%s'
        logger.info(_S_KEY, '=' * 60)
        logger.info(_S_KEY, 'Qwen3-Embedding API 连通性测试')
        logger.info('服务地址: %s', self.base_url)
        logger.info('期望维度: %s (Qwen3-Embedding-4B)', self.expected_dim)
        logger.info('测试时间: %s', datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info(_S_KEY, '=' * 60)
        logger.info("")

        # 基本连接测试
        if not self.test_health():
            logger.info(_S_KEY, '\n❌ 无法连接到 Embedding 服务，请检查服务是否启动')
            return False

        # 功能测试
        self.test_single_embed()
        self.test_batch_embed()
        self.test_embed_with_prompt()
        self.test_similarity()
        self.test_chinese_text()

        # 汇总结果
        logger.info("")
        logger.info(_S_KEY, '=' * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info('测试汇总: %s/%s 通过', passed, total)
        logger.info(_S_KEY, '=' * 60)

        return passed == total


def main():
    tester = EmbeddingAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()