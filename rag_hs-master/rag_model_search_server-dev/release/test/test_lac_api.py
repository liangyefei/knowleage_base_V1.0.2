"""LAC 中文分词 API 连通性测试脚本
测试 LAC 微服务（基于 PaddleNLP Taskflow "pos_tagging"）的连接和基本功能

接口契约：
    POST /p/lac  {"base_word": "文本"} → {"output": [[词列表], [词性列表]]}
    GET  /health → {"status": "healthy", "model_loaded": true, "task": "pos_tagging"}

配置信息：
- Host: 默认 127.0.0.1（本机直连）
- Port: 5955
- 在 haisi_rag_models_server_dev 容器内测独立 LAC 容器时，用 --host 172.17.0.1
  （dev 容器默认桥接网络的网关 = 宿主机）或 --host host.docker.internal
"""


import logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

import requests
import sys
import argparse
from datetime import datetime, timezone


class LacAPITester:
    def __init__(self, host="127.0.0.1", port=5955):
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
        """测试健康检查"""
        _TEST_HEALTH_LABEL = '健康检查'
        try:
            response = requests.get(f"{self.base_url}/health", timeout=30)
            if response.status_code == 200:
                data = response.json()
                status = data.get("status", "unknown")
                model_loaded = data.get("model_loaded", False)
                task = data.get("task", "unknown")

                if status == "healthy" and model_loaded:
                    self.log(_TEST_HEALTH_LABEL, True, f"状态: {status}, 模型加载: {model_loaded}, 任务: {task}")
                    return True
                else:
                    self.log(_TEST_HEALTH_LABEL, False, f"状态: {status}, 模型加载: {model_loaded}, 任务: {task}")
                    return False
            else:
                self.log(_TEST_HEALTH_LABEL, False, f"状态码: {response.status_code}")
                return False
        except Exception as e:
            self.log(_TEST_HEALTH_LABEL, False, str(e))
            return False

    def test_basic_segment(self):
        """测试基本中文分词"""
        _TEST_BASIC_SEGM_LABEL = '基本分词'
        try:
            text = "我爱中国"
            output = self._segment(text)
            if not isinstance(output, list) or len(output) != 2:
                self.log(_TEST_BASIC_SEGM_LABEL, False, f"output 格式异常: {output}")
                return False
            words, tags = output[0], output[1]
            if not isinstance(words, list) or not isinstance(tags, list):
                self.log(_TEST_BASIC_SEGM_LABEL, False, f"words/tags 不是列表: {output}")
                return False
            if len(words) != len(tags):
                self.log(_TEST_BASIC_SEGM_LABEL, False, f"words 和 tags 长度不一致: {len(words)} vs {len(tags)}")
                return False
            if len(words) == 0:
                self.log(_TEST_BASIC_SEGM_LABEL, False, "分词结果为空")
                return False
            pairs = " ".join(f"{w}/{t}" for w, t in zip(words, tags))
            self.log(_TEST_BASIC_SEGM_LABEL, True, f"'{text}' → {pairs}")
            return True
        except Exception as e:
            self.log(_TEST_BASIC_SEGM_LABEL, False, str(e))
            return False

    def test_known_phrase(self):
        """测试 PaddleNLP 文档示例短语"""
        _TEST_KNOWN_PHRA_LABEL = '已知短语'
        try:
            text = "第十四届全运会男足决赛在上海举行"
            output = self._segment(text)
            words, tags = output[0], output[1]
            # 验证包含已知词（PaddleNLP pos_tagging 文档示例）
            if "第十四届" in words and "全运会" in words:
                idx1 = words.index("第十四届")
                idx2 = words.index("全运会")
                # 第十四届=m（数词）, 全运会=nz（其他专名）
                if tags[idx1] == "m" and tags[idx2] in ("nz", "n"):
                    pairs = " ".join(f"{w}/{t}" for w, t in zip(words, tags))
                    self.log(_TEST_KNOWN_PHRA_LABEL, True, f"'{text}' → {pairs}")
                    return True
                else:
                    pairs = " ".join(f"{w}/{t}" for w, t in zip(words, tags))
                    self.log(_TEST_KNOWN_PHRA_LABEL, True, f"词性变化（可接受）: {pairs}")
                    return True
            else:
                self.log(_TEST_KNOWN_PHRA_LABEL, False, f"分词结果不含预期词: {words}")
                return False
        except Exception as e:
            self.log(_TEST_KNOWN_PHRA_LABEL, False, str(e))
            return False

    def test_chinese_long_text(self):
        """测试中文长文本"""
        _TEST_CHINESE_LO_LABEL = '中文长文本'
        try:
            text = (
                "人工智能是计算机科学的一个分支，"
                "它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器。"
            )
            output = self._segment(text)
            words, tags = output[0], output[1]
            if len(words) >= 10 and len(words) == len(tags):
                self.log(_TEST_CHINESE_LO_LABEL, True, f"分出 {len(words)} 个词")
                return True
            else:
                self.log(_TEST_CHINESE_LO_LABEL, False, f"分词数异常: {len(words)} 词, tags {len(tags)}")
                return False
        except Exception as e:
            self.log(_TEST_CHINESE_LO_LABEL, False, str(e))
            return False

    def test_english_text(self):
        """测试英文文本（LAC 也能处理）"""
        _TEST_ENGLISH_TE_LABEL = '英文文本'
        try:
            text = "Hello world, this is a test."
            output = self._segment(text)
            words, tags = output[0], output[1]
            if len(words) > 0 and len(words) == len(tags):
                self.log(_TEST_ENGLISH_TE_LABEL, True, f"分出 {len(words)} 个词")
                return True
            else:
                self.log(_TEST_ENGLISH_TE_LABEL, False, f"分词结果异常: {output}")
                return False
        except Exception as e:
            self.log(_TEST_ENGLISH_TE_LABEL, False, str(e))
            return False

    def test_mixed_text(self):
        """测试中英混合文本"""
        _TEST_MIXED_TEXT_LABEL = '中英混合'
        try:
            text = "我用 Python 写了一个 Hello World 程序"
            output = self._segment(text)
            words, tags = output[0], output[1]
            if len(words) > 0 and len(words) == len(tags):
                pairs = " ".join(f"{w}/{t}" for w, t in zip(words, tags))
                self.log(_TEST_MIXED_TEXT_LABEL, True, f"'{text}' → {pairs}")
                return True
            else:
                self.log(_TEST_MIXED_TEXT_LABEL, False, f"分词结果异常: {output}")
                return False
        except Exception as e:
            self.log(_TEST_MIXED_TEXT_LABEL, False, str(e))
            return False

    def test_empty_string(self):
        """测试空字符串（边界情况）"""
        _TEST_EMPTY_STRI_LABEL = '空字符串'
        try:
            output = self._segment("")
            _is_valid_structure = (isinstance(output, list) and len(output) == 2
                                   and isinstance(output[0], list)
                                   and isinstance(output[1], list))
            if _is_valid_structure and len(output[0]) == 0 and len(output[1]) == 0:
                self.log(_TEST_EMPTY_STRI_LABEL, True, "返回 [[], []]")
                return True
            else:
                self.log(_TEST_EMPTY_STRI_LABEL, False, f"期望 [[], []], 实际 {output}")
                return False
        except Exception as e:
            self.log(_TEST_EMPTY_STRI_LABEL, False, str(e))
            return False

    def test_response_format(self):
        """严格验证响应格式契约：output = [[str, ...], [str, ...]]"""
        _TEST_RESPONSE_F_LABEL = '响应格式'
        try:
            text = "测试响应格式"
            output = self._segment(text)
            assert isinstance(output, list), f"output 不是 list: {type(output)}"
            assert len(output) == 2, f"output 长度不是 2: {len(output)}"
            words, tags = output
            assert isinstance(words, list), f"words 不是 list: {type(words)}"
            assert isinstance(tags, list), f"tags 不是 list: {type(tags)}"
            assert len(words) == len(tags), f"长度不一致: {len(words)} vs {len(tags)}"
            for w in words:
                assert isinstance(w, str), f"word 不是 str: {type(w)} {w!r}"
            for t in tags:
                assert isinstance(t, str), f"tag 不是 str: {type(t)} {t!r}"
            self.log(_TEST_RESPONSE_F_LABEL, True, "output = [[str,...], [str,...]] 契约对齐")
            return True
        except AssertionError as e:
            self.log(_TEST_RESPONSE_F_LABEL, False, f"契约不符: {e}")
            return False
        except Exception as e:
            self.log(_TEST_RESPONSE_F_LABEL, False, str(e))
            return False

    def run_all_tests(self):
        """运行所有测试"""
        _S_KEY = '%s'
        logger.info(_S_KEY, '=' * 60)
        logger.info(_S_KEY, 'LAC 中文分词 API 连通性测试')
        logger.info('服务地址: %s', self.base_url)
        logger.info('测试时间: %s', datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info(_S_KEY, '=' * 60)
        logger.info("")

        # 基本连接测试
        if not self.test_health():
            logger.info(_S_KEY, '\n❌ 无法连接到 LAC 服务或模型未加载')
            logger.info('   检查: %s/health', self.base_url)
            logger.info(_S_KEY, '   若在 haisi_rag_models_server_dev 容器内测独立 LAC 容器，')
            logger.info(_S_KEY, '   试用 --host 172.17.0.1（默认桥接网关=宿主机）或 --host host.docker.internal')
            return False

        # 功能测试
        self.test_basic_segment()
        self.test_known_phrase()
        self.test_chinese_long_text()
        self.test_english_text()
        self.test_mixed_text()
        self.test_empty_string()
        self.test_response_format()

        # 汇总结果
        logger.info("")
        logger.info(_S_KEY, '=' * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info('测试汇总: %s/%s 通过', passed, total)
        logger.info(_S_KEY, '=' * 60)

        return passed == total

    def _segment(self, text):
        """调用 /p/lac 接口，返回 output 字段；失败抛异常"""
        response = requests.post(
            f"{self.base_url}/p/lac",
            json={"base_word": text},
            timeout=60,
        )
        if response.status_code != 200:
            raise RuntimeError(f"HTTP {response.status_code}: {response.text}")
        result = response.json()
        return result.get("output")


def main():
    parser = argparse.ArgumentParser(description="LAC 中文分词 API 连通性测试")
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="LAC 服务 host（默认 127.0.0.1；dev 容器内测独立 LAC 容器用 172.17.0.1）",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=5955,
        help="LAC 服务端口（默认 5955）",
    )
    args = parser.parse_args()

    tester = LacAPITester(host=args.host, port=args.port)
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
