import json

from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import DeclarativeMeta, declarative_base
from sqlalchemy.orm import sessionmaker

from configs import SQLALCHEMY_DATABASE_URI

engine = create_engine(
    SQLALCHEMY_DATABASE_URI,
    json_serializer=lambda obj: json.dumps(obj, ensure_ascii=False),
    pool_pre_ping=True,
    pool_recycle=3600,
    pool_size=10,
    max_overflow=20,
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base: DeclarativeMeta = declarative_base()
