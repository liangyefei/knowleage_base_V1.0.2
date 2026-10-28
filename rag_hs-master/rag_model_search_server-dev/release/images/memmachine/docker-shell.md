# MemMachine x86 容器操作命令（独立 docker 部署）

- 镜像：`haisi-memmachine:v1.0`
- 容器：`memmachine-app` / `memmachine-postgres` / `memmachine-neo4j`
- 端口：`8080`（API）/ `5432`（PG）/ `7474` `7687`（Neo4j）
- 目标主机：192.168.80.250（Windows + WSL2）
- 部署方式：三个服务用独立 `docker run` 部署，通过共享 bridge 网络 `memmachine-network` 互联（不使用 docker compose）
- 执行环境：须在 **WSL2 bash** 中执行（命令含 `[ -f ] ||` / `until...do...done` / `$(pwd)` 等 bash 语法，PowerShell 无法运行）

> 容器间通过 `--network-alias postgres` / `--network-alias neo4j` 暴露主机名，`.env` 中 `POSTGRES_HOST=postgres`、`NEO4J_HOST=neo4j` 默认值即可直接解析，无需改动。

---

## 1. 进入工作目录

```bash
cd /data/haisi/haisi_rag_models_server
```

---

## 2. 准备配置

```bash
# 2.1 生成 .env（memmachine 容器通过 --env-file 读取）
[ -f .env ] || cp release/images/memmachine/.env.example .env
# 按需修改 .env 中的 GLM_API_KEY / ALIYUN_API_KEY / 端口等

# 2.2 确保 configuration.yml 存在（容器内挂载为 /app/configuration.yml）
[ -f configuration.yml ] || cp open_source/memmachine/configuration.yml configuration.yml
```

---

## 3. 构建 x86 镜像（tag v1.0）

> ⚠️ 必须加 `--provenance=false --output type=docker`，否则 BuildKit 生成 OCI 格式 manifest，
> 推送华为云 SWR 报 `Invalid image, fail to parse 'manifest.json'`。
>
> 在项目根执行（第 1 步已 cd 到项目根），build context 用 `.`（即项目根），
> Dockerfile 通过 `-f release/images/memmachine/Dockerfile` 指定。

```bash
docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  --output type=docker \
  -f release/images/memmachine/Dockerfile \
  -t haisi-memmachine:v1.0 \
  .

# 兼容老版 Docker（无 buildx 时）：
# DOCKER_BUILDKIT=0 docker build -f release/images/memmachine/Dockerfile -t haisi-memmachine:v1.0 .
```

---

## 4. 创建网络与数据卷（一次性）

```bash
# 共享 bridge 网络（三容器通过它互通）
docker network create memmachine-network 2>/dev/null || true

# named volumes（docker rm 不丢数据）
docker volume create postgres_data        2>/dev/null || true
docker volume create neo4j_data           2>/dev/null || true
docker volume create neo4j_logs           2>/dev/null || true
docker volume create neo4j_import         2>/dev/null || true
docker volume create neo4j_plugins       2>/dev/null || true
docker volume create memmachine_logs      2>/dev/null || true
```

---

## 5. 部署 PostgreSQL（pgvector）

```bash
docker run -d --name memmachine-postgres \
  --restart unless-stopped \
  --network memmachine-network --network-alias postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=memmachine \
  -e POSTGRES_USER=memmachine \
  -e POSTGRES_PASSWORD=memmachine_password \
  -e "POSTGRES_INITDB_ARGS=--encoding=UTF-8 --lc-collate=C --lc-ctype=C" \
  -v postgres_data:/var/lib/postgresql/data \
  --health-cmd "pg_isready -U memmachine -d memmachine" \
  --health-interval 10s \
  --health-timeout 5s \
  --health-retries 5 \
  --health-start-period 30s \
  pgvector/pgvector:pg16
```

| 参数 | 说明 |
|------|------|
| `--network-alias postgres` | 在 `memmachine-network` 中主机名解析为 `postgres`（与 `.env` 的 `POSTGRES_HOST` 一致） |
| `-v postgres_data` | 持久化 PG 数据，`docker rm` 不丢 |
| `--health-cmd` 等 | 与 docker-compose 一致的健康检查（官方镜像无内置 HEALTHCHECK，需手动加，否则 §9 的 `docker inspect` 返回 `<no value>`） |
| `pgvector/pgvector:pg16` | 带 pgvector 扩展的 PostgreSQL 16（profile memory 向量存储） |

---

## 6. 部署 Neo4j

