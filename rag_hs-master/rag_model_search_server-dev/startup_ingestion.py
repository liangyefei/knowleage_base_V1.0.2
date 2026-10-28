"""Ingestion 入口（入库 + Kafka）。

务实拆分 Phase 2：复用 startup.py 的 fork + API + Kafka 编排，
通过 HAISI_RUN_MODE=ingest 门控路由（仅入库路由）+ 启动 KafkaCommServer。
镜像：haisi-rag-ingestion（带 kafka-python + 解析栈）。
"""
import asyncio
import os
import sys

os.environ["HAISI_RUN_MODE"] = "ingest"
# 仅起 API（入库路由 + Kafka），不起 WebUI
sys.argv = ["startup_ingestion.py", "--api"]

from startup import create_tables, engine, start_main_server

if __name__ == "__main__":
    # 幂等建表（init Job 已建则跳过）+ fork 前释放 PG 连接池
    create_tables()
    engine.dispose()
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(start_main_server())
