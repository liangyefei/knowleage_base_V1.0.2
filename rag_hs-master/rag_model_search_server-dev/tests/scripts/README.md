# 手动调试脚本

本目录文件为手动调试/连接性脚本，**非 pytest 用例**，不被 pytest 收集（见 pytest.ini / tests/conftest.py 的 collect_ignore 配置）。

## 运行方式
每个脚本包裹在 `if __name__ == "__main__":` 中，直接 `python tests/scripts/<script>.py` 运行。

## 文件说明
- test_aliyun_req_id.py：阿里云模型 requestID 记录有效性测试（helper/embedding/reranker/vlm/route_llm/main_llm/memory/kg_extract，覆盖 8 个调用点）
- test_milvus_api.py：Milvus 连接性手动验证
- test_redis.py：Redis 连接性手动验证
- probe_reranker.py：Reranker 探活脚本
- request_knowledge_chat.py / request_upload_docs.py：API 手动请求脚本
