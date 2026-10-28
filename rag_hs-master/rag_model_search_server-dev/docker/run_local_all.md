# 本地GPU部署docker运行命令（全功能单镜像，all 模式）

## 250GPU服务器环境
## ssh账号密码
farai@192.168.80.250/xxx

## 进入项目目录
cd /data/haisi/haisi_rag_models_server

已经安装好了docker和docker-compose 方便执行部署任务。

适合开发调试、小规模部署。API + WebUI + Kafka 消费者同 Pod 运行。

> 双镜像拆分（ingest + qa）生产部署见 [run_local_ingestion.md](run_local_ingestion.md) 与 [run_local_qa.md](run_local_qa.md)。

## 0. 全功能调试镜像
```bash
# 代码路径如下，环境中已经安装好了docker和docker-compose方便执行部署任务。
cd /data/haisi/haisi_rag_models_server
# python和项目运行环境启动了对应容器
sudo docker run -itd \
  --restart always \
  --ipc=host \
  -v /data:/data \
  --name haisi_rag_models_server_dev \
  -e OPENAI_API_KEY=123456789 \
  -e TOKENIZERS_PARALLELISM=false \
  -p 30300:12345 \
  -p 30322:22 \
  -p 38501:8501 \
  -p 37861:7861 \
  --ulimit memlock=-1 \
  haisi_rag_models_server_dev:latest
```

## 1. 构建镜像

```bash
# 进入wsl环境
wsl -d Ubuntu
cd /data/haisi/haisi_rag_models_server
sudo docker build -f docker/Dockerfile -t haisi_rag_models_server:v1.1 .
# 华为云打包上传镜像用这个命令
sudo docker buildx build --provenance=false --output type=docker -f docker/Dockerfile -t haisi_rag_models_server:v1.1 .
sudo docker tag haisi_rag_models_server:v1.1 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi_rag_models_server:v1.1
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi_rag_models_server:v1.1
```


## 2. 创建容器并启动服务（全功能单镜像，all 模式）

```bash
sudo docker run -itd \
  --restart always \
  --ipc=host \
  -v /data/release:/data/release \
  --name haisi_rag_models_server \
  -e OPENAI_API_KEY=123456789 \
  -e TOKENIZERS_PARALLELISM=false \
  -e DUAL_MCP_AUTO_INDEX=0 \
  -p 40322:22 \
  -p 48501:8501 \
  -p 47861:7861 \
  --ulimit memlock=-1 \
  haisi_rag_models_server:v1.0.7

# 服务随容器启动自动运行（Dockerfile ENTRYPOINT 为 python3 startup.py -a）
# 如需进入容器调试/查看日志，见下方第 3 节
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 40322 | 22 | SSH 调试（sshd 未自动启动，需 `docker exec` 进容器后 `service ssh start`） |
| 48501 | 8501 | WebUI (Streamlit) |
| 47861 | 7861 | API Server (Uvicorn，主要对外接口) |

> 旧版 API 端口 12345 已废弃（startup.py 实际使用 `API_SERVER["port"]`=7861），不再映射。

> **安全提示**：Dockerfile 内置 root 密码 `123456`（`echo 'root:123456' | chpasswd`），仅供调试 sshd。生产部署务必修改：`docker exec -it haisi_rag_models_server bash` 后执行 `echo 'root:<强密码>' | chpasswd`；建议禁用 `PasswordAuthentication` 改用 SSH 密钥登录，且不将 22 端口暴露到公网。

## 3. 容器管理与清理

```bash
# 查看日志
docker logs -f haisi_rag_models_server --tail 300

# 进入容器调试
sudo docker exec -it haisi_rag_models_server bash

# 删除容器
docker rm -f haisi_rag_models_server

# 标记镜像版本
sudo docker tag haisi_rag_models_server:v1.1 haisi_rag_models_server:v20260911
```

## 4. Windows 防火墙与端口转发配置（可选）

> 局域网外部访问需要，与 Milvus 同一 WSL2 环境，以管理员身份运行 PowerShell。

```powershell
# 1. 查看 WSL IP（将下方的 WSL_IP 替换为实际 IP，如 172.24.104.177）
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发和防火墙放行规则
$ports = @(40300, 40322, 48501, 47861)
$wslIP = "172.24.104.177"  # 替换为实际 WSL IP
foreach ($port in $ports) {
    netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIP
    New-NetFirewallRule -DisplayName "haisi_rag_models_server $port" -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow
}

# 3. 查看转发规则
netsh interface portproxy show all

# 4. 删除转发规则（如需清理）
foreach ($port in $ports) { netsh interface portproxy delete v4tov4 listenport=$port }
```

## 5. 相关文档

- [run_local_ingestion.md](run_local_ingestion.md)：ingestion 容器（写路径）生产部署
- [run_local_qa.md](run_local_qa.md)：qa 容器（读路径 + WebUI）生产部署
- [run_wsl_all.md](run_wsl_all.md)：全功能单容器开发测试环境
