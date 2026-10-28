# Kafka 本地开发环境部署文档

## 📋 目录

* 概述
* 环境要求
* 项目结构
* 文件内容
* 部署步骤
* 主题说明
* 测试指南
* 故障排查
* 与主项目集成

***

## 概述

Kafka 是 Apache 开源的分布式消息队列系统，支持高吞吐、持久化、多消费者组消费。本项目使用 Kafka 作为知识库文档处理的消息总线，后端与模型服务通过两个 topic 解耦：

* **出站 topic（Backend → Model）**：`kb-document-processing`，后端投递待处理文档
* **入站 topic（Model → Backend）**：`kb-document-processed`，模型回调处理结果

**生产/测试环境**使用华为云 DMS Kafka（k8s 内网三节点集群），**本地开发环境**使用本目录提供的单节点 Kafka（KRaft 模式，无需 Zookeeper）作为替代。

**典型应用场景**：

* 本地无 DMS Kafka 环境时的开发调试
* 模型端消费者逻辑的本地验证
* 消息格式与消费链路的端到端联调

**与主项目的关系**：

主项目 `haisi_rag_models_server` 的 comm 模块（`server/comm/`）通过 `params/server_config.yaml` 中的 `COMM_KAFKA_ADDRESS` 配置连接 Kafka。本地开发时将 `bootstrap_servers` 切换为 `haisi-kafka-dev:9092` 即可，无需改动业务代码。

| **项**       | **说明**                              |
| ----------- | ----------------------------------- |
| 服务名称        | haisi-kafka-dev                     |
| 镜像          | bitnami/kafka:3.7                   |
| 运行模式        | KRaft（单节点 controller+broker，无 Zookeeper） |
| 容器间通信端口     | 9092（INTERNAL，advertised: haisi-kafka-dev:9092） |
| 宿主机测试端口     | 9094（EXTERNAL，advertised: localhost:9094） |
| 数据持久化       | docker volume `kafka_data`         |
| 网络          | kafka-net（bridge）                   |
| GPU         | 不需要（纯消息队列）                          |

> **当前部署位置**：本服务部署在 `192.168.80.250`（Windows + WSL2）本地开发环境，仅用于本地联调。生产环境请使用 DMS Kafka。

***

## 环境要求

### 硬件要求

| **组件** | **最低要求**    | **推荐配置**    |
| ------ | ---------- | ---------- |
| CPU    | x86_64，2 核 | 4 核及以上     |
| 内存     | ≥ 2GB      | ≥ 4GB      |
| 硬盘     | ≥ 5GB      | ≥ 20GB     |
| GPU    | 不需要        | 不需要        |

> 单节点 Kafka 本地开发，1GB JVM 堆即可满足知识库文档处理消息吞吐。

### 软件要求

| **组件**      | **版本要求**     | **说明**                |
| ----------- | ------------ | --------------------- |
| 操作系统        | Linux / WSL2 | 推荐 Ubuntu 18.04+      |
| Docker      | ≥ 20.0       | 支持 bridge 网络即可        |
| Docker Compose | ≥ 2.0       | 使用 `docker compose` 子命令 |

### 网络要求

| **访问场景**             | **连接地址**              | **说明**            |
| -------------------- | --------------------- | ----------------- |
| 模型容器 → Kafka（推荐）     | `haisi-kafka-dev:9092` | 模型容器需接入 `kafka-net` 网络 |
| 宿主机本机测试              | `localhost:9094`      | 用 `kafka-console-*` 工具调试 |
| 局域网其他机器访问            | 不直接暴露                 | 本地开发环境无需对外，生产用 DMS |

