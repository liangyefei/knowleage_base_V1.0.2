#!/bin/bash

# ============================================
# PostgreSQL 15 部署脚本
# 基于 docker-compose 一键部署
# ============================================

set -e

# 配置信息
CONTAINER_NAME="postgresql-db"
PORT=16602
DB_NAME="faraidb"
DB_USER="farai"
DB_PASSWORD="farai123"
HOST_IP="192.168.80.250"

echo "=========================================="
echo "PostgreSQL 15 部署脚本"
echo "=========================================="
echo "容器名称: $CONTAINER_NAME"
echo "端口映射: $PORT -> 5432"
echo "数据库: $DB_NAME"
echo "普通用户: $DB_USER"
echo "宿主机 IP: $HOST_IP"
echo "=========================================="

# 构建并启动容器
echo "[1/3] 构建并启动 PostgreSQL 容器..."
sudo docker compose -f docker-compose.yml up -d --build
echo "✓ 容器已启动"

# 等待数据库初始化
echo "[2/3] 等待数据库初始化（约 15 秒）..."
sleep 15

# 检查容器状态
echo "[3/3] 检查部署状态..."
echo "------------------------------------------"
echo "容器状态:"
sudo docker ps | grep $CONTAINER_NAME
echo "------------------------------------------"

# 健康检查
echo "健康检查:"
sudo docker exec $CONTAINER_NAME pg_isready -U postgres

# 测试连接并查询版本
echo "------------------------------------------"
echo "数据库版本:"
sudo docker exec $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME -c "SELECT version();" 2>/dev/null \
    || echo "(数据库初始化中，请稍后使用以下命令重试：sudo docker exec $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME -c \"SELECT version();\")"

echo "=========================================="
echo "✓ PostgreSQL 部署完成"
echo "=========================================="
echo "连接方式:"
echo "  容器内连接: sudo docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
echo "  本地连接:   psql -h 127.0.0.1 -p $PORT -U $DB_USER -d $DB_NAME"
echo "  远程连接:   psql -h $HOST_IP -p $PORT -U $DB_USER -d $DB_NAME"
echo "  密码: $DB_PASSWORD"
echo "=========================================="
echo "常用命令:"
echo "  查看日志: sudo docker compose -f docker-compose.yml logs -f"
echo "  重启服务: sudo docker compose -f docker-compose.yml restart"
echo "  停止服务: sudo docker compose -f docker-compose.yml down"
echo "  停止并删除数据: sudo docker compose -f docker-compose.yml down -v"
echo "=========================================="
echo "如需开启远程访问，请执行:"
echo "  sudo bash scripts/setup_remote.sh"
echo "=========================================="