```bash
docker run -d --name memmachine-neo4j \
  --restart unless-stopped \
  --network memmachine-network --network-alias neo4j \
  -p 7474:7474 -p 7473:7473 -p 7687:7687 \
  -e NEO4J_EDITION=community \
  -e NEO4J_AUTH=neo4j/neo4j_password \
  -e NEO4J_server_bolt_thread__pool__max__size=2000 \
  -e NEO4J_server_memory_heap_initial__size=512m \
  -e NEO4J_server_memory_heap_max__size=1G \
  -e NEO4J_server_default__listen__address=0.0.0.0 \
  -e NEO4J_server_bolt_listen__address=0.0.0.0:7687 \
  -e NEO4J_server_http_listen__address=0.0.0.0:7474 \
  -e NEO4J_server_https_listen__address=0.0.0.0:7473 \
  -e 'NEO4J_PLUGINS=["apoc","graph-data-science"]' \
  -v neo4j_data:/data \
  -v neo4j_logs:/logs \
  -v neo4j_import:/var/lib/neo4j/import \
  -v neo4j_plugins:/plugins \
  --health-cmd "cypher-shell -u neo4j -p neo4j_password 'RETURN 1'" \
  --health-interval 30s \
  --health-timeout 10s \
  --health-retries 5 \
  --health-start-period 30s \
  neo4j:5.23-community
```

| 端口 | 用途 |
|------|------|
| `7474` | Neo4j HTTP（浏览器控制台） |
| `7473` | Neo4j HTTPS |
| `7687` | Bolt 协议（memmachine 通过此端口连接，与 `.env` 的 `NEO4J_PORT=7687` 一致） |

> `--health-cmd` 等健康检查参数与 docker-compose 一致（官方 neo4j 镜像无内置 HEALTHCHECK，需手动加，否则 §9 的 `docker inspect` 返回 `<no value>`）。

---

## 7. 等待依赖健康

> 必须等 PostgreSQL / Neo4j 就绪后再启动 memmachine，否则应用启动时连接 DB 失败。

```bash
# 等 PostgreSQL 就绪
until docker exec memmachine-postgres \
  pg_isready -U memmachine -d memmachine >/dev/null 2>&1; do
  echo "waiting postgres..."; sleep 3
done
echo "postgres ready"

# 等 Neo4j 就绪
until docker exec memmachine-neo4j \
  cypher-shell -u neo4j -p neo4j_password "RETURN 1" >/dev/null 2>&1; do
  echo "waiting neo4j..."; sleep 5
done
echo "neo4j ready"
```

---

## 8. 部署 MemMachine

```bash
docker run -d --name memmachine-app \
  --restart unless-stopped \
  --network memmachine-network \
  -p 8080:8080 \
  --env-file .env \
  -v "$(pwd)/configuration.yml:/app/configuration.yml:rw" \
  -v memmachine_logs:/tmp/memory_logs \
  --add-host=host.docker.internal:host-gateway \
  haisi-memmachine:v1.0
```

| 参数 | 说明 |
|------|------|
| `--env-file .env` | 注入 GLM_API_KEY / ALIYUN_API_KEY / POSTGRES_HOST / NEO4J_HOST 等全部应用环境变量 |
| `-v configuration.yml` | 挂载配置文件（`MEMORY_CONFIG=/app/configuration.yml` 指向它） |
| `-v memmachine_logs` | 日志持久化卷 |
| `--add-host` | 等价 compose 的 `extra_hosts`，容器内可解析 `host.docker.internal` |
| 不设 `--network-alias` | memmachine 不需要被其他容器按服务名访问 |

> `.env` 中 `POSTGRES_HOST=postgres`、`NEO4J_HOST=neo4j` 已通过 §5/§6 的 network-alias 解析，无需修改。

---

## 9. 验证服务

```bash
# 等 memmachine 启动（首次需初始化 DB schema，约 30-60s）
sleep 60

# 健康检查
curl -s http://127.0.0.1:8080/api/v2/health
# 局域网：curl -s http://192.168.80.250:8080/api/v2/health

# 三容器健康状态
docker inspect --format '{{.State.Health.Status}}' memmachine-postgres
docker inspect --format '{{.State.Health.Status}}' memmachine-neo4j
docker inspect --format '{{.State.Health.Status}}' memmachine-app
```

---

## 10. 查看状态与日志

```bash
# 容器列表
docker ps --filter name=memmachine- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# memmachine 应用日志
docker logs -f memmachine-app

# PostgreSQL 日志
docker logs -f memmachine-postgres

# Neo4j 日志
docker logs -f memmachine-neo4j
```

---

## 11. 停止与删除容器

