#!/usr/bin/env python3
# @Time : 2024/3/18 15:19:29
# @Author : Hetao
# @File : [Code] --> request_upload_docs.py
# @Software: PyCharm
# @Function: TODO
import requests

from configs.logger_manager import logger

url = "http://10.18.61.35:47861/kw/knowledge_base/upload_docs_use_path"

request = {
  "files": ["/mnt/d/data/release/knowledge/gptcoder-operation-platform-service/preTest/15/超级秒杀系统.txt"],
  "knowledge_base_name": "知识库1",
  "override": False,
  "to_vector_store": True,
  "chunk_size": 500,
  "chunk_overlap": 150,
  "zh_title_enhance": False,
  "not_refresh_vs_cache": False
}

headers = {"Content-Type": "application/json"}
# 发起请求并获取响应
res = requests.post(url, json=request)
logger.info("{}", res)
if res.status_code == 200:
    response = res.text
    logger.info("{}", response)