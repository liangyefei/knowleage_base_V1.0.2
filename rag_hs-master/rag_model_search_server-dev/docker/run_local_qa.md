# qa 容器生产部署（读路径 + WebUI，v2.0）

适合生产 GPU 单机部署。qa 容器承载问答路由（`file_chat`/`feedback`/`list_knowledge_bases`）+ WebUI，通过 `HAISI_RUN_MODE=qa` 控制，由 `Dockerfile-qa` 的 `ENTRYPOINT ["python3", "startup_qa.py"]` 自动启动，跳过 Kafka 消费者。瘦掉解析栈和 kafka-python（22 个重包），镜像体积约 3.19GB（比 ingestion 瘦 1.84GB），秒级、低 CPU、高 QPS、多副本 + HPA。

> **前置条件**：ingestion 容器已按 [run_local_ingestion.md](run_local_ingestion.md) 启动（共享 `haisi-rag-net` 网络与 `haisi-rag-ingestion` 容器名），否则 `file_chat` 调 `upload_temp_docs` 会失败。
> 全功能单镜像部署见 [run_local_all.md](run_local_all.md)。开发测试见 [run_wsl_qa.md](run_wsl_qa.md)。

## 1. 构建镜像

```bash
# 进入wsl环境
wsl -d Ubuntu
cd /data/haisi/haisi_rag_models_server

# qa 镜像（用 requirements-qa.txt 瘦身版，去掉 unstructured/torch/kafka-python 等 22 个重包）
# 镜像体积约 3.19GB（比 ingestion 瘦 1.84GB），构建时间约 60s
sudo docker build -f docker/Dockerfile-qa -t haisi-rag-qa:v2.0 .

# 华为云打包上传
sudo docker tag haisi-rag-qa:v2.0 swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-qa:v2.0
sudo docker push swr.cn-south-1.myhuaweicloud.com/hisilicon-rag/haisi-rag-qa:v2.0
```

## 2. 启动 qa 容器

```bash
sudo docker run -itd \
  --restart always \
  --ipc=host \
  --network haisi-rag-net \
  -v /data/release:/data/release \
  --name haisi-rag-qa \
  -e OPENAI_API_KEY=123456789 \
  -e TOKENIZERS_PARALLELISM=false \
  -e INGESTION_API_URL=http://haisi-rag-ingestion:7861/kw \
  -p 48501:8501 \
  -p 47861:7861 \
  --ulimit memlock=-1 \
  haisi-rag-qa:v2.0
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 47861 | 7861 | 问答 API Server（对外；对齐 k8s qa Service 端口与生产 v1.0.6） |
| 48501 | 8501 | WebUI (Streamlit，对外) |

> qa 容器起 API（问答路由）+ WebUI，跳过 Kafka 消费者。
> `INGESTION_API_URL` 用容器名 `haisi-rag-ingestion` 访问（同 docker network 内 DNS 解析，避免 127.0.0.1 跨容器不通）。
> `file_chat` 跨 Pod：ingestion 容器写共享 Milvus `temp_{id}` collection，qa 容器直查同一 Milvus。
> 日志应出现：`HAISI_RUN_MODE=qa，跳过 KafkaCommServer 启动` + `Uvicorn running on 7861` + `Streamlit on 8501`。

> **安全提示**：Dockerfile-qa 内置 root 密码 `123456`（仅供调试 sshd，本容器未自动启动）。生产部署如需 SSH 调试，务必先 `docker exec -it haisi-rag-qa bash` 后执行 `echo 'root:<强密码>' | chpasswd`，建议改用密钥登录并禁用 `PasswordAuthentication`。

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
docker logs -f haisi-rag-qa --tail 300

# 进入容器调试
sudo docker exec -it haisi-rag-qa bash

# 删除容器
docker rm -f haisi-rag-qa

# 标记镜像版本
sudo docker tag haisi-rag-qa:v2.0 haisi-rag-qa:v20260911
```

## 5. Windows 防火墙与端口转发配置（可选）

> 局域网外部访问需要，与 Milvus 同一 WSL2 环境，以管理员身份运行 PowerShell。

```powershell
# 1. 查看 WSL IP（将下方的 WSL_IP 替换为实际 IP，如 172.24.104.177）
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发和防火墙放行规则（qa 需 47861 + 48501）
$ports = @(47861, 48501)
$wslIP = "172.24.104.177"  # 替换为实际 WSL IP
foreach ($port in $ports) {
    netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIP
    New-NetFirewallRule -DisplayName "haisi-rag-qa $port" -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow
}

# 3. 查看转发规则
netsh interface portproxy show all

# 4. 删除转发规则（如需清理）
foreach ($port in $ports) { netsh interface portproxy delete v4tov4 listenport=$port }
```

## 6. 相关文档

- [run_local_ingestion.md](run_local_ingestion.md)：ingestion 容器（写路径）生产部署，本容器的前置依赖
- [run_local_all.md](run_local_all.md)：全功能单镜像（all 模式）部署
- [run_wsl_qa.md](run_wsl_qa.md)：qa 容器开发测试环境
