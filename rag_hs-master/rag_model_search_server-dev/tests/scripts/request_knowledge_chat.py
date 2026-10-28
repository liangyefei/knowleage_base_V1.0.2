#!/usr/bin/env python3
# @Time : 2024/3/18 14:48:02
# @Author : Hetao
# @File : [Code] --> request_knowledge_chat.py
# @Software: PyCharm
# @Function: TODO
import json

import requests

from configs.logger_manager import logger

url = "http://10.18.61.35:47861/kw/chat/multi_knowledge_base_chat"

request = {
    "query": "一加一等于多少",
    "user_id": "test_user_001",
    "knowledge_base_names": ["技术"],
    "top_k": 3,
    "score_threshold": 0.7,
    "history": [],
    "stream": True,
    "model_name": "chatglm3-6b",
    "temperature": 0.7,
    "max_tokens": 0,
    "prompt_name": "default"
}

headers = {"Content-Type": "application/json"}
# 发起请求并获取响应
res = requests.post(url, json=request)
logger.info("{}", res)
if res.status_code == 200:
    response = res.text
    logger.info("{}", response)