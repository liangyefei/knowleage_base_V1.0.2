# ingestion 容器生产部署（写路径，v2.0）

适合生产 GPU 单机部署。ingestion 容器承载入库路由（`create_knowledge_base`/`upload_docs`/`upload_temp_docs`/`delete_docs`）+ Kafka 消费者，通过 `HAISI_RUN_MODE=ingest` 控制，由 `Dockerfile-ingestion` 的 `ENTRYPOINT ["python3", "startup_ingestion.py"]` 自动启动。带解析栈（unstructured + libreoffice + tree-sitter + kafka-python），分钟级任务、CPU 密集、低副本数。

> 全功能单镜像部署见 [run_local_all.md](run_local_all.md)。开发测试见 [run_wsl_ingestion.md](run_wsl_ingestion.md)。

## 1. 构建镜像

```bash
# 进入wsl环境
wsl -d Ubuntu
cd /data/haisi/haisi_rag_models_server

# ingestion 镜像（完全沿用原版 Dockerfile 环境，仅改 ENTRYPOINT + ENV HAISI_RUN_MODE=ingest）
# 前面层（FROM/LABEL/apt/requirements.txt pip/unstructured/tree-sitter/kafka-python）和原版 Dockerfile 完全一致，
# 构建时若原版镜像已存在则 step #6-#10 全部 CACHED，只重打最后一层（ENV + ENTRYPOINT）
sudo docker build -f docker/Dockerfile-ingestion -t haisi-rag-ingestion:v2.0 .

# 华为云打包上传
sudo docker tag haisi-rag-ingestion:v2.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-ingestion:v2.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-ingestion:v2.0
```

## 2. 创建网络并启动 ingestion 容器

```bash
# 1. 创建专用 docker 网络，供 qa 容器通过容器名访问 ingestion API
sudo docker network create haisi-rag-net

# 2. 启动 ingestion 容器（写路径）
sudo docker run -itd \
  --restart always \
  --ipc=host \
  --network haisi-rag-net \
  -v /data/release:/data/release \
  --name haisi-rag-ingestion \
  -e DUAL_MCP_AUTO_INDEX=0 \
  -e OPENAI_API_KEY=123456789 \
  -e TOKENIZERS_PARALLELISM=false \
  -p 47860:7861 \
  --ulimit memlock=-1 \
  haisi-rag-ingestion:v2.0
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 47860 | 7861 | 入库 API Server（qa 通过 INGESTION_API_URL 调用；对齐 k8s ingestion Service 端口） |

> ingestion 容器只起 API（入库路由）+ Kafka 消费者，**不起 WebUI，不开 8501 端口**。
> 容器启动后由 `startup_ingestion.py` 自动起服务，无需手动 `docker exec`。
> 日志应出现：`Kafka Comm Server Success (mode=ingest)` + `API server 4 workers uvloop` + `Application startup complete`。

> **安全提示**：Dockerfile-ingestion 内置 root 密码 `123456`（仅供调试 sshd，本容器未自动启动）。生产部署如需 SSH 调试，务必先 `docker exec -it haisi-rag-ingestion bash` 后执行 `echo 'root:<强密码>' | chpasswd`，建议改用密钥登录并禁用 `PasswordAuthentication`。

## 3. 路由分离说明

| 路由 | ingestion 容器 | qa 容器 |
|---|---|---|
| `/kw/knowledge_base/create_knowledge_base` | 405 ✓ | 404 |
| `/kw/knowledge_base/upload_docs` | 405 ✓ | 404 |
| `/kw/knowledge_base/upload_temp_docs` | 405 ✓ | 404 |
| `/kw/knowledge_base/delete_docs` | 405 ✓ | 404 |
| `/kw/chat/file_chat` | 404 | 405 ✓ |
| `/kw/chat/feedback` | 404 | 405 ✓ |
| `/kw/knowledge_base/list_knowledge_bases` | 404 | 200 ✓ |
| `/kw/server/configs` | 405 ✓ | 405 ✓ |

- `file_chat` 跨 Pod：ingestion 容器写共享 Milvus `temp_{id}` collection，qa 容器直查同一 Milvus。
- `ApiRequest` 双 client 路由：18 条入库路由走 `ingest_client`（INGESTION_API_URL），其余走 `client`（base_url）。单 Pod 部署（all 模式）不设 INGESTION_API_URL 时退化为 base_url，行为与拆分前一致。

## 4. 容器管理与清理

```bash
# 查看日志
docker logs -f haisi-rag-ingestion --tail 300

# 进入容器调试
sudo docker exec -it haisi-rag-ingestion bash

# 删除容器
docker rm -f haisi-rag-ingestion

# 若 qa 容器也已清理，删除共享网络
docker network rm haisi-rag-net

# 标记镜像版本
sudo docker tag haisi-rag-ingestion:v2.0 haisi-rag-ingestion:v20260911
```

## 5. Windows 防火墙与端口转发配置（可选）

> 局域网外部访问需要，与 Milvus 同一 WSL2 环境，以管理员身份运行 PowerShell。

```powershell
# 1. 查看 WSL IP（将下方的 WSL_IP 替换为实际 IP，如 172.24.104.177）
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发和防火墙放行规则（ingestion 仅需 47860）
$ports = @(47860)
$wslIP = "172.24.104.177"  # 替换为实际 WSL IP
foreach ($port in $ports) {
    netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIP
    New-NetFirewallRule -DisplayName "haisi-rag-ingestion $port" -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow
}

# 3. 查看转发规则
netsh interface portproxy show all

# 4. 删除转发规则（如需清理）
foreach ($port in $ports) { netsh interface portproxy delete v4tov4 listenport=$port }
```

## 6. 相关文档

- [run_local_qa.md](run_local_qa.md)：qa 容器（读路径 + WebUI）生产部署，需在本容器启动后运行
- [run_local_all.md](run_local_all.md)：全功能单镜像（all 模式）部署
- [run_wsl_ingestion.md](run_wsl_ingestion.md)：ingestion 容器开发测试环境
