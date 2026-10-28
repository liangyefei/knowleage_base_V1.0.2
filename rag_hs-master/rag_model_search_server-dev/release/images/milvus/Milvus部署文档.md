# Milvus v2.6.9 部署文档

> 本文档记录了在 Windows + WSL2 环境下，使用 Docker Compose 部署 Milvus v2.6.9 的完整过程。

***

## 一、环境信息

| **项目**            | **详情**                         |
| ----------------- | ------------------------------ |
| **操作系统**          | Windows 11 (WSL2)              |
| **WSL 发行版**       | Ubuntu (默认)                    |
| **Milvus 版本**     | v2.6.9                         |
| **etcd 版本**       | v3.5.18                        |
| **MinIO 版本**      | RELEASE.2024-12-18T13-15-44Z   |
| **部署方式**          | Docker Compose (Standalone 模式) |
| **Milvus 宿主机 IP** | 192.168.80.250                 |
| **WSL 虚拟 IP**     | 172.24.104.177                 |
| **服务端口**          | 19530 (gRPC) / 9091 (HTTP)     |

***

## 二、前置条件

### 2.1 软件依赖

* Docker Engine 20.10.x+
* Docker Compose 2.x+
* WSL2（Windows 环境下）
* curl / telnet / nc（用于网络测试）
* Python 3.8+（可选，用于 SDK 测试）

### 2.2 端口要求

| **端口** | **用途**              | **是否对外暴露** |
| ------ | ------------------- | ---------- |
| 19530  | Milvus 主服务端口（gRPC）  | 是          |
| 9091   | Milvus 健康检查端口（HTTP） | 是          |
| 9000   | MinIO 服务端口          | 否（内部）      |
| 9001   | MinIO Web 控制台       | 否（内部）      |
| 2379   | etcd 客户端端口          | 否（内部）      |

### 2.3 硬件要求

* 内存：建议 ≥ 8GB
* 磁盘：建议 ≥ 20GB（用于存储向量数据）

***

## 三、部署步骤

### 3.1 创建部署目录

bash

```
mkdir -p /data/Deploy\ milvus
cd /data/Deploy\ milvus
```

### 3.2 下载并配置 docker-compose.yml

#### 方式一：自动下载（推荐）

bash

```
wget https://github.com/milvus-io/milvus/releases/download/v2.6.9/milvus-standalone-docker-compose.yml -O docker-compose.yml
```

#### 方式二：手动创建

在部署目录下创建 `docker-compose.yml` 文件，内容如下：

yaml

```
services:
  etcd:
    container_name: milvus-etcd
    image: quay.io/coreos/etcd:v3.5.18   # 使用 v3.5.18 版本
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/etcd:/etcd
    command: etcd -advertise-client-urls=http://etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 30s
      timeout: 20s
      retries: 3

  minio:
    container_name: milvus-minio
    image: minio/minio:RELEASE.2024-12-18T13-15-44Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    ports:
      - "9001:9001"
      - "9000:9000"
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/minio:/minio_data
    command: minio server /minio_data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3

  standalone:
    container_name: milvus-standalone
    image: milvusdb/milvus:v2.6.9
    command: ["milvus", "run", "standalone"]
    security_opt:
    - seccomp:unconfined
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
      MQ_TYPE: woodpecker
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/milvus:/var/lib/milvus
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      start_period: 90s
      timeout: 20s
      retries: 3
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      - "etcd"
      - "minio"

networks:
  default:
    name: milvus
```

> **注意**：这里 etcd 镜像版本已从 `v3.5.25` 改为 `v3.5.18`，因为 `quay.io/coreos/etcd:v3.5.25` 可能不可用。

### 3.3 拉取镜像（可选）

bash

```
# 可以提前拉取，也可以让 docker compose up 自动拉取
docker pull milvusdb/milvus:v2.6.9
docker pull quay.io/coreos/etcd:v3.5.18
docker pull minio/minio:RELEASE.2024-12-18T13-15-44Z
```

