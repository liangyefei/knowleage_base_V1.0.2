# 测试代码运行开发环境（全功能单容器）

说明：使用 Windows 上的 WSL 进入 Ubuntu 环境，然后使用 Docker 搭建开发环境并在容器中运行项目。本文件为**全功能单容器**模式（all-in-one，单 Pod），即一个容器内同时承载 API Server + WebUI + Redis + Kafka 消费者，所有路由（入库 + 问答）都在同一进程。

> 若需验证 ingestion + qa 双镜像拆分（路由分离）模式，见 [run_wsl_ingestion.md](run_wsl_ingestion.md) 与 [run_wsl_qa.md](run_wsl_qa.md)。

## 1. 构建镜像

```bash
# 进入 WSL 环境
wsl -d Ubuntu
cd /mnt/d/Code/haisi/haisi_rag_models_server
sudo docker build -f docker/Dockerfile-dev -t haisi_rag_models_server_dev:latest .
```

## 2. 创建容器

> **注意**：FastChat 已从项目中永久移除（2026-08-04），不再需要 20000/20001/20002 三个 fastchat 端口映射。容器仅启动 API Server(7861) + WebUI(8501) + Redis。

```bash
sudo docker run -itd \
  --restart always \
  --ipc=host \
  -v /mnt/d/Code:/mnt/d/Code \
  --name haisi_rag_models_server_dev \
  -e OPENAI_API_KEY=123456789 \
  -e TOKENIZERS_PARALLELISM=false \
  -p 30322:22 \
  -p 38501:8501 \
  -p 37861:7861 \
  --ulimit memlock=-1 \
  haisi_rag_models_server_dev:latest
```

端口说明：
| 宿主端口 | 容器端口 | 用途 |
|---|---|---|
| 30322 | 22 | SSH（Dockerfile-dev CMD 为 sshd -D，前台常驻） |
| 38501 | 8501 | WebUI (Streamlit) |
| 37861 | 7861 | API Server (Uvicorn，主要对外接口) |

> 旧版 API 端口 12345 已废弃（startup.py 实际使用 `API_SERVER["port"]`=7861），不再映射。

> **安全提示**：Dockerfile-dev 内置 root 密码 `123456`（`echo 'root:123456' | chpasswd`），sshd 作为主进程常驻。开发环境可接受；若容器暴露到局域网，务必先修改密码：`docker exec -it haisi_rag_models_server_dev bash` 后执行 `echo 'root:<强密码>' | chpasswd`，建议改用 SSH 密钥登录并禁用 `PasswordAuthentication`。

## 3. 进入容器并启动服务

```bash
# 进入容器
sudo docker exec -it haisi_rag_models_server_dev bash

# 进入项目目录启动服务（-a 启动 API + WebUI + Redis）
cd /mnt/d/Code/haisi/haisi_rag_models_server/
python3 startup.py -a

# 或仅启动 API 服务（用于后端调试）
python3 startup.py --api

# wsl中启动服务并记录日志
wsl -d Ubuntu -- sudo docker exec -d -w /mnt/d/Code/haisi/haisi_rag_models_server haisi_rag_models_server_dev bash -c "python3 startup.py -a > /tmp/startup_run.log 2>&1" 
```

启动成功后日志中应出现：
- `当前启动的LLM模型：['doubao-1.5-pro-32k-api'] @ cuda`
- `当前Embbedings模型： qwen3-embedding-api @ cuda`
- `Uvicorn running on http://0.0.0.0:7861`
- **不应出现** `fastchat` 或 `fschat` 相关报错

## 4. 验证 FastChat 移除后的接口

在容器内执行以下命令快速验证对外 API 接口正常：

```bash
# 验证 list_running_models 返回 doubao 模型
curl -s -X POST http://127.0.0.1:7861/kw/llm_model/list_running_models \
  -H "Content-Type: application/json" -d "{}" | python3 -m json.tool

# 验证 server/configs 中 controller_address 和 openai_api_address 为空字符串
curl -s -X POST http://127.0.0.1:7861/kw/server/configs \
  -H "Content-Type: application/json" -d "{}" | python3 -m json.tool

# 验证 stop 返回友好提示（不调用 fastchat controller）
curl -s -X POST http://127.0.0.1:7861/kw/llm_model/stop \
  -H "Content-Type: application/json" \
  -d '{"model_name":"doubao-1.5-pro-32k-api"}' | python3 -m json.tool
```

预期返回：
- `list_running_models`: `data` 中包含 `doubao-1.5-pro-32k-api`
- `server/configs`: `controller_address` 与 `openai_api_address` 均为 `""`
- `stop`: `msg` 为 `"在线 API 模型 doubao-1.5-pro-32k-api 无需停止（配置即在线）"`

## 5. 容器管理与日志

```bash
# 查看容器日志
docker logs -f haisi_rag_models_server_dev --tail 300

# 停止并删除容器
docker rm -f haisi_rag_models_server_dev

# 标记镜像版本
sudo docker tag haisi_rag_models_server_dev:latest haisi_rag_models_server_dev:v20260804
```

## 6. 命令行参数说明

FastChat 已彻底移除，`startup.py` 不再保留 FastChat 兼容参数（`--all-api`/`--llm-api`/`-o`/`-m`/`-c`/`-p` 均已删除，传入会报 `unrecognized arguments`）。

当前保留的启动参数：
- `-a / --all-webui`：启动 API + WebUI（等价 `--api --webui`）
- `--api`：仅启动 API Server
- `-w / --webui`：仅启动 WebUI
- `-n / --model-name`：指定 LLM 模型名称（用于配置加载）
- `-q / --quiet`：减少日志输出
- `-i / --lite`：Lite 模式（仅在线 API 的 LLM 对话 + 搜索）
- `-init / --init-db`：已废弃，传入会打印提示并退出（请单独运行 `python3 init_database.py`）

## 7. 防火墙与端口转发配置（可选）

> 局域网外部访问需要，与 Milvus 同一 WSL2 环境。以管理员身份运行 PowerShell。

```powershell
# 1. 查看 WSL IP（替换下方 WSL_IP 为实际值，如 172.24.104.177）
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发与防火墙放行
$ports = @(30322, 38501, 37861)
$wslIP = "172.24.104.177"  # 替换为实际 WSL IP
foreach ($port in $ports) {
    netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIP
    New-NetFirewallRule -DisplayName "haisi_rag_models_server_dev $port" -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow
}

# 3. 查看规则
netsh interface portproxy show all

# 4. 清理（如需）
foreach ($port in $ports) { netsh interface portproxy delete v4tov4 listenport=$port }
```

## 8. 相关文档

- [run_wsl_ingestion.md](run_wsl_ingestion.md)：ingestion 容器（写路径）本地验证
- [run_wsl_qa.md](run_wsl_qa.md)：qa 容器（读路径 + WebUI）本地验证
- [run_local_all.md](run_local_all.md)：生产环境双镜像部署命令