> ⚠️ **advertised listeners 说明**：Kafka 的 `advertised.listeners` 决定 broker 返回给客户端的连接地址。
> * 模型容器接入 `kafka-net` 后，通过 `haisi-kafka-dev:9092` 直连，advertised 地址为 `haisi-kafka-dev:9092`，容器间 DNS 解析正常
> * 宿主机用 `localhost:9094` 连 EXTERNAL listener，advertised 为 `localhost:9094`
> * **不要混用**：模型容器若用 `localhost:9094` 会连不上（容器内 localhost 不是宿主机）

***

## 项目结构

```
release/images/kafka/
├── Kafka部署文档.md        # 本文档
├── docker-compose.yml      # Docker Compose 编排（Kafka + 主题初始化容器）
├── kafka-setup.sh          # 一键启动脚本（含网络接入）
└── create-topics.sh        # 业务主题创建脚本（由 init 容器自动执行）
```

***

## 文件内容

### 1. `docker-compose.yml`

```yaml
version: '3.8'

services:
  kafka:
    image: bitnami/kafka:3.7
    container_name: haisi-kafka-dev
    ports:
      - "9092:9092"   # INTERNAL：容器间通信
      - "9094:9094"   # EXTERNAL：宿主机测试
    environment:
      # KRaft 模式配置
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@haisi-kafka-dev:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER

      # 监听器配置
      KAFKA_CFG_LISTENERS: INTERNAL://:9092,CONTROLLER://:9093,EXTERNAL://:9094
      KAFKA_CFG_ADVERTISED_LISTENERS: INTERNAL://haisi-kafka-dev:9092,EXTERNAL://localhost:9094
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: INTERNAL

      # Topic 默认配置
      KAFKA_CFG_NUM_PARTITIONS: 16
      KAFKA_CFG_DEFAULT_REPLICATION_FACTOR: 1
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_CFG_LOG_RETENTION_HOURS: 168
      KAFKA_CFG_MESSAGE_MAX_BYTES: 10485760

      # JVM 堆
      KAFKA_HEAP_OPTS: "-Xmx1G -Xms1G"

    volumes:
      - kafka_data:/bitnami/kafka
    networks:
      - kafka-net
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 40s

  # 主题初始化容器（启动后自动创建两个业务 topic，执行完退出）
  kafka-init-topics:
    image: bitnami/kafka:3.7
    container_name: haisi-kafka-init-topics
    depends_on:
      kafka:
        condition: service_healthy
    entrypoint: ["/bin/bash", "/init/create-topics.sh"]
    volumes:
      - ./create-topics.sh:/init/create-topics.sh:ro
    networks:
      - kafka-net
    restart: "no"

volumes:
  kafka_data:
    driver: local

networks:
  kafka-net:
    name: kafka-net
    driver: bridge
```

**关键设计说明**：

| **配置项**                                          | **说明**                                              |
| ------------------------------------------------- | --------------------------------------------------- |
| `KAFKA_CFG_PROCESS_ROLES: controller,broker`      | KRaft 模式，单节点同时承担 controller 与 broker 角色，无需 Zookeeper |
| `KAFKA_CFG_CONTROLLER_QUORUM_VOTERS`              | KRaft 仲裁者列表，单节点自指 `1@haisi-kafka-dev:9093`          |
| 双 listener（INTERNAL + EXTERNAL）                    | 容器间走 `haisi-kafka-dev:9092`，宿主机走 `localhost:9094`  |
| `kafka-init-topics` 容器                             | 依赖 `kafka` healthy 后执行 `create-topics.sh`，创建完即退出    |
| `kafka_data` volume                                | 数据持久化，容器重启不丢消息；清理需 `docker compose down -v`        |

### 2. `create-topics.sh`

