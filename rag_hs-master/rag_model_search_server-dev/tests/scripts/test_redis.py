# 该文件为手动连接脚本,非 pytest 用例;裸代码已包裹在 __main__ 块中避免收集期执行
import pytest

pytest.importorskip("redis")
if __name__ == "__main__":
    import redis
    redis_host = '192.168.41.164'
    redis_port = 6378
    redis_password = None  # Redis密码，如果有的话
    redis_db = 0  # Redis数据库索引
    redis_client = redis.StrictRedis(host=redis_host, port=redis_port, password=redis_password, db=redis_db)
    redis_client.set('key1', 'value1')
    redis_client.close()
