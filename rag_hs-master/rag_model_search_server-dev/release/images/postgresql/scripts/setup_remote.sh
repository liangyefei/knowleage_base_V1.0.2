#!/bin/bash

# ============================================
# PostgreSQL 远程访问配置脚本
# 修改监听地址并允许指定网段访问
# ============================================

set -e

# 配置信息
CONTAINER_NAME="postgresql-db"
HOST_IP="192.168.80.250"
PORT=16602
DB_NAME="faraidb"
DB_USER="farai"
DB_PASSWORD="farai123"
# 允许访问的网段（生产环境请按需收紧）
ALLOWED_NETWORK="192.168.81.0/24"

echo "=========================================="
echo "PostgreSQL 远程访问配置"
echo "=========================================="
echo "允许访问网段: $ALLOWED_NETWORK"
echo "=========================================="

# 1. 修改监听地址
echo "[1/3] 配置监听地址 (listen_addresses = '*')..."
sudo docker exec $CONTAINER_NAME sed -i \
    "s/#listen_addresses = 'localhost'/listen_addresses = '*'/g" \
    /var/lib/pgsql/data/userdata/postgresql.conf

# 验证修改
echo "    当前配置:"
sudo docker exec $CONTAINER_NAME grep "listen_addresses" \
    /var/lib/pgsql/data/userdata/postgresql.conf | grep -v "^#"

# 2. 配置客户端认证
echo "[2/3] 配置 pg_hba.conf 客户端认证..."
sudo docker exec $CONTAINER_NAME bash -c \
    "echo 'host all all $ALLOWED_NETWORK md5' >> /var/lib/pgsql/data/userdata/pg_hba.conf"

echo "    pg_hba.conf 末尾配置:"
sudo docker exec $CONTAINER_NAME tail -3 /var/lib/pgsql/data/userdata/pg_hba.conf

# 3. 重启容器使配置生效
echo "[3/3] 重启容器使配置生效..."
sudo docker compose -f ../docker-compose.yml restart

echo "等待服务启动..."
sleep 5

# 健康检查
echo "------------------------------------------"
echo "健康检查:"
sudo docker exec $CONTAINER_NAME pg_isready -U postgres

echo "=========================================="
echo "✓ 远程访问配置完成"
echo "=========================================="
echo "连接信息："
echo "   主机: $HOST_IP"
echo "   端口: $PORT"
echo "   数据库: $DB_NAME"
echo "   用户: $DB_USER"
echo "   密码: $DB_PASSWORD"
echo "=========================================="
echo "远程连接命令："
echo "   psql -h $HOST_IP -p $PORT -U $DB_USER -d $DB_NAME"
echo "=========================================="
echo "如需配置防火墙，请参考部署文档第 7.4 节。"
echo "=========================================="
