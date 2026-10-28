# Milvus Docker 命令清单

> 华为云 x86 CPU 部署。Milvus v2.6.9 + etcd v3.5.18 + MinIO。
> 镜像从官方源拉取后 retag 推送到华为云 SWR，云端再拉取运行。

## 1. 拉取镜像（官方源，本地或构建机执行）

```bash
docker pull milvusdb/milvus:v2.6.9
docker pull quay.io/coreos/etcd:v3.5.18
docker pull minio/minio:RELEASE.2024-12-18T13-15-44Z
```

## 2. 推送到华为云 SWR

```bash
# retag
docker tag milvusdb/milvus:v2.6.9 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/milvus:v2.6.9
docker tag quay.io/coreos/etcd:v3.5.18 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/etcd:v3.5.18
docker tag minio/minio:RELEASE.2024-12-18T13-15-44Z swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/minio:RELEASE.2024-12-18T13-15-44Z

# push
docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/milvus:v2.6.9
docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/etcd:v3.5.18
docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/minio:RELEASE.2024-12-18T13-15-44Z
```

## 3. 云端服务器拉取并 retag 回原名

```bash
# 拉取
docker pull swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/milvus:v2.6.9
docker pull swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/etcd:v3.5.18
docker pull swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/minio:RELEASE.2024-12-18T13-15-44Z

# retag 回官方名，便于复用官方 compose 配置
docker tag swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/milvus:v2.6.9 milvusdb/milvus:v2.6.9
docker tag swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/etcd:v3.5.18 quay.io/coreos/etcd:v3.5.18
docker tag swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/minio:RELEASE.2024-12-18T13-15-44Z minio/minio:RELEASE.2024-12-18T13-15-44Z
```

## 4. 创建数据目录与网络

```bash
mkdir -p /data/milvus/volumes/etcd /data/milvus/volumes/minio /data/milvus/volumes/milvus
docker network create milvus 2>/dev/null || true
```

## 5. docker run 运行（etcd → minio → milvus）

```bash
# 1) etcd
docker run -d --name milvus-etcd --network milvus --restart always \
  -v /data/milvus/volumes/etcd:/etcd \
  quay.io/coreos/etcd:v3.5.18 \
  etcd -advertise-client-urls=http://etcd:2379 \
        -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

# 2) minio
docker run -d --name milvus-minio --network milvus --restart always \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ACCESS_KEY=minioadmin -e MINIO_SECRET_KEY=minioadmin \
  -v /data/milvus/volumes/minio:/minio_data \
  minio/minio:RELEASE.2024-12-18T13-15-44Z \
  minio server /minio_data --console-address ":9001"

# 3) milvus standalone
docker run -d --name milvus-standalone --network milvus --restart always \
  -p 19530:19530 -p 9091:9091 \
  --security-opt seccomp:unconfined \
  -e ETCD_ENDPOINTS=etcd:2379 \
  -e MINIO_ADDRESS=minio:9000 \
  -e MQ_TYPE=woodpecker \
  -v /data/milvus/volumes/milvus:/var/lib/milvus \
  milvusdb/milvus:v2.6.9 \
  milvus run standalone
```

## 6. 验证

```bash
# 容器状态
docker ps --filter name=milvus-

# 健康检查（等待 start_period 90s）
curl http://localhost:9091/healthz
# 预期返回：OK
```

## 7. 停止与删除

```bash
# 停止
docker stop milvus-standalone milvus-minio milvus-etcd

# 删除容器（数据保留在 /data/milvus/volumes）
docker rm milvus-standalone milvus-minio milvus-etcd

# 彻底清理（含数据）
docker rm milvus-standalone milvus-minio milvus-etcd
docker network rm milvus
rm -rf /data/milvus/volumes
```

## 8. 端口说明

| 宿主端口 | 容器端口 | 用途 |
|---------|---------|------|
| 19530 | 19530 | Milvus gRPC（SDK 连接） |
| 9091 | 9091 | Milvus 健康检查（HTTP） |
| 9000 | 9000 | MinIO S3 API（内部） |
| 9001 | 9001 | MinIO Web 控制台 |
