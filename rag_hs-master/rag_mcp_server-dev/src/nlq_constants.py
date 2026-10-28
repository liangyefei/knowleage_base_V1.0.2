#!/usr/bin/env python3
"""
nl_query_server.py — 自然语言代码查询常驻 HTTP 服务 (FastAPI)

启动后常驻内存，直接读 SQLite 知识图谱（只读），查询 ~20ms。
FastAPI 自带 Swagger UI (/docs) 和 OpenAPI schema。

用法:
    python3 nl_query_server.py
    python3 nl_query_server.py --port 9788 --host 127.0.0.1

    启动后访问 http://localhost:9788/docs 进入交互式 API 文档。
"""

import argparse
from dataclasses import dataclass
import asyncio
import datetime
import json
import os
import re
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel, Field

import logging

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("nl_query_server")

# 扫描 G.TYP.03：重复字典 key 常量化
_K_CALLERS = "callers"
_K_CALLEES = "callees"
_K_REF_NAME = "ref_name"
_K_LINE = "line"
_K_STATUS = "status"
_K_DATA = "data"
_PROC = "/proc"
_ST_PENDING = "pending"
_ST_RUNNING = "running"
_ST_DONE = "done"
_ST_FAILED = "failed"
_K_NAME = "name"
_K_TYPE = "type"
_K_SOURCE = "source"
_K_MATCHES = "matches"
_K_MATCHED = "matched"
_K_FILE_PATH = "file_path"
_K_FILE = "file"
_K_LABEL = "label"
_K_JOB_ID = "job_id"
_K_ID = "id"
_K_STAGES = "stages"
_K_COMPLETED_AT = "completed_at"
_K_TARGET = "target"
_K_EDGES = "edges"
_K_NODES = "nodes"
_K_DEG = "deg"
_K_CURRENT = "current"
_K_CONFIDENCE = "confidence"
_K_VALID_FROM = "valid_from"
_K_VALID_TO = "valid_to"
_K_START_LINE = "start_line"
_K_END_LINE = "end_line"
_K_DOC = "doc"
_K_DOC_NAME = "doc_name"
_K_DOC_REF = "doc_ref"
_K_SYMBOLS = "symbols"
_K_ENC_IGNORE = "ignore"
_K_RB = "rb"
_MATCH_DOCUMENT = "document"


# ── 配置 ────────────────────────────────────────────────────────────

CACHE_DIR = os.environ.get(
    "CBM_CACHE_DIR", os.path.expanduser("~/.cache/codebase-memory-mcp")
)
CBM_BIN = os.environ.get(
    "CBM_BIN", "/opt/codebase-memory-mcp/build/c/codebase-memory-mcp"
)
CODEGRAPH_BIN = os.environ.get("CODEGRAPH_BINARY", "codegraph")
LOG_DIR = os.environ.get("LOG_DIR", "/var/log")
# /doc_code/files 返回的「查看原文」链接基址：空=按请求 Host 推导
# （DEV 公网经 ELB 直连时即公网地址）；平台代理场景可显式覆盖
DOC_VIEW_BASE_URL = os.environ.get("DOC_VIEW_BASE_URL", "").strip().rstrip("/")