```bash
# 停止（保留数据卷）
docker stop memmachine-app memmachine-postgres memmachine-neo4j

# 删除容器（数据卷保留，下次 docker run 复用 named volume 即恢复数据）
docker rm memmachine-app memmachine-postgres memmachine-neo4j
```

---

## 12. 删除数据卷（⚠️ 清空所有记忆数据）

```bash
docker volume rm postgres_data neo4j_data neo4j_logs \
  neo4j_import neo4j_plugins memmachine_logs

# 可选：删除共享网络
docker network rm memmachine-network
```

---

## 13. 删除镜像

```bash
docker rmi haisi-memmachine:v1.0
```

---

## 14. 重新构建（代码更新后）

```bash
cd /data/haisi/haisi_rag_models_server

# 1) 停掉旧容器
docker stop memmachine-app 2>/dev/null
docker rm   memmachine-app 2>/dev/null

# 2) 重新构建镜像（provenance=false 避免 SWR 推送失败；在项目根执行，context 为 .）
docker buildx build --platform linux/amd64 --provenance=false --output type=docker \
  -f release/images/memmachine/Dockerfile -t haisi-memmachine:v1.0 .

# 3) 重新部署 memmachine（postgres / neo4j 无需重启，数据卷复用）
docker run -d --name memmachine-app \
  --restart unless-stopped \
  --network memmachine-network \
  -p 8080:8080 \
  --env-file .env \
  -v "$(pwd)/configuration.yml:/app/configuration.yml:rw" \
  -v memmachine_logs:/tmp/memory_logs \
  --add-host=host.docker.internal:host-gateway \
  haisi-memmachine:v1.0

# 4) 回到第 9 步验证
```

---

## 15. 迁移到 K8s 的注意点

> 本文档的 docker 命令仅用于本地/单机验证。仓库已自带 Helm Chart（`open_source/memmachine/deployments/helm/`），上 K8s 时直接复用需注意以下差异。

### 15.1 主机名映射（network-alias → K8s Service）

| docker（本文档） | K8s Helm Service | 来源 |
|------|------|------|
| `--network-alias postgres` | Service `memmachine-postgres` | [values.yaml](open_source/memmachine/deployments/helm/values.yaml) `postgres.host` |
| `--network-alias neo4j` | Service `memmachine-neo4j` | [values.yaml](open_source/memmachine/deployments/helm/values.yaml) `neo4j.host` |

[configuration.yml](configuration.yml) 中 `host: postgres` 是硬编码（注释"docker-compose 服务名"），在 K8s 中无法解析。上云时二选一：

1. 把 `host: postgres` 改成 `host: memmachine-postgres`（推荐，与 Helm 默认对齐）；
2. 或在 K8s 里建一个名为 `postgres` 的 Service 指向 pgvector Pod。

### 15.2 现有 Helm Chart 的 configuration.yml 与本文档不同

仓库自带的 Helm Chart（[memmachine-configmaps.yaml](open_source/memmachine/deployments/helm/templates/memmachine-configmaps.yaml)）默认渲染的 configuration.yml：

- LLM/Embedder 用 **OpenAI**（`$OPENAI_API_KEY`），不是本文档的 GLM + 阿里云 DashScope；
- episodic_memory 用 `vector_graph_store: db_neo4j`（**声明式后端**），不是本文档的 `backend: event`；
- 资源 ID 用 `db_postgres` / `default_embedder` / `default_model`，不是 `profile_storage` / `aliyun_embedder` / `glm_model`。

因此**不能直接 `helm install` 复用本文档的 GLM/阿里云 configuration.yml**。迁移方案：用本文档的 [configuration.yml](configuration.yml) 覆盖 Helm 的 `memmachine-config` ConfigMap（外加 §15.1 的 host 改名），或在 `values.yaml` 里把 embedder/model 的 `provider` / `base_url` / `model` 改成 GLM/阿里云 endpoint。

### 15.3 其余 docker → K8s 映射

| docker | K8s |
|------|------|
| named volume（`postgres_data` 等） | PersistentVolumeClaim（Helm `pvc.yaml`，默认 `storageClass: nfs-client`） |
| `--env-file .env` | ConfigMap（`memmachine-env-config`）+ Secret（`secrets.yaml`，放 password / api_key） |
| `--restart unless-stopped` | Deployment `restartPolicy: Always`（由控制器自动拉起 Pod） |
| `--health-cmd` | livenessProbe / readinessProbe（Helm `*-deployment.yaml`） |
| `-p 8080:8080` | Service（ClusterIP）+ Ingress / NodePort（`nodePorts.*`） |
| `memmachine-network` bridge | 集群内 DNS（`<svc>.<ns>.svc.cluster.local`） |
