-- 连接pg
psql -U postgres

-- ==========================================
-- 1. 创建用户
-- ==========================================
CREATE USER hisilicon_knowledge WITH PASSWORD '123456';

-- ==========================================
-- 2. 创建数据库（指定UTF8编码、中文排序、归属该用户）
-- ==========================================
CREATE DATABASE hisilicon_knowledge
OWNER hisilicon_knowledge
ENCODING 'UTF8'
TEMPLATE = template0;

-- ==========================================
-- 3. 切换至新库（psql 元命令，请单独一行执行）
-- 注意：在 Navicat/DBeaver 的 SQL 编辑器里，请直接点击库名切换；
-- 在 psql 命令行中，请执行下面的 \c 命令
-- ==========================================
\c hisilicon_knowledge;

-- ==========================================
-- 4. 授权（为应用连接做准备）
-- 说明：由于该用户是数据库 OWNER，它默认拥有库内所有对象的全部权限。
-- 下面主要解决“未来由超级用户（postgres）创建的表”的访问问题，
-- 并设置了默认权限，确保以后新建的表自动可读写。
-- ==========================================

-- 4.1/4.2 授权（整改：扫描规则「账号授权」——应用账号 hisilicon_knowledge 已是库 OWNER，
-- 默认拥有库内全部对象权限，无需（也不应）再显式 GRANT ALL TABLES/SEQUENCES；
-- 未来由超级用户创建的对象通过下面的默认权限自动获得授权。）

-- 4.3 设置默认权限
ALTER DEFAULT PRIVILEGES IN SCHEMA hisilicon_knowledge
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hisilicon_knowledge;

ALTER DEFAULT PRIVILEGES IN SCHEMA hisilicon_knowledge
GRANT USAGE, SELECT ON SEQUENCES TO hisilicon_knowledge;

-- ==========================================
-- 5. （可选）确认当前设置
-- 查看当前数据库编码
-- SELECT datname, pg_encoding_to_char(encoding) FROM pg_database WHERE datname = 'hisilicon_knowledge';
-- ==========================================