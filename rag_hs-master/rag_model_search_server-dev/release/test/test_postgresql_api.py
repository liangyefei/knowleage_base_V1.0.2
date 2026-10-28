"""
PostgreSQL API 连通性测试脚本
测试 PostgreSQL 数据库的连接和基本功能

配置信息：
- Host: 192.168.80.250
- Port: 16602
- 数据库: faraidb
- 用户名: farai
- 密码: farai123
"""

import sys
from datetime import datetime, timezone

from configs.logger_manager import logger

try:
    import psycopg2
    PSYCOPG2_AVAILABLE = True
except ImportError:
    PSYCOPG2_AVAILABLE = False
    logger.warning("警告: psycopg2 未安装，请执行: pip install psycopg2-binary")


class PostgreSQLAPITester:
    def __init__(self, host="192.168.80.250", port=16602, database="faraidb", user="farai", password="farai123"):
        self.host = host
        self.port = port
        self.database = database
        self.user = user
        self.password = password
        self.results = []
        self.connection = None

    def log(self, test_name, success, message=""):
        """记录测试结果"""
        result = {
            "test": test_name,
            "success": success,
            "message": message,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }
        self.results.append(result)
        status = "✅" if success else "❌"
        logger.info("{} {}: {}", status, test_name, message)

    def test_connection(self):
        """测试连接"""
        _TEST_CONNECTION_LABEL = '连接测试'
        if not PSYCOPG2_AVAILABLE:
            self.log(_TEST_CONNECTION_LABEL, False, "psycopg2 未安装")
            return False

        try:
            self.connection = psycopg2.connect(
                host=self.host,
                port=self.port,
                database=self.database,
                user=self.user,
                password=self.password,
                connect_timeout=10
            )
            self.log(_TEST_CONNECTION_LABEL, True, f"成功连接到 {self.host}:{self.port}/{self.database}")
            return True
        except Exception as e:
            self.log(_TEST_CONNECTION_LABEL, False, str(e))
            return False

    def test_server_version(self):
        """测试服务器版本"""
        _TEST_SERVER_VER_LABEL = '服务器版本'
        if not self.connection:
            self.log(_TEST_SERVER_VER_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("SELECT version();")
            version = cursor.fetchone()[0]
            cursor.close()
            self.log(_TEST_SERVER_VER_LABEL, True, f"PostgreSQL: {version[:50]}...")
            return True
        except Exception as e:
            self.log(_TEST_SERVER_VER_LABEL, False, str(e))
            return False

    def test_database_info(self):
        """测试数据库信息"""
        _TEST_DATABASE_I_LABEL = '数据库信息'
        if not self.connection:
            self.log(_TEST_DATABASE_I_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("SELECT current_database(), current_user, inet_server_port();")
            info = cursor.fetchone()
            cursor.close()
            self.log(_TEST_DATABASE_I_LABEL, True, f"数据库: {info[0]}, 用户: {info[1]}, 端口: {info[2]}")
            return True
        except Exception as e:
            self.log(_TEST_DATABASE_I_LABEL, False, str(e))
            return False

    def test_list_tables(self):
        """测试列出表"""
        _TEST_LIST_TABLE_LABEL = '列出表'
        if not self.connection:
            self.log(_TEST_LIST_TABLE_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name;
            """)
            tables = cursor.fetchall()
            cursor.close()
            self.log(_TEST_LIST_TABLE_LABEL, True, f"共有 {len(tables)} 张表")
            return True
        except Exception as e:
            self.log(_TEST_LIST_TABLE_LABEL, False, str(e))
            return False

    def test_create_table(self):
        """测试创建表"""
        _TEST_CREATE_TAB_LABEL = '创建表'
        if not self.connection:
            self.log(_TEST_CREATE_TAB_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS test_table_haisi (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """)
            self.connection.commit()
            cursor.close()
            self.log(_TEST_CREATE_TAB_LABEL, True, "表 'test_table_haisi' 创建成功")
            return True
        except Exception as e:
            self.log(_TEST_CREATE_TAB_LABEL, False, str(e))
            return False

    def test_insert_data(self):
        """测试插入数据"""
        _TEST_INSERT_DAT_LABEL = '插入数据'
        if not self.connection:
            self.log(_TEST_INSERT_DAT_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("""
                INSERT INTO test_table_haisi (name) VALUES ('测试数据');
            """)
            self.connection.commit()
            cursor.close()
            self.log(_TEST_INSERT_DAT_LABEL, True, "成功插入测试数据")
            return True
        except Exception as e:
            self.log(_TEST_INSERT_DAT_LABEL, False, str(e))
            return False

    def test_query_data(self):
        """测试查询数据"""
        _TEST_QUERY_DATA_LABEL = '查询数据'
        if not self.connection:
            self.log(_TEST_QUERY_DATA_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("SELECT * FROM test_table_haisi;")
            rows = cursor.fetchall()
            cursor.close()
            self.log(_TEST_QUERY_DATA_LABEL, True, f"查询到 {len(rows)} 条记录")
            return True
        except Exception as e:
            self.log(_TEST_QUERY_DATA_LABEL, False, str(e))
            return False

    def test_drop_table(self):
        """测试删除表"""
        _TEST_DROP_TABLE_LABEL = '删除表'
        if not self.connection:
            self.log(_TEST_DROP_TABLE_LABEL, False, "未连接")
            return False

        try:
            cursor = self.connection.cursor()
            cursor.execute("DROP TABLE IF EXISTS test_table_haisi;")
            self.connection.commit()
            cursor.close()
            self.log(_TEST_DROP_TABLE_LABEL, True, "表 'test_table_haisi' 已删除")
            return True
        except Exception as e:
            self.log(_TEST_DROP_TABLE_LABEL, False, str(e))
            return False

    def cleanup(self):
        """清理连接"""
        if self.connection:
            try:
                self.connection.close()
            except Exception as e:
                self.log("清理连接", False, str(e))

    def run_all_tests(self):
        """运行所有测试"""
        logger.info("=" * 60)
        logger.info("PostgreSQL API 连通性测试")
        logger.info("服务地址: {}:{}", self.host, self.port)
        logger.info("数据库: {}", self.database)
        logger.info("测试时间: {}", datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S'))
        logger.info("=" * 60)
        logger.info("")

        if not PSYCOPG2_AVAILABLE:
            logger.info("❌ 请先安装 psycopg2: pip install psycopg2-binary")
            return False

        # 基本连接测试
        if not self.test_connection():
            logger.info("\n❌ 无法连接到 PostgreSQL，请检查服务是否启动")
            self.cleanup()
            return False

        # 功能测试
        self.test_server_version()
        self.test_database_info()
        self.test_list_tables()
        self.test_create_table()
        self.test_insert_data()
        self.test_query_data()
        self.test_drop_table()

        # 清理
        self.cleanup()

        # 汇总结果
        logger.info("")
        logger.info("=" * 60)
        total = len(self.results)
        passed = sum(1 for r in self.results if r["success"])
        logger.info("测试汇总: {}/{} 通过", passed, total)
        logger.info("=" * 60)

        return passed == total


def main():
    tester = PostgreSQLAPITester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()