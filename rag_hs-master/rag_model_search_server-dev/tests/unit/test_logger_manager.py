#!/usr/bin/env python3
"""logger_manager UT：验证向量化/ERROR 独立 sink + 滚动 + retention。"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import asyncio
import os
import tempfile

import pytest
from loguru import logger


def _flush(lg):
    """等待 loguru enqueue=True 的所有 sink 刷盘完成。

    loguru 的 complete() 返回 AwaitableCompleter（非 coroutine），
    故用 run_until_complete 而非 asyncio.run。
    """
    loop = asyncio.new_event_loop()
    try:
        loop.run_until_complete(lg.complete())
    finally:
        loop.close()


def test_create_logger_has_four_sinks():
    """create_logger 应注册 4 个 sink：console + 通用文件 + 向量化文件 + ERROR 文件。"""
    with tempfile.TemporaryDirectory() as tmp:
        from configs.logger_manager import create_logger
        lg = create_logger(log_path=tmp, log_level="DEBUG")
        handler_count = len(lg._core.handlers)
        assert handler_count >= 4, f"预期 >=4 个 sink，实际 {handler_count}"


def test_vectorize_log_goes_to_vectorize_file():
    """[tx] 前缀日志应写入 kw_vectorize_*.log，非 [tx] 日志不写入。"""
    with tempfile.TemporaryDirectory() as tmp:
        from configs.logger_manager import create_logger
        lg = create_logger(log_path=tmp, log_level="DEBUG")
        lg.info("[tx] test_file vectorize start: 10 chunks")
        lg.info("普通日志不应进向量化文件")
        # loguru enqueue=True 是异步，需 complete() 等待刷盘
        _flush(lg)
        vec_files = [f for f in os.listdir(tmp) if "vectorize" in f]
        assert len(vec_files) >= 1, f"预期有 vectorize 日志文件，实际 {os.listdir(tmp)}"
        with open(os.path.join(tmp, vec_files[0]), encoding='utf-8') as f:
            content = f.read()
        assert "[tx]" in content, "向量化文件应含 [tx] 日志"
        assert "普通日志" not in content, "向量化文件不应含非 [tx] 日志"


def test_error_log_goes_to_error_file():
    """ERROR+ 日志应写入 kw_error_*.log，INFO 不写入。"""
    with tempfile.TemporaryDirectory() as tmp:
        from configs.logger_manager import create_logger
        lg = create_logger(log_path=tmp, log_level="DEBUG")
        lg.error("测试错误日志")
        lg.info("普通信息")
        _flush(lg)
        err_files = [f for f in os.listdir(tmp) if "error" in f]
        assert len(err_files) >= 1, f"预期有 error 日志文件，实际 {os.listdir(tmp)}"
        with open(os.path.join(tmp, err_files[0]), encoding='utf-8') as f:
            content = f.read()
        assert "测试错误日志" in content, "ERROR 文件应含 error 日志"
        assert "普通信息" not in content, "ERROR 文件不应含 INFO 日志"
