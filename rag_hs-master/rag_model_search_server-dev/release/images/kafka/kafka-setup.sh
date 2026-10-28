#!/bin/bash
# ===========================================================================
# kafka-setup.sh - Kafka 本地开发环境一键启动脚本
#
# 功能：
#   1. 启动 Kafka（KRaft 模式，含主题初始化容器）
#   2. 等待 Kafka 就绪并验证 topic 创建成功
#   3. 将模型容器 haisi_rag_models_server_dev 接入 kafka-net 网络
#      （模型端通过 haisi-kafka-dev:9092 直连 Kafka）
#
# 用法：
#   bash release/images/kafka/kafka-setup.sh
#
# 前置条件：
#   - 已安装 Docker Engine 20.0+ 与 Docker Compose 2.0+
#   - 模型容器 haisi_rag_models_server_dev 已创建（见 docker/run_wsl_all.md）
#     若尚未创建，本脚本会跳过接入步骤并提示
# ===========================================================================

set -euo pipefail

# 脚本所在目录（docker-compose.yml 同目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MODEL_CONTAINER_NAME="haisi_rag_models_server_dev"
KAFKA_CONTAINER_NAME="haisi-kafka-dev"
KAFKA_NET="kafka-net"

echo "=========================================="
echo "Kafka 本地开发环境 Setup"
echo "=========================================="
echo "Compose 目录: $SCRIPT_DIR"
echo "Kafka 容器:   $KAFKA_CONTAINER_NAME"
echo "网络:         $KAFKA_NET"
echo "=========================================="

# --------------------------------------------------------------------------
# 1. 启动 Kafka（含主题初始化容器）
# --------------------------------------------------------------------------
echo "[1/3] 启动 Kafka ..."
if ! docker compose up -d; then
    echo "[error] docker compose up 失败，请检查 Docker 是否运行"
    exit 1
fi

# --------------------------------------------------------------------------
# 2. 等待 Kafka 就绪
# --------------------------------------------------------------------------
echo "[2/3] 等待 Kafka 就绪 ..."
READY=0
for i in $(seq 1 30); do
    if docker exec "$KAFKA_CONTAINER_NAME" kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        echo "[ok] Kafka 已就绪（耗时约 ${i}0 秒）"
        READY=1
        break
    fi
    echo "  等待中 ... (${i}/30)"
    sleep 10
done

if [ "$READY" -ne 1 ]; then
    echo "[error] Kafka 60 秒内未就绪，请查看日志："
    echo "        docker logs -f $KAFKA_CONTAINER_NAME"
    exit 1
fi

# --------------------------------------------------------------------------
# 3. 验证 topic 创建结果
# --------------------------------------------------------------------------
echo "[3/3] 验证 topic 列表 ..."
docker exec "$KAFKA_CONTAINER_NAME" kafka-topics.sh --bootstrap-server localhost:9092 --list

# 等待 init 容器完成 topic 创建（若尚未完成）
sleep 3
echo "------------------------------------------"
echo "预期 topic："
echo "  kb-document-processing  (16 分区)"
echo "  kb-document-processed   (8 分区)"
echo "------------------------------------------"
docker exec "$KAFKA_CONTAINER_NAME" kafka-topics.sh --bootstrap-server localhost:9092 --list

# --------------------------------------------------------------------------
# 4. 将模型容器接入 kafka-net（若存在）
# --------------------------------------------------------------------------
if docker ps -a --format '{{.Names}}' | grep -q "^${MODEL_CONTAINER_NAME}$"; then
    NETWORKS=$(docker inspect "$MODEL_CONTAINER_NAME" --format '{{json .NetworkSettings.Networks}}' 2>/dev/null || echo "{}")
    if echo "$NETWORKS" | grep -q "$KAFKA_NET"; then
        echo "[skip] 模型容器 $MODEL_CONTAINER_NAME 已在 $KAFKA_NET 网络中，无需重复接入"
    else
        echo "[info] 将模型容器 $MODEL_CONTAINER_NAME 接入 $KAFKA_NET ..."
        if docker network connect "$KAFKA_NET" "$MODEL_CONTAINER_NAME"; then
            echo "[ok] 接入成功"
        else
            echo "[warn] 接入失败，请手动执行：docker network connect $KAFKA_NET $MODEL_CONTAINER_NAME"
        fi
    fi
else
    echo "[warn] 模型容器 $MODEL_CONTAINER_NAME 未创建"
    echo "       请先按 docker/run_wsl_all.md 创建开发容器，再手动接入网络："
    echo "       docker network connect $KAFKA_NET $MODEL_CONTAINER_NAME"
fi

echo "=========================================="
echo "Kafka 本地环境部署完成"
echo "=========================================="
echo ""
echo "连接信息："
echo "  容器间通信: haisi-kafka-dev:9092"
echo "  宿主机测试: localhost:9094"
echo ""
echo "切换主项目配置（params/server_config.yaml）："
echo "  COMM_KAFKA_ADDRESS:"
echo "    bootstrap_servers: \"haisi-kafka-dev:9092\""
echo ""
echo "常用命令："
echo "  查看日志:   docker logs -f $KAFKA_CONTAINER_NAME"
echo "  查看主题:   docker exec $KAFKA_CONTAINER_NAME kafka-topics.sh --bootstrap-server localhost:9092 --list"
echo "  消费消息:   docker exec $KAFKA_CONTAINER_NAME kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic kb-document-processing --from-beginning"
echo "  停止服务:   docker compose down（在 $SCRIPT_DIR 下）"
echo "  清理数据:   docker compose down -v（在 $SCRIPT_DIR 下，会删除 kafka_data 卷）"
echo "=========================================="
