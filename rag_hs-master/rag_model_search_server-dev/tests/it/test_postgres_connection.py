#!/usr/bin/env python3
"""测试 PostgreSQL 数据库连接"""

from sqlalchemy import create_engine, text

from configs import SQLALCHEMY_DATABASE_URI, logger


def test_postgres_connection():
    """测试 PostgreSQL 连接"""
    logger.info("数据库连接字符串: {}", SQLALCHEMY_DATABASE_URI)

    try:
        # 创建引擎
        engine = create_engine(SQLALCHEMY_DATABASE_URI)

        # 测试连接
        with engine.connect() as conn:
            result = conn.execute(text("SELECT version();"))
            version = result.fetchone()[0]
            logger.info("✅ PostgreSQL 连接成功!")
            logger.info("数据库版本: {}", version)

            # 检查现有表
            result = conn.execute(text("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name;
            """))

            tables = [row[0] for row in result]
            if tables:
                logger.info("\n现有数据表 ({} 个):", len(tables))
                for table in tables:
                    logger.info("  - {}", table)
            else:
                logger.info("\n数据库中没有表（需要运行初始化）")

            return True

    except Exception as e:
        logger.error("❌ 连接失败: {}", e)
        return False

if __name__ == "__main__":
    test_postgres_connection()