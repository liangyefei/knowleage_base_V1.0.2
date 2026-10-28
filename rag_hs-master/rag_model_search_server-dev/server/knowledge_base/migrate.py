"""数据库表创建/重置工具。

开发阶段无历史数据迁移需求，仅保留 create_tables / reset_tables，
供 startup.py 和 init_database.py 使用。
"""
from server.db.base import Base, engine


def create_tables():
    Base.metadata.create_all(bind=engine)


def reset_tables():
    Base.metadata.drop_all(bind=engine)
    create_tables()
