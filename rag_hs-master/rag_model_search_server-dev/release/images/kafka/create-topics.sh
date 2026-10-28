#!/bin/bash
# ===========================================================================
# create-topics.sh - Kafka 业务主题初始化脚本
#
# 由 docker-compose.yml 中的 kafka-init-topics 容器在启动时自动执行
# 也可手动在 Kafka 容器内执行：
#   docker exec haisi-kafka-dev /init/create-topics.sh
#
# 创建的业务主题（与 params/server_config.yaml COMM_KAFKA_ADDRESS 对齐）：
#   kb-document-processing  (16 分区)  Backend -> Model  文档处理请求
#   kb-document-processed   (8 分区)   Model -> Backend  文档处理回调
# ===========================================================================

set -euo pipefail

BOOTSTRAP_SERVER="haisi-kafka-dev:9092"

echo "=========================================="
echo "Kafka 业务主题初始化"
echo "=========================================="
echo "Bootstrap: $BOOTSTRAP_SERVER"
echo "=========================================="

# 等待 Kafka 就绪（init 容器依赖 kafka healthy，但再多一层保险）
echo "[1/4] 等待 Kafka 就绪 ..."
for i in $(seq 1 30); do
    if kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --list >/dev/null 2>&1; then
        echo "[ok] Kafka 已就绪"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "[error] Kafka 300 秒内未就绪，放弃初始化"
        exit 1
    fi
    echo "  等待中 ... (${i}/30)"
    sleep 10
done

# --------------------------------------------------------------------------
# 创建 kb-document-processing（Backend -> Model，16 分区）
# --------------------------------------------------------------------------
echo "[2/4] 创建 topic: kb-document-processing (16 分区) ..."
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic kb-document-processing \
    --partitions 16 \
    --replication-factor 1 \
    --config cleanup.policy=delete \
    --config retention.ms=604800000 \
    --config max.message.bytes=10485760

# --------------------------------------------------------------------------
# 创建 kb-document-processed（Model -> Backend，8 分区）
# --------------------------------------------------------------------------
echo "[3/4] 创建 topic: kb-document-processed (8 分区) ..."
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic kb-document-processed \
    --partitions 8 \
    --replication-factor 1 \
    --config cleanup.policy=delete \
    --config retention.ms=604800000 \
    --config max.message.bytes=10485760

# --------------------------------------------------------------------------
# 验证 topic 创建结果
# --------------------------------------------------------------------------
echo "[4/4] 验证 topic 列表与分区数 ..."
echo "------------------------------------------"
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --list
echo "------------------------------------------"
echo "kb-document-processing 分区详情："
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --describe --topic kb-document-processing
echo "------------------------------------------"
echo "kb-document-processed 分区详情："
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --describe --topic kb-document-processed
echo "------------------------------------------"
echo "[done] 主题初始化完成"