### 3.4 启动服务

bash

```
docker compose up -d
```

### 3.5 验证容器状态

bash

```
docker compose ps
```

预期输出：

text

```
NAME                IMAGE                                      STATUS
milvus-etcd         quay.io/coreos/etcd:v3.5.18                Up (healthy)
milvus-minio        minio/minio:RELEASE.2024-12-18T13-15-44Z   Up (healthy)
milvus-standalone   milvusdb/milvus:v2.6.9                     Up (healthy)
```

### 3.6 查看日志

bash

```
# 查看所有服务日志
docker compose logs

# 查看特定服务日志
docker compose logs standalone
docker compose logs etcd
docker compose logs minio
```

***

## 四、服务验证

### 4.1 健康检查

bash

```
curl http://localhost:9091/healthz
```

预期返回：`OK`

### 4.2 Python SDK 测试

bash

```
pip install pymilvus
```

python

```
from pymilvus import connections, utility

# 连接 Milvus
connections.connect(host="localhost", port="19530")

# 获取服务版本
print(f"Milvus 版本: {utility.get_server_version()}")

# 列出所有集合
print(f"集合列表: {utility.list_collections()}")
```

预期输出：

text

```
Milvus 版本: v2.6.9
集合列表: []
```

***

## 五、网络配置

### 5.1 背景说明

Milvus 部署在 WSL2 中。WSL2 默认使用 NAT 网络，有自己的虚拟 IP（如 `172.24.104.177`），与 Windows 宿主机（`192.168.80.250`）不在同一网段。因此，从外部访问 Milvus 需要配置端口转发。

### 5.2 连接地址对照

| **访问场景**                   | **连接地址**               | **说明**  |
| -------------------------- | ---------------------- | ------- |
| Windows 本机访问 WSL 内的 Milvus | `localhost:19530`      | 可直接访问   |
| WSL 内部访问 Milvus            | `localhost:19530`      | 可直接访问   |
| 局域网其他电脑访问                  | `192.168.80.250:19530` | 需配置端口转发 |

### 5.3 端口转发配置（外部访问需要）

以**管理员身份**打开 PowerShell，执行以下命令：

powershell

```
# 1. 查看 WSL IP
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发规则
netsh interface portproxy add v4tov4 listenport=19530 listenaddress=0.0.0.0 connectport=19530 connectaddress=172.24.104.177

netsh interface portproxy add v4tov4 listenport=9091 listenaddress=0.0.0.0 connectport=9091 connectaddress=172.24.104.177

# 3. 查看转发规则
netsh interface portproxy show all

# 4. Windows 防火墙放行端口
New-NetFirewallRule -DisplayName "Milvus 19530" -Direction Inbound -LocalPort 19530 -Protocol TCP -Action Allow

New-NetFirewallRule -DisplayName "Milvus 9091" -Direction Inbound -LocalPort 9091 -Protocol TCP -Action Allow
```

### 5.4 验证外部访问

在局域网其他电脑上执行：

bash

```
curl http://192.168.80.250:9091/healthz
```

预期返回：`OK`

### 5.5 管理端口转发规则

powershell

```
# 查看所有规则
netsh interface portproxy show all

# 删除规则
netsh interface portproxy delete v4tov4 listenport=19530
netsh interface portproxy delete v4tov4 listenport=9091
```

> **注意**：如果 WSL 重启后 IP 发生变化（如从 `172.24.104.177` 变为 `172.24.104.178`），需要删除旧规则并用新 IP 重新添加。

***

## 六、数据持久化

数据存储在 `volumes/` 目录下：

text

```
./volumes/
├── etcd/      # etcd 元数据
├── minio/     # 向量数据（MinIO 对象存储）
└── milvus/    # Milvus 索引文件
```

删除容器不会影响这些数据。如需彻底清理：

bash

```
docker compose down -v   # 删除容器并清除数据卷
```

