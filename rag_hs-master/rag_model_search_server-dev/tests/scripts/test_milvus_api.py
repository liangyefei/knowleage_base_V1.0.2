import pytest

from configs.logger_manager import logger

pytest.importorskip("pymilvus")
if __name__ == "__main__":
    from pymilvus import connections

    connections.connect(
        alias="default",
        host="192.168.80.250",
        port=19530
    )
    logger.info("连接成功")
