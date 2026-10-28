#!/bin/bash

# ============================================
# Redis 部署脚本
# 基于 docker-compose 一键部署
# ============================================

set -e

# 配置信息
DATA_DIR="/data/redis/data"
CONTAINER_NAME="haisi-redis-server"
PORT=16379
PASSWORD="farai123"
HOST_IP="192.168.80.250"

echo "=========================================="
echo "Redis 部署脚本"
echo "=========================================="
echo "数据目录: $DATA_DIR"
echo "端口映射: $PORT -> 6379"
echo "容器名称: $CONTAINER_NAME"
echo "宿主机 IP: $HOST_IP"
echo "=========================================="

# 创建数据目录
echo "[1/3] 创建数据目录..."
sudo mkdir -p $DATA_DIR
sudo chmod 777 $DATA_DIR
echo "✓ 数据目录已创建: $DATA_DIR"

# 构建并启动容器
echo "[2/3] 构建并启动 Redis 容器..."
sudo docker compose -f docker-compose.yml up -d --build
echo "✓ 容器已启动"

# 等待容器启动
sleep 3

# 检查容器状态
echo "[3/3] 检查部署状态..."
echo "------------------------------------------"
echo "容器状态:"
sudo docker ps | grep $CONTAINER_NAME
echo "------------------------------------------"

# 测试连接
echo "连接测试:"
sudo docker exec $CONTAINER_NAME redis-cli -a $PASSWORD ping

echo "=========================================="
echo "✓ Redis 部署完成"
echo "=========================================="
echo "连接方式:"
echo "  本地连接: redis-cli -h 127.0.0.1 -p $PORT -a $PASSWORD"
echo "  远程连接: redis-cli -h $HOST_IP -p $PORT -a $PASSWORD"
echo "=========================================="
echo "常用命令:"
echo "  查看日志: sudo docker logs $CONTAINER_NAME"
echo "  进入 CLI: sudo docker exec -it $CONTAINER_NAME redis-cli -a $PASSWORD"
echo "  重启容器: sudo docker restart $CONTAINER_NAME"
echo "  停止容器: sudo docker compose -f docker-compose.yml down"
echo "=========================================="