***

## 七、常用运维命令

| **操作** | **命令**                                        |
| ------ | --------------------------------------------- |
| 启动服务   | `docker compose up -d`                        |
| 停止服务   | `docker compose down`                         |
| 重启服务   | `docker compose restart`                      |
| 查看状态   | `docker compose ps`                           |
| 查看日志   | `docker compose logs -f standalone`           |
| 进入容器   | `docker exec -it milvus-standalone /bin/bash` |
| 清理数据   | `docker compose down -v`                      |

***

## 八、客户端连接配置

### 8.1 Python SDK

python

```
from pymilvus import connections

# Windows 本机访问
connections.connect(host="localhost", port="19530")

# 局域网其他电脑访问
connections.connect(host="192.168.80.250", port="19530")
```

### 8.2 Java SDK

java

```
ConnectConfig connectConfig = ConnectConfig.builder()
        .uri("http://192.168.80.250:19530")
        .token("root:Milvus")
        .build();
```

### 8.3 Go SDK

go

```
client, err := milvusclient.NewClient(ctx, &milvusclient.ClientConfig{
    Address: "192.168.80.250:19530",
})
```

### 8.4 Node.js SDK

javascript

```
const { MilvusClient } = require('@zilliz/milvus2-sdk-node');
const client = new MilvusClient({
    address: '192.168.80.250:19530'
});
```

### 8.5 RESTful API

bash

```
# 健康检查
curl http://192.168.80.250:9091/healthz

# 获取版本
curl -X GET "http://192.168.80.250:19530/v1/vector/collections"
```

### 8.6 默认认证信息

| **参数** | **值**         |
| ------ | ------------- |
| 用户名    | `root`        |
| 密码     | `Milvus`      |
| Token  | `root:Milvus` |

***

## 九、故障排查

### 9.1 容器无法启动

bash

```
# 查看详细日志
docker compose logs

# 检查端口占用
netstat -tlnp | grep -E "19530|9091|9000|9001"
```

### 9.2 外部无法访问

1. 检查 WSL IP 是否变化：bash

```
wsl ip addr show eth0 | grep inet
```

2. 检查端口转发规则：powershell

```
netsh interface portproxy show all
```

3. 检查 Windows 防火墙：powershell

```
# 查看入站规则
Get-NetFirewallRule -DisplayName "Milvus*" | Format-Table
```

4. 在 WSL 内部验证服务：bash

```
curl http://localhost:9091/healthz
```

### 9.3 常见问题

| **问题**                        | **解决方案**                     |
| ----------------------------- | ---------------------------- |
| `curl: (7) Failed to connect` | 检查 WSL IP 和端口转发              |
| `Permission denied`           | 以管理员身份运行 PowerShell          |
| 镜像拉取超时                        | 配置 Docker 镜像加速器              |
| 容器状态 `unhealthy`              | 等待 `start_period: 90s` 或查看日志 |

***

## 十、文件清单

text

```
/data/Deploy milvus/
├── docker-compose.yml          # Docker Compose 配置文件
├── volumes/                    # 数据持久化目录
│   ├── etcd/                   # etcd 数据
│   ├── minio/                  # MinIO 数据
│   └── milvus/                 # Milvus 数据
└── README.md                   # 本文档
```

***

## 十一、参考资料

* [Milvus 官方文档](https://milvus.io/docs)
* [Milvus Docker Compose 部署指南](https://github.com/milvus-io/milvus/releases)
* [WSL 网络配置](https://learn.microsoft.com/zh-cn/windows/wsl/networking)

***

## 十二、版本记录

| **日期**     | **版本** | **变更说明**                 | **作者** |
| ---------- | ------ | ------------------------ | ------ |
| 2026-07-30 | v1.0   | 初始版本，完成 Milvus v2.6.9 部署 | -      |

***

> **文档结束**。如有问题，请联系项目负责人或查阅官方文档。
