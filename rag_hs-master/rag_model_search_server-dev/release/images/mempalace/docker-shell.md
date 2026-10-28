# MemPalace x86 容器操作命令

- 镜像：`haisi-mempalace:v1.0`（base）/ `haisi-mempalace:rest-v1.0`（REST）
- 容器：`mempalace-rest`
- 端口：`8777`
- 目标主机：192.168.80.250（Windows + WSL2）

## 1. 进入工作目录

```bash
cd /data/haisi/haisi_rag_models_server
```

## 2. 预拉取基础镜像（105 无法直连 docker.io）

```bash
docker pull --platform=linux/amd64 \
  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/debian:bookworm-slim

docker tag \
  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/debian:bookworm-slim \
  debian:bookworm-slim
```

## 3. 构建 x86 镜像（tag v1.0，两次构建）

> ⚠️ REST 镜像 `Dockerfile.rest` 里 `FROM haisi-mempalace:latest`，故 base 必须同时打 `:latest` 与 `:v1.0`。
> ⚠️ 必须加 `--provenance=false --output type=docker`，否则 BuildKit 生成 OCI manifest，推送华为云 SWR 报 `Invalid image, fail to parse 'manifest.json'`。

```bash
# 1) MCP/CLI 基础镜像
docker buildx build \
  --provenance=false \
  --output type=docker \
  -f open_source/mempalace/Dockerfile \
  -t haisi-mempalace:latest \
  -t haisi-mempalace:v1.0 \
  open_source/mempalace

# 2) REST 适配器镜像
docker buildx build \
  --provenance=false \
  --output type=docker \
  -f open_source/mempalace/Dockerfile.rest \
  -t haisi-mempalace:rest-v1.0 \
  open_source/mempalace

# 兼容老版 Docker（无 buildx 时）：
# docker build -f open_source/mempalace/Dockerfile -t haisi-mempalace:latest -t haisi-mempalace:v1.0 open_source/mempalace
# docker build -f open_source/mempalace/Dockerfile.rest -t haisi-mempalace:rest-v1.0 open_source/mempalace
```

## 4. 部署容器

### 4.1 REST 适配器（HTTP API，端口 8777，常驻服务）

```bash
# 准备配置（公网/半信任网络改用 token，见 .env.example）
[ -f release/images/mempalace/.env ] || cp release/images/mempalace/.env.example release/images/mempalace/.env

# 启动容器（可信内网免鉴权）
docker run -d \
  --name mempalace-rest \
  --restart unless-stopped \
  -p 8777:8777 \
  -e MEMPALACE_REST_HOST=0.0.0.0 \
  -e MEMPALACE_REST_NO_AUTH=1 \
  -v mempalace-data:/data \
  haisi-mempalace:rest-v1.0
```

### 4.2 MCP 基础镜像（stdio 传输，可选）

`haisi-mempalace:v1.0` 默认运行 MCP server over **stdio（JSON-RPC）**，需由 MCP 客户端（Claude Desktop / Cursor / Trae 等本地工具）通过 stdin/stdout 驱动，**不能 `-d` 后台常驻**（无客户端 attach 时 MCP server 无输入空转）。远程/网络访问请用 §4.1 REST 镜像。

```bash
# 方式一：交互式 MCP 会话（本地 MCP 客户端驱动 stdin/stdout）
docker run -i --rm \
  --name mempalace-mcp \
  -v mempalace-data:/data \
  haisi-mempalace:v1.0

# 方式二：一次性 CLI 命令（search / mine / status 等，跑完即退）
docker run --rm -v mempalace-data:/data haisi-mempalace:v1.0 search "why GraphQL"
docker run --rm -v mempalace-data:/data haisi-mempalace:v1.0 status
```

MCP 客户端配置示例（客户端的 `mcp.json`）：

```json
{
  "mcpServers": {
    "mempalace": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-v", "mempalace-data:/data", "haisi-mempalace:v1.0"]
    }
  }
}
```

> ⚠️ MCP 基础镜像与 §4.1 REST 镜像共用 `mempalace-data` 卷，同时运行会争抢 ChromaDB 文件锁，请避免并发；二选一即可。

## 5. 查看状态与日志

```bash
docker ps | grep mempalace-rest
docker logs -f mempalace-rest --tail 200
```

## 6. 验证服务

```bash
curl -s http://127.0.0.1:8777/healthz
# 局域网：curl -s http://192.168.80.250:8777/healthz
```

## 7. 停止与删除容器

```bash
docker stop mempalace-rest
docker rm -f mempalace-rest

# 清理数据卷（可选，会删除全部记忆数据）
docker volume rm mempalace-data
```

## 8. 删除镜像

```bash
docker rmi haisi-mempalace:rest-v1.0 haisi-mempalace:v1.0 haisi-mempalace:latest
```

## 9. 重新构建（代码更新后）

```bash
cd /data/haisi/haisi_rag_models_server
docker rm -f mempalace-rest 2>/dev/null
docker build -f open_source/mempalace/Dockerfile -t haisi-mempalace:latest -t haisi-mempalace:v1.0 open_source/mempalace
docker build -f open_source/mempalace/Dockerfile.rest -t haisi-mempalace:rest-v1.0 open_source/mempalace
# 再回到第 4 步部署
```