```bash
#!/bin/bash
set -euo pipefail

BOOTSTRAP_SERVER="haisi-kafka-dev:9092"

# 等待 Kafka 就绪
for i in $(seq 1 30); do
    if kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --list >/dev/null 2>&1; then
        break
    fi
    sleep 10
done

# 创建 kb-document-processing（16 分区）
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create --if-not-exists \
    --topic kb-document-processing \
    --partitions 16 \
    --replication-factor 1 \
    --config cleanup.policy=delete \
    --config retention.ms=604800000 \
    --config max.message.bytes=10485760

# 创建 kb-document-processed（8 分区）
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create --if-not-exists \
    --topic kb-document-processed \
    --partitions 8 \
    --replication-factor 1 \
    --config cleanup.policy=delete \
    --config retention.ms=604800000 \
    --config max.message.bytes=10485760

# 验证
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --describe --topic kb-document-processing
kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --describe --topic kb-document-processed
```

**主题参数说明**：

| **参数**              | **值**       | **说明**                          |
| ------------------- | ---------- | ------------------------------- |
| `--partitions`      | 16 / 8     | 与 DMS Kafka 生产环境对齐，保证消费者并行度一致   |
| `--replication-factor` | 1          | 单节点本地开发，副本数 1 即可；生产环境 DMS 为 3   |
| `cleanup.policy`    | delete     | 消息消费后按 retention 过期删除，避免磁盘膨胀     |
| `retention.ms`      | 604800000  | 7 天保留期（本地开发够用）                  |
| `max.message.bytes` | 10485760   | 10MB，知识库文档处理消息可能较大              |

### 3. `kafka-setup.sh`

```bash
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MODEL_CONTAINER_NAME="haisi_rag_models_server_dev"
KAFKA_CONTAINER_NAME="haisi-kafka-dev"
KAFKA_NET="kafka-net"

# 1. 启动 Kafka（含主题初始化容器）
docker compose up -d

# 2. 等待 Kafka 就绪
for i in $(seq 1 30); do
    if docker exec "$KAFKA_CONTAINER_NAME" kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        break
    fi
    sleep 10
done

# 3. 验证 topic
docker exec "$KAFKA_CONTAINER_NAME" kafka-topics.sh --bootstrap-server localhost:9092 --list

# 4. 将模型容器接入 kafka-net（若存在）
if docker ps -a --format '{{.Names}}' | grep -q "^${MODEL_CONTAINER_NAME}$"; then
    NETWORKS=$(docker inspect "$MODEL_CONTAINER_NAME" --format '{{json .NetworkSettings.Networks}}')
    if ! echo "$NETWORKS" | grep -q "$KAFKA_NET"; then
        docker network connect "$KAFKA_NET" "$MODEL_CONTAINER_NAME"
    fi
fi
```

***

## 部署步骤

### 1. 环境准备

确保 WSL2 已安装 Docker 与 docker-compose：

```bash
# 进入 WSL
wsl -d Ubuntu

# 验证 Docker
docker --version
docker compose version
```

### 2. 进入部署目录

```bash
cd /data/haisi/haisi_rag_models_server/release/images/kafka
```

### 3. 一键启动（推荐）

```bash
bash kafka-setup.sh
```

脚本会自动完成：
1. `docker compose up -d` 启动 Kafka 与主题初始化容器
2. 等待 Kafka 就绪（最多 300 秒）
3. 验证 topic 创建结果
4. 将模型容器 `haisi_rag_models_server_dev` 接入 `kafka-net` 网络（若存在）

### 4. 手动启动（可选）

若不想用脚本，可手动执行：

```bash
# 启动 Kafka + 主题初始化容器
docker compose up -d

# 等待 Kafka 就绪后，将模型容器接入网络
docker network connect kafka-net haisi_rag_models_server_dev
```

### 5. 验证部署

```bash
# 查看容器状态
docker ps | grep haisi-kafka

# 查看 Kafka 日志（等待 "started" 字样）
docker logs -f haisi-kafka-dev

# 列出 topic（应看到 kb-document-processing 和 kb-document-processed）
docker exec haisi-kafka-dev kafka-topics.sh --bootstrap-server localhost:9092 --list

# 查看主题详情
docker exec haisi-kafka-dev kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic kb-document-processing
```

