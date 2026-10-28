"""QA 入口（问答 + WebUI 并入，无 Kafka）。

务实拆分 Phase 2：复用 startup.py 的 fork + API + WebUI 编排，
通过 HAISI_RUN_MODE=qa 门控路由（仅问答路由）+ 跳过 KafkaCommServer。
WebUI 并入 QA（3.5-B），同 Pod 起 streamlit。
镜像：haisi-rag-qa（无 kafka-python，QA 瘦身）。
"""
import asyncio
import os
import sys

os.environ["HAISI_RUN_MODE"] = "qa"
# 起 API（问答路由）+ WebUI（并入 QA，3.5-B）
sys.argv = ["startup_qa.py", "--all-webui"]

from startup import create_tables, engine, start_main_server

if __name__ == "__main__":
    # 幂等建表（表由 ingestion init Job 建，此处仅兜底）+ fork 前释放 PG 连接池
    create_tables()
    engine.dispose()
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(start_main_server())