预期输出：

```
Topic: kb-document-processing	TopicId: xxx	PartitionCount: 16	ReplicationFactor: 1
	Topic: kb-document-processing	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: kb-document-processing	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	...
Topic: kb-document-processed	TopicId: yyy	PartitionCount: 8	ReplicationFactor: 1
	Topic: kb-document-processed	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	...
```

### 6. Windows WSL2 端口转发（可选，仅外部访问需要）

本地开发环境默认仅 WSL 内部与模型容器访问，无需对外暴露。若需从局域网其他机器访问 Kafka（一般不需要，生产用 DMS），可参照 [LAC 部署文档 4.1 节](../lac/LAC部署文档.md#41-windows-wsl2-部署补充暴露端口到局域网) 配置端口转发：

```powershell
# 以管理员身份打开 PowerShell
netsh interface portproxy add v4tov4 listenport=9094 listenaddress=0.0.0.0 connectport=9094 connectaddress=172.24.104.177
New-NetFirewallRule -DisplayName "Kafka 9094" -Direction Inbound -LocalPort 9094 -Protocol TCP -Action Allow
```

> 本地 Kafka 一般不对外暴露，模型容器通过 `kafka-net` 内部网络访问即可。

***

## 主题说明

### 业务主题列表

| **Topic**                 | **方向**            | **分区数** | **key**     | **用途**           |
| ------------------------- | ----------------- | ------- | ----------- | ---------------- |
| `kb-document-processing`  | Backend → Model   | 16      | `kbId`      | 后端投递待处理文档请求      |
| `kb-document-processed`   | Model → Backend   | 8       | `documentId` | 模型回调文档处理结果       |

### 消费者组

| **消费者组**         | **消费 topic**            | **消费者数** | **说明**              |
| ---------------- | ----------------------- | --------- | ------------------- |
| `model-service`  | `kb-document-processing` | 4（默认）     | 模型端消费文档处理请求；多实例时总消费者数 ≤ 16（分区数） |

### 分区与并行度

* `kb-document-processing` 16 分区：同一 `kbId` 的消息落同一分区，保证知识库内文档处理顺序；16 分区支持最多 16 个消费者并行
* `kb-document-processed` 8 分区：同一 `documentId` 的回调落同一分区，后端可按 key 路由到同一消费者处理状态更新
* 模型端 `consumer_workers: 4`，多 Pod 部署时 `Pod 数 × consumer_workers ≤ 16`（详见 `params/server_config.yaml`）

### 消息保留策略

| **配置**            | **值**     | **说明**                |
| ----------------- | -------- | --------------------- |
| `cleanup.policy`  | delete   | 按时间过期删除，非 compact     |
| `retention.ms`    | 604800000 | 7 天（本地开发环境）           |
| `max.message.bytes` | 10485760 | 单条消息最大 10MB           |

***

## 测试指南

### 1. 命令行工具测试

bitnami/kafka 镜像内置 `kafka-console-producer.sh` 与 `kafka-console-consumer.sh`，可在容器内直接测试。

**消费消息（开一个终端先订阅）**：

```bash
# 消费 kb-document-processing topic（从最新 offset 开始）
docker exec -it haisi-kafka-dev kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic kb-document-processing \
    --group test-consumer
```

**生产消息（另开一个终端投递）**：

```bash
# 往 kb-document-processing topic 投递测试消息
docker exec -it haisi-kafka-dev kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic kb-document-processing
# 然后输入消息内容，回车发送，Ctrl+C 退出
```

### 2. 从开头消费

```bash
# 从最早 offset 开始消费（--from-beginning）
docker exec -it haisi-kafka-dev kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic kb-document-processed \
    --from-beginning
```

### 3. 查看消费者组状态

```bash
# 查看所有消费者组
docker exec haisi-kafka-dev kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --list

# 查看 model-service 消费者组详情（lag 表示积压消息数）
docker exec haisi-kafka-dev kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --group model-service
```

### 4. Python 客户端测试

```python
from kafka import KafkaProducer, KafkaConsumer
import json

# 生产测试消息
producer = KafkaProducer(
    bootstrap_servers=['localhost:9094'],
    value_serializer=lambda v: json.dumps(v).encode('utf-8'),
    key_serializer=str.encode
)
producer.send('kb-document-processing', key='kb-001', value={'docId': 'doc-1', 'action': 'PROCESS'})
producer.flush()
print('消息已发送')

# 消费测试
consumer = KafkaConsumer(
    'kb-document-processing',
    bootstrap_servers=['localhost:9094'],
    auto_offset_reset='earliest',
    group_id='test-py-consumer',
    value_deserializer=lambda x: json.loads(x.decode('utf-8'))
)
for msg in consumer:
    print(f'收到: topic={msg.topic}, key={msg.key}, value={msg.value}')
    break
```

> Python 客户端用 `localhost:9094`（EXTERNAL listener）测试；模型容器内用 `haisi-kafka-dev:9092`（INTERNAL listener）。

### 5. 模型容器内连通性验证

```bash
# 进入模型容器
docker exec -it haisi_rag_models_server_dev bash

# 验证能否连接 Kafka（应能解析 haisi-kafka-dev 并返回 topic 列表）
python3 -c "
from kafka import KafkaAdminClient
client = KafkaAdminClient(bootstrap_servers='haisi-kafka-dev:9092')
print('topics:', client.list_topics())
"
```

***

## 故障排查

### 常见问题及解决方案

| **问题**                          | **可能原因**                          | **解决方案**                          |
| ------------------------------- | --------------------------------- | --------------------------------- |
| 模型容器连接 `haisi-kafka-dev:9092` 失败 | 模型容器未接入 `kafka-net` 网络           | `docker network connect kafka-net haisi_rag_models_server_dev` |
| 宿主机 `localhost:9094` 连不上        | EXTERNAL listener 未生效或端口未映射        | `docker port haisi-kafka-dev` 确认 9094 已映射；检查 docker-compose.yml |
| `Advertised listener` 错误        | advertised 地址与实际访问地址不一致            | 模型容器必须用 `haisi-kafka-dev:9092`，宿主机必须用 `localhost:9094`，不可混用 |
| 主题未自动创建                         | init 容器未执行或 Kafka 未就绪              | `docker logs haisi-kafka-init-topics` 查看初始化日志；手动执行 `create-topics.sh` |
| 消费者 lag 持续增长                    | 消费者处理速度跟不上生产速度                    | 检查 `consumer_workers` 配置；多实例部署增加消费者数（总数 ≤ 分区数 16） |
| 容器启动后立即退出                       | KRaft 配置错误或端口冲突                   | `docker logs haisi-kafka-dev` 查看错误栈 |
| 磁盘占用持续增长                       | 消息未过期清理                           | 检查 `retention.ms` 配置；`docker compose down -v` 清理卷 |
| `bitnami/kafka:3.7` 拉取失败         | 网络问题或镜像源不可达                       | 配置 Docker 镜像加速器，或用 `docker save/load` 离线传输 |
| WSL2 重启后外部访问不通                  | WSL2 IP 变化                        | `wsl ip addr show eth0` 取新 IP，更新端口转发规则 |

### 日志查看

```bash
# Kafka 主服务日志
docker logs -f haisi-kafka-dev

# 主题初始化容器日志
docker logs haisi-kafka-init-topics

# 最近 100 行
docker logs --tail 100 haisi-kafka-dev
```

### 容器管理

```bash
# 查看容器状态
docker ps | grep haisi-kafka

# 重启 Kafka
docker restart haisi-kafka-dev

# 停止并删除容器（保留数据）
docker compose down

# 停止并删除容器与数据卷（彻底清理）
docker compose down -v

# 进入容器调试
docker exec -it haisi-kafka-dev bash

# 查看 kafka-net 网络中的容器
docker network inspect kafka-net
```

### 网络排查

```bash
# 查看模型容器接入的网络
docker inspect haisi_rag_models_server_dev --format '{{json .NetworkSettings.Networks}}' | python3 -m json.tool

# 确认 kafka-net 中包含 haisi-kafka-dev 与模型容器
docker network inspect kafka-net --format '{{range .Containers}}{{.Name}} {{end}}'

# 在模型容器内解析 haisi-kafka-dev
docker exec haisi_rag_models_server_dev ping -c 2 haisi-kafka-dev
```

***

## 与主项目集成

### 配置项

`params/server_config.yaml`（以及 `params/devp/`、`params/test/`、`params/prod/` 同名文件）中的 `COMM_KAFKA_ADDRESS`：

```yaml
COMM_KAFKA_ADDRESS:
  # 本地开发：切换为本地容器
  bootstrap_servers: "haisi-kafka-dev:9092"
  # 生产/测试：华为云 DMS Kafka（三节点）
  # bootstrap_servers: "192.168.0.190:9092,192.168.0.136:9092,192.168.0.28:9092"
  group_id: "model-service"
  topic_processing: "kb-document-processing"
  topic_processed: "kb-document-processed"
  consumer_workers: 4
  shutdown_timeout_ms: 7200000
  poll_max_records: 32
  doc_worker_pool_size: 16
  control_worker_pool_size: 1
  vlm_concurrency_limit: 8
  commit_strategy: "per_partition_completed"
```

由 `configs/server_loader.py` 加载并导出 `COMM_KAFKA_ADDRESS`、`COMM_KAFKA_WORKERS` 等变量，`server/comm/` 模块直接引用。

### 启用步骤

1. **启动本地 Kafka**：按本文档执行 `bash release/images/kafka/kafka-setup.sh`
2. **切换配置**：修改 `params/server_config.yaml`（或对应环境的配置文件）：
   ```yaml
   COMM_KAFKA_ADDRESS:
     bootstrap_servers: "haisi-kafka-dev:9092"
   ```
3. **重启模型服务**：在模型容器内重启 `startup.py`，启动日志应出现 Kafka 消费者启动成功字样
4. **验证消费链路**：用 `kafka-console-producer.sh` 往 `kb-document-processing` 投递测试消息，观察模型端是否消费

### 生产环境切换

生产/测试环境无需部署本地 Kafka，直接使用华为云 DMS Kafka：

```yaml
COMM_KAFKA_ADDRESS:
  bootstrap_servers: "192.168.0.190:9092,192.168.0.136:9092,192.168.0.28:9092"
  # DMS Kafka SASL_SSL 认证（按 DMS 实例凭证填写）
  sasl_ssl:
    mechanism: "PLAIN"
    username: "your-username"
    password: "your-password"
    security_protocol: "SASL_SSL"
```

> 生产环境的 topic 由 DMS 托管，分区数与本地图保持一致（processing=16, processed=8），消费者组 `model-service` 跨实例共享。

***

## 版本历史

| **版本** | **日期**     | **更新内容**                              |
| ------ | ---------- | ----------------------------------- |
| v1.0.0 | 2026-08-22 | 初始版本，bitnami/kafka:3.7 KRaft 模式，含主题自动初始化 |

***

## 相关文档

* [Docker 部署总览](../docker.md)
* [本地部署环境与命令](../env-local.md)
* [端口转发与防火墙脚本](../firewall.bat)
* [LAC 中文分词服务部署文档](../lac/LAC部署文档.md)
* 主项目配置：[params/server_config.yaml](../../../params/server_config.yaml)
* 主项目代码：[server/comm/](../../../server/comm/)
* 主项目开发容器：[docker/run_wsl_all.md](../../../docker/run_wsl_all.md)
