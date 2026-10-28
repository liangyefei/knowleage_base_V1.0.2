# LAC 中文分词服务部署文档

## 📋 目录

* 概述
* 环境要求（含模型与离线说明）
* 项目结构
* 文件内容
* 部署步骤（含离线部署）
* 接口说明
* 测试指南
* 故障排查
* 性能优化

***

## 概述

LAC（Lexical Analysis of Chinese）是百度开源的中文分词与词性标注工具，基于 PaddlePaddle 深度学习框架，能够同时完成分词、词性标注、实体识别等任务。

本项目将 LAC 封装为 HTTP 微服务，供 HaiSI RAG 知识库检索流程调用，主要用于对用户查询（Query）进行关键词抽取（LOC / PER / ORG / n / v / nz / nw / vn / TIME 等词性），用于增强检索召回。

**典型应用场景**：

* 知识库检索前的查询关键词抽取
* 中文文本的分词预处理
* 命名实体识别（地名、人名、机构名等）

**与主项目的关系**：

主项目 `haisi_rag_models_server` 通过 `server/chat/pipeline/lac_keywords.py` 以异步 HTTP（httpx.AsyncClient）调用本微服务（`_extract_keywords_lac`），用于 multi chat 路由阶段抽取查询关键词。主项目镜像无需安装 paddlepaddle / paddlenlp / LAC。

| **项**       | **说明**                              |
| ----------- | ----------------------------------- |
| 服务名称        | haisi-lac                           |
| 服务端口        | 5955                                |
| 基础镜像        | swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/paddlepaddle/paddle:2.6.1 |
| PaddleNLP 版本 | ≥ 2.8.1, <3.0                      |
| 任务类型        | pos_tagging（分词 + 词性标注）              |
| GPU         | 不需要（CPU 即可）                         |
| **调用方式**        | HTTP POST `/p/lac`                  |

> **当前部署位置**：本服务已部署在 `192.168.80.250:5955`，可直接调用，无需在本地重新构建镜像：
>
> ```bash
> # 健康检查
> curl http://192.168.80.250:5955/health
>
> # 分词调用
> curl -X POST http://192.168.80.250:5955/p/lac \
>     -H "Content-Type: application/json" \
>     -d '{"base_word": "百度是一家高科技公司"}'
> ```
>
> 在本地 `haisi_rag_models_server_dev` 容器（WSL Docker 默认桥接网络）内访问该地址时，
> 默认走 NAT 出口即可直达 `192.168.80.250`，无需 `--network=host`，也无需用 `172.17.0.1` 网关。
> 测试脚本：`python3 release/test/test_lac_api.py --host 192.168.80.250`

***

## 环境要求

### 硬件要求

| **组件** | **最低要求**       | **推荐配置**       |
| ------ | ------------- | ------------- |
| CPU    | x86_64，4 核    | 8 核及以上        |
| 内存     | ≥ 2GB         | ≥ 4GB         |
| 硬盘     | ≥ 5GB         | ≥ 10GB        |
| GPU    | 不需要           | 不需要           |

> LAC 模型较小（百兆级），CPU 推理即可满足在线业务延迟需求，无需 GPU。

### 软件要求

| **组件**   | **版本要求**                  | **说明**              |
| -------- | ------------------------- | ------------------- |
| 操作系统     | Linux（Ubuntu 18.04+ 推荐）   | 镜像基于 Ubuntu         |
| Docker   | ≥ 20.0                    | 支持 CPU 运行时即可        |
| 网络       | 可访问 PyPI 或清华镜像            | 离线部署需自备 LAC 离线安装包   |

### 依赖说明

| **Python 包**        | **版本**    | **说明**                 |
| ------------------- | --------- | ---------------------- |
| paddlepaddle        | 2.6.1     | 由基础镜像提供，无需单独安装         |
| paddlenlp           | ≥ 2.8.1, <3.0 | LAC 算法的 PaddleNLP 实现，提供 `Taskflow("pos_tagging")` |
| aistudio-sdk        | == 0.2.6  | paddlenlp 2.8.x 依赖；paddle 2.6.1 镜像自带的 0.3.0+ 移除了 `download` 函数，必须降到 0.2.6 |
| fastapi             | ≥ 0.104.0 | Web 框架                 |
| uvicorn[standard]  | ≥ 0.24.0  | ASGI 服务器               |
| pydantic            | ≥ 2.0.0   | 请求参数校验                 |

### 模型与离线说明

> **关键结论：本服务的 Dockerfile 已通过 `COPY lac_params/` 把 4 个 LAC 模型文件预置
> 到容器内 `/root/.paddlenlp/taskflow/lac/`，PaddleNLP `Taskflow("pos_tagging")` 启动
> 时直接命中本地 cache（MD5 校验通过），不联网下载。**

本项目用 PaddleNLP 的 `Taskflow("pos_tagging")` 而非独立的 `LAC` 包，原因：
LAC 2.1.2 源码 `import paddle.fluid as fluid`，paddle 2.6+ 已移除 fluid 模块，
在 paddle 2.6.1 镜像下 LAC 包加载必崩。PaddleNLP 的 pos_tagging 任务用自家
BiGruCrf 模型 + `paddle.inference` 预测器，不碰 `paddle.fluid`，与 paddle 2.6.x 兼容。

| **对比项**    | **`LAC` 包（已弃用）**          | **PaddleNLP `Taskflow("pos_tagging")`（本项目使用）** |
| ---------- | -------------------------------------------------- | ------------------------------------------------ |
| 模型文件位置      | 随包安装在 `site-packages/lac/` 下 | 容器内 `/root/.paddlenlp/taskflow/lac/`（由 `task_priority_path="lac"` 决定，本地路径不含 `lexical_analysis/` 前缀） |
| paddle 2.6+ 兼容 | ❌（`import paddle.fluid` 必崩） | ✅（用 `paddle.inference` 新 API） |
| 首次启动是否联网    | 不联网（模型随包）                | **默认联网下载**到 cache 目录；本服务已预置模型文件，启动时直接命中本地 cache，不联网 |
| 离线部署       | 只需 `LAC-2.1.2.tar.gz`    | 模型已随镜像打进；只需把 paddle 基础镜像 + paddlenlp 等 pip wheel 备好 |

**本地模型文件**（已放在 [lac_params/](./lac_params/) 目录，构建时 COPY 进镜像）：

| **本地文件名**        | **容器内路径**                              | **MD5**                              | **来源** |
| ----------------- | ---------------------------------------------------------------- | ------------------------------------ | --- |
| `model.pdparams`  | `/root/.paddlenlp/taskflow/lac/model_state.pdparams`（重命名） | `3d4008c6c9d29424465829c9acf909bd` | LAC 包随包模型 |
| `word.dic`        | `/root/.paddlenlp/taskflow/lac/word.dic`                    | `f1dfc68139bb6dd58c9c4313c341e436` | 同上 |
| `tag.dic`         | `/root/.paddlenlp/taskflow/lac/tag.dic`                     | `b11b616926b9f7f0a40a8087f84a8a99` | 同上 |
| `q2b.dic`         | `/root/.paddlenlp/taskflow/lac/q2b.dic`                     | `4ef2cd16f8002fe7cd7dd31cdff47e0d` | 同上 |

> 这 4 个文件就是 baidu/lac 包随包带的模型（也是 PaddleNLP 重新托管在同一份文件，
> MD5 与 [PaddleNLP 源码 `resource_files_urls`](https://github.com/PaddlePaddle/PaddleNLP/blob/v2.8.1/paddlenlp/taskflow/lexical_analysis.py) 里写的预期值完全一致）。
> 只是 `model.pdparams` 文件名与 PaddleNLP 期望的 `model_state.pdparams` 不同，
> Dockerfile 里 COPY 时重命名即可。

构建镜像需要的资源：

| **资源**                          | **获取方式**                                   |
| ------------------------------- | ------------------------------------------ |
| `paddle:2.6.1` 基础镜像 | `docker pull`（构建时自动拉取，或离线场景 `docker save/load`） |
| paddlenlp + FastAPI / uvicorn / pydantic wheels | 构建时 `pip install`（默认走清华源），离线场景用 `pip download` 打成 wheelhouse |
| LAC 模型文件 | 已在 `lac_params/` 目录，Dockerfile COPY 进镜像，无需任何下载 |

详见下方 [6. 离线部署](#6-离线部署可选)。

***

## 项目结构

```
release/images/lac/
├── LAC部署文档.md          # 本文档
├── Dockerfile            # Docker 镜像构建文件
├── docker-compose.yml    # Docker Compose 编排文件
├── requirements.txt      # Python 依赖
├── start.sh              # 服务启动脚本
├── app.py                # FastAPI 主应用
└── lac_params/           # LAC 模型文件（PaddleNLP Taskflow 预置 cache）
    ├── model.pdparams    # 模型权重（容器内重命名为 model_state.pdparams）
    ├── word.dic          # 词表
    ├── tag.dic           # 词性表
    └── q2b.dic           # 繁简转换表
```

***

## 文件内容

### 1. `Dockerfile`

```dockerfile
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/paddlepaddle/paddle:2.6.1

LABEL maintainer="haisi"
LABEL version="1.0"
LABEL description="百度 LAC 中文分词 HTTP 服务"

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=5955

# 安装系统依赖（curl 用于健康检查）
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 配置 pip 镜像源（国内加速）
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple \
    && pip config set install.trusted-host pypi.tuna.tsinghua.edu.cn

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制本地预置的 LAC 模型 cache（避免首次启动联网下载）
# PaddleNLP Taskflow 的 cache 路径：~/.paddlenlp/taskflow/lac/
# （由 taskflow.py 里 task_priority_path="lac" 决定，不是 pos_tagging/lac/ 也不是 lexical_analysis/lac/）
# 注意：lac_params/model.pdparams 需重命名为 PaddleNLP 期望的 model_state.pdparams
COPY lac_params/model.pdparams /root/.paddlenlp/taskflow/lac/model_state.pdparams
COPY lac_params/word.dic       /root/.paddlenlp/taskflow/lac/word.dic
COPY lac_params/tag.dic        /root/.paddlenlp/taskflow/lac/tag.dic
COPY lac_params/q2b.dic        /root/.paddlenlp/taskflow/lac/q2b.dic

# 复制应用代码
COPY app.py .
COPY start.sh .

RUN chmod +x start.sh

EXPOSE 5955

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:5955/health || exit 1

CMD ["./start.sh"]
```

### 2. `docker-compose.yml`

```yaml
version: '3.8'

services:
  lac:
    build: .
    container_name: haisi-lac
    ports:
      - "5955:5955"
    environment:
      - PORT=5955
    restart: unless-stopped
```

### 3. `requirements.txt`

```
paddlenlp>=2.8.1,<3.0
aistudio-sdk==0.2.6
fastapi>=0.104.0
uvicorn[standard]>=0.24.0
pydantic>=2.0.0
```

> ⚠️ `aistudio-sdk==0.2.6` 是关键锁定。paddle 2.6.1 镜像自带 0.3.0+ 版本，移除了 `download` 函数，导致 paddlenlp 2.8.x 在 `from aistudio_sdk.hub import download` 时报 `ImportError`。详见 [PaddleNLP#10781](https://github.com/PaddlePaddle/PaddleNLP/issues/10781)。

### 4. `start.sh`

```bash
#!/bin/bash

echo "=========================================="
echo "LAC 中文分词服务 Starting..."
echo "=========================================="
echo "Port: ${PORT:-5955}"
echo "Mode: pos_tagging (分词 + 词性标注)"
echo "=========================================="

exec python -m uvicorn app:app --host 0.0.0.0 --port ${PORT:-5955} --workers 1
```

### 5. `app.py`

```python
"""LAC 中文分词 HTTP 服务

基于 PaddleNLP Taskflow "pos_tagging"（百度 LAC 算法的 PaddleNLP 实现）提供
中文分词与词性标注能力。供 HaiSI RAG 知识库检索流程调用，用于用户查询的关键词抽取。

为何不用 LAC 包：LAC 2.1.2 源码 `import paddle.fluid as fluid`，而 paddle 2.6+
已移除 fluid 模块，导致 LAC 包在 paddle 2.6.1 镜像下无法加载。PaddleNLP 的
pos_tagging 任务用自家 BiGruCrf 模型 + paddle.inference 预测器，不依赖 LAC 包。

接口契约（与历史 flask_service.py 保持一致）：
    POST /p/lac
    请求: {"base_word": "待分词文本"}
    响应: {"output": [[词列表], [词性列表]]}
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import logging
import os

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="LAC 中文分词服务",
    description="基于 PaddleNLP Taskflow pos_tagging 的中文分词 HTTP API",
    version="2.0.0"
)

# 全局单例
_lac = None


def get_lac():
    """获取 PaddleNLP Taskflow 实例（懒加载，首次调用时加载模型）

    使用 pos_tagging 任务（LAC 算法的 PaddleNLP 实现），避免 LAC 包对
    paddle.fluid 的依赖（paddle 2.6+ 已移除 fluid 模块）。

    首次调用会从 https://bj.bcebos.com/paddlenlp/taskflow/lexical_analysis/lac/
    下载模型到 ~/.paddlenlp/taskflow/lac/（本地路径由 task_priority_path="lac"
    决定，不含任务名前缀）。本服务的 Dockerfile 已通过 COPY lac_params/ 将
    4 个模型文件预置到该路径，启动时直接加载，不联网。
    """
    global _lac
    if _lac is None:
        logger.info("Loading PaddleNLP Taskflow (pos_tagging)...")
        from paddlenlp import Taskflow
        _lac = Taskflow("pos_tagging")
        logger.info("PaddleNLP Taskflow (pos_tagging) loaded")
    return _lac


class LacRequest(BaseModel):
    base_word: str = Field(..., description="待分词的中文文本")


@app.get("/health")
async def health_check():
    """健康检查端点"""
    return {
        "status": "healthy" if _lac is not None else "loading",
        "model_loaded": _lac is not None,
        "task": "pos_tagging"
    }


@app.post("/p/lac")
async def lac_segment(req: LacRequest):
    """LAC 分词接口

    请求体: {"base_word": "待分词文本"}
    响应体: {"output": [[词列表], [词性列表]]}

    Taskflow("pos_tagging") 对单条输入返回 [('word', 'tag'), ...] 元组列表，
    这里转为 [[词列表], [词性列表]] 以对齐主项目 lac_service.py 的契约。
    """
    # 空文本短路：PaddleNLP Taskflow 不接受空输入（会抛 "input text should not be empty"），
    # 直接返回 [[], []] 以对齐契约，避免 500
    if not req.base_word:
        return {"output": [[], []]}

    try:
        lac = get_lac()
        result = lac(req.base_word)
        if result:
            words, tags = zip(*result)
            return {"output": [list(words), list(tags)]}
        return {"output": [[], []]}
    except Exception as e:
        logger.error(f"LAC 处理失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.on_event("startup")
async def startup_event():
    """应用启动时预加载模型，避免首次请求卡顿"""
    try:
        get_lac()
    except Exception as e:
        logger.error(f"启动时加载 PaddleNLP Taskflow 失败: {e}")


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 5955))
    uvicorn.run(app, host="0.0.0.0", port=port)
```

***

## 部署步骤

### 1. 环境准备

确保目标机器已安装 Docker（≥ 20.0）与 docker-compose。

```bash
# 验证 Docker
docker --version
docker-compose --version
```

### 2. 进入部署目录

```bash
cd /data/haisi/haisi_rag_models_server/release/images/lac
```

### 3. 构建镜像

```bash
docker build -t haisi-lac:1.0 .
```

> 首次构建会拉取 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/paddlepaddle/paddle:2.6.1` 基础镜像（约 1.5GB），请保证网络通畅或提前 `docker pull` 备好。

### 4. 运行容器

**方式一：docker-compose（推荐）**

```bash
docker-compose up -d
```

**方式二：docker run**

```bash
docker run -d \
    --name haisi-lac \
    -p 5955:5955 \
    -e PORT=5955 \
    --restart unless-stopped \
    haisi-lac:1.0
```

### 4.1 Windows WSL2 部署补充：暴露端口到局域网

> 本服务的部署形态与 [Milvus 部署文档](../milvus/Milvus部署文档.md) 一致：Docker 跑在 Windows 主机 `192.168.80.250` 的 WSL2 中，**共享同一个 WSL2 实例和虚拟 IP `172.24.104.177`**。因此端口转发和防火墙规则可参照 Milvus 文档的 5.3 节一并配置。

#### 4.1.1 背景说明

WSL2 默认使用 NAT 网络，有自己的虚拟 IP（`172.24.104.177`），与 Windows 宿主机（`192.168.80.250`）不在同一网段。WSL2 内部监听的端口默认只对 Windows 本机的 `localhost` 可达，**局域网其他机器访问 `192.168.80.250:5955` 会 Connection refused**。从外部访问需要配置端口转发。

#### 4.1.2 连接地址对照

| **访问场景**                    | **连接地址**               | **说明**            |
| ----------------------------- | ---------------------- | ----------------- |
| Windows 本机访问 WSL 内的 LAC     | `localhost:5955`      | 可直接访问（WSL2 自带 localhost forwarding） |
| WSL 内部访问 LAC                 | `localhost:5955`      | 可直接访问             |
| 局域网其他电脑访问（含 dev 容器）          | `192.168.80.250:5955` | 需配置端口转发 + 防火墙放行   |

#### 4.1.3 端口转发配置（外部访问需要）

以**管理员身份**打开 PowerShell，执行以下命令：

```powershell
# 1. 查看 WSL IP（与 Milvus 同 WSL，应为 172.24.104.177；若 WSL 重启后 IP 变化需更新规则）
wsl ip addr show eth0 | grep inet

# 2. 添加端口转发规则（把 Windows 0.0.0.0:5955 转发到 WSL2 的 5955）
netsh interface portproxy add v4tov4 listenport=5955 listenaddress=0.0.0.0 connectport=5955 connectaddress=172.24.104.177

# 3. 查看转发规则
netsh interface portproxy show all

# 4. Windows 防火墙放行端口
New-NetFirewallRule -DisplayName "LAC 5955" -Direction Inbound -LocalPort 5955 -Protocol TCP -Action Allow
```

> 若防火墙规则已建（如通过 `New-NetFirewallRule -DisplayName "LAC 5955" ...` 执行过），PowerShell 会报 `One or more firewall rules already exist`，可忽略，或先 `Remove-NetFirewallRule -DisplayName "LAC 5955"` 再重建。

#### 4.1.4 验证外部访问

在局域网其他电脑或 dev 容器中执行：

```bash
# 健康检查（应返回 {"status":"healthy","model_loaded":true,"task":"pos_tagging"}）
curl http://192.168.80.250:5955/health

# 分词调用
curl -X POST http://192.168.80.250:5955/p/lac \
    -H "Content-Type: application/json" \
    -d '{"base_word": "百度是一家高科技公司"}'
```

#### 4.1.5 管理端口转发规则

```powershell
# 查看所有规则
netsh interface portproxy show all

# 删除规则
netsh interface portproxy delete v4tov4 listenport=5955 listenaddress=0.0.0.0

# 查看防火墙规则
Get-NetFirewallRule -DisplayName "LAC*" | Format-Table

# 删除防火墙规则
Remove-NetFirewallRule -DisplayName "LAC 5955"
```

> **注意**：WSL 重启后 IP 可能变化（如从 `172.24.104.177` 变为 `172.24.104.178`），需删除旧规则并用新 IP 重新添加。若 LAC 与 Milvus 部署在同一 WSL，IP 变化时两个服务的转发规则都要更新。

#### 4.1.6 故障排查

若外部仍访问不通，按以下顺序排查：

1. **WSL 内部服务是否正常**：在 105 的 WSL 里 `curl http://localhost:5955/health` 应返回 200。
2. **Windows 本机 localhost 转发**：在 105 的 PowerShell 里 `curl http://127.0.0.1:5955/health` 应返回 200（若不通，说明容器没起来或 docker 端口映射漏了 `-p 5955:5955`）。
3. **端口转发规则**：`netsh interface portproxy show all` 确认 `5955` 转发到 `172.24.104.177`。
4. **防火墙规则**：`Get-NetFirewallRule -DisplayName "LAC*"` 确认 `LAC 5955` 规则存在且 `Action` 为 `Allow`、`Enabled` 为 `True`。
5. **Docker 端口绑到 0.0.0.0**：在 WSL 里 `ss -tlnp | grep 5955` 应看到 `0.0.0.0:5955` 或 `*:5955`，不能是 `127.0.0.1:5955`。若只绑 127.0.0.1，检查 `docker-compose.yml` 是否漏了 `ports: - "5955:5955"`，或 `/etc/docker/daemon.json` 是否误设了 `"iptables": false`（若有需删掉并重启 docker）。

### 5. 验证部署

```bash
# 查看容器状态
docker ps | grep haisi-lac

# 查看启动日志（等待 "PaddleNLP Taskflow (pos_tagging) loaded" 出现）
docker logs -f haisi-lac

# 健康检查
curl http://127.0.0.1:5955/health
```

预期响应：

```json
{
  "status": "healthy",
  "model_loaded": true,
  "task": "pos_tagging"
}
```

### 6. 离线部署（可选）

网络不通时，按以下步骤离线部署。需要准备两类资源：**基础镜像**、**Python 依赖 wheelhouse**。LAC 模型文件已在 `lac_params/` 目录，Dockerfile 会直接 COPY 进镜像，无需额外处理。

> ℹ️ 模型预置说明：本服务的 Dockerfile 已通过 `COPY lac_params/` 把 4 个模型文件预置到容器内 `/root/.paddlenlp/taskflow/lac/`，PaddleNLP Taskflow 启动时命中本地 cache（MD5 校验通过），**不联网下载**。离线部署只需关心 paddle 基础镜像和 pip 依赖。

#### 步骤 1：在联网机器上准备离线资源

```bash
# 1.1 拉取并打包基础镜像
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/paddlepaddle/paddle:2.6.1
docker save -o paddle-2.6.1.tar swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/paddlepaddle/paddle:2.6.1

# 1.2 下载 Python 依赖 wheelhouse（paddlenlp + FastAPI / uvicorn / pydantic 及其依赖）
mkdir -p wheelhouse
pip download \
    -r requirements.txt \
    -d wheelhouse/ \
    --platform manylinux2014_x86_64 \
    --python-version 3.10 \
    --only-binary=:all:
# paddlenlp 是纯 Python wheel，可以 --only-binary；若个别依赖只有源码包，
# 去掉 --only-binary=:all: 允许源码包下载。
```

#### 步骤 2：将离线资源拷贝到目标机器

将以下文件/目录拷贝到目标机器的部署目录（`release/images/lac/`）下：

```
release/images/lac/
├── paddle-2.6.1.tar          # 基础镜像
├── wheelhouse/                # Python 依赖 wheelhouse
│   ├── paddlenlp-*.whl
│   ├── fastapi-*.whl
│   ├── uvicorn-*.whl
│   ├── pydantic-*.whl
│   └── ...
├── Dockerfile                 # 修改后见步骤 4
├── app.py
├── start.sh
├── requirements.txt
└── lac_params/                # 模型文件（已在仓库里，Dockerfile 直接 COPY）
    ├── model.pdparams
    ├── word.dic
    ├── tag.dic
    └── q2b.dic
```

#### 步骤 3：在目标机器加载基础镜像

```bash
docker load -i paddle-2.6.1.tar
```

#### 步骤 4：修改 Dockerfile 改为本地安装

将 `Dockerfile` 中的依赖安装段（`COPY requirements.txt .` + `pip install -r requirements.txt` 两行）替换为本地 wheelhouse 安装：

```dockerfile
# 装本地 wheelhouse（paddlenlp + FastAPI / uvicorn / pydantic 等）
COPY wheelhouse/ /tmp/wheelhouse/
RUN pip install --no-cache-dir --no-index --find-links=/tmp/wheelhouse/ \
    "paddlenlp>=2.8.1,<3.0" fastapi uvicorn[standard] pydantic
```

> `COPY lac_params/` 那 4 行**保留不动**——模型预置与 pip 依赖安装相互独立，离线部署无需修改模型 COPY 段。

#### 步骤 5：构建并运行

```bash
docker build -t haisi-lac:1.0 .
docker-compose up -d
```

#### 步骤 6：验证离线可用性

```bash
# 启动日志应出现 "PaddleNLP Taskflow (pos_tagging) loaded"（无任何网络下载报错）
docker logs -f haisi-lac

# 健康检查
curl http://127.0.0.1:5955/health

# 分词测试
curl -X POST http://127.0.0.1:5955/p/lac \
    -H "Content-Type: application/json" \
    -d '{"base_word": "百度是一家高科技公司"}'
```

如果 `docker logs` 里没有任何 `download` / `ConnectionError` / `URLError` 字样，且分词接口返回正常结果，说明离线部署成功。

***

## 接口说明

### 1. 健康检查

**请求**：

```bash
curl http://127.0.0.1:5955/health
```

**响应**：

```json
{
  "status": "healthy",
  "model_loaded": true,
  "task": "pos_tagging"
}
```

### 2. 分词接口

**请求**：

```bash
curl -X POST http://127.0.0.1:5955/p/lac \
    -H "Content-Type: application/json" \
    -d '{"base_word": "百度是一家高科技公司"}'
```

**参数说明**：

| **参数**       | **类型** | **必填** | **说明**     |
| ------------ | ------ | ------ | ---------- |
| `base_word`  | string | ✅      | 待分词的中文文本 |

**响应**：

```json
{
  "output": [
    ["百度", "是", "一家", "高科技", "公司"],
    ["ORG", "v", "m", "n", "n"]
  ]
}
```

**响应结构**：

* `output[0]`：分词列表
* `output[1]`：词性列表（与分词一一对应）

**LAC 词性标签对照表**：

| 标签    | 含义     | 示例                |
| ------ | ------ | ----------------- |
| `LOC`  | 地名     | "北京"、"海思"         |
| `PER`  | 人名     | "张三"              |
| `ORG`  | 机构名    | "华为"、"工信部"        |
| `n`    | 普通名词   | "芯片"、"启动"         |
| `v`    | 动词     | "运行"、"加载"         |
| `nz`   | 专有名词   | "Hi3798"          |
| `nw`   | 新词     | LAC 未登录但识别出的词 |
| `vn`   | 动名词    | "开发"、"测试"         |
| `TIME` | 时间     | "2024年"、"昨天"      |
| `m`    | 数量词    | "一家"、"300"        |

***

## 测试指南

### 1. 使用 curl 测试

```bash
# 简单文本
curl -X POST http://127.0.0.1:5955/p/lac \
    -H "Content-Type: application/json" \
    -d '{"base_word": "海思半导体研发新一代芯片"}'

# 含实体的文本
curl -X POST http://127.0.0.1:5955/p/lac \
    -H "Content-Type: application/json" \
    -d '{"base_word": "华为海思于2024年发布Hi3798芯片"}'
```

### 2. 使用 Python 测试

```python
import requests

url = "http://127.0.0.1:5955/p/lac"
data = {"base_word": "百度是一家高科技公司"}

response = requests.post(url, json=data)
print(f"Status: {response.status_code}")
print(f"Result: {response.json()}")
```

预期输出：

```
Status: 200
Result: {'output': [['百度', '是', '一家', '高科技', '公司'], ['ORG', 'v', 'm', 'n', 'n']]}
```

### 3. 批量测试脚本

```python
import requests
import time

url = "http://127.0.0.1:5955/p/lac"

test_cases = [
    "海思半导体研发新一代芯片",
    "华为海思于2024年发布Hi3798芯片",
    "工信部发布2024年半导体产业发展规划",
    "张三在北京的研发中心负责芯片测试",
]

for text in test_cases:
    start = time.time()
    response = requests.post(url, json={"base_word": text})
    elapsed = time.time() - start
    output = response.json()["output"]
    print(f"Input : {text}")
    print(f"Words : {output[0]}")
    print(f"Tags  : {output[1]}")
    print(f"Time  : {elapsed * 1000:.1f} ms")
    print("-" * 40)
```

***

## 故障排查

### 常见问题及解决方案

| **问题**              | **可能原因**                       | **解决方案**                       |
| ------------------- | ------------------------------ | ------------------------------ |
| 基础镜像拉取失败             | 无法访问华为云镜像源 | 配置 Docker 镜像加速器，或用离线部署方式        |
| `pip install paddlenlp` 失败 | 网络问题或 Python 版本太低 | 确认 Python ≥ 3.8；用清华 pip 源；或用离线 wheelhouse |
| 容器启动后立即退出            | 模型加载失败或缺包               | `docker logs haisi-lac` 查看错误栈    |
| 健康检查 `model_loaded: false` | startup 加载失败                   | 查看日志，确认 paddlenlp 安装正确、模型 cache 是否到位 |
| 启动时卡在 "Loading..."      | 首次启动正在下载模型（约 30MB）    | 等待下载完成；离线环境需预先备好模型 cache 目录 |
| `cannot import name 'download' from 'aistudio_sdk.hub'` | paddle 2.6.1 镜像自带的 `aistudio_sdk` 0.3.0+ 移除了 `download` 函数 | `pip install aistudio-sdk==0.2.6` 降级（已写进 requirements.txt，重建镜像即可） |
| `ModuleNotFoundError: No module named 'paddle.fluid'` | 误装了 LAC 包（与 paddle 2.6+ 不兼容） | 不要装 LAC 包，本服务用 PaddleNLP Taskflow |
| 请求 500 错误            | 输入文本为空或非字符串                    | 校验 `base_word` 字段类型            |
| 首次请求很慢               | startup 预加载未完成                 | 等待日志出现 "PaddleNLP Taskflow (pos_tagging) loaded" 后再发请求 |
| 端口被占用                | 宿主 5955 已被其他进程占用               | 修改 `docker-compose.yml` 端口映射    |
| 从局域网访问 Windows 主机 WSL2 的 5955 不通（Connection refused），但 Windows 本机 `curl 127.0.0.1:5955` 通 | Docker 跑在 WSL2 中，端口默认只对 Windows localhost 可达，未暴露到局域网 | 见 [4.1 Windows WSL2 部署补充](#41-windows-wsl2-部署补充暴露端口到局域网)：`netsh portproxy add v4tov4` 把 Windows 0.0.0.0:5955 转发到 WSL2 IP，再 `New-NetFirewallRule` 开放 5955 |

### 日志查看

```bash
# 实时日志
docker logs -f haisi-lac

# 最近 100 行
docker logs --tail 100 haisi-lac

# 带时间戳
docker logs -t haisi-lac
```

### 容器管理

```bash
# 查看容器状态
docker ps | grep haisi-lac

# 重启容器
docker restart haisi-lac

# 停止容器
docker stop haisi-lac

# 删除容器
docker rm -f haisi-lac

# 进入容器调试
docker exec -it haisi-lac bash
```

***

## 性能优化

### 1. 并发与进程模型

| **参数**              | **推荐值** | **说明**                            |
| ------------------- | ------- | --------------------------------- |
| `--workers`         | 1       | LAC 模型单例，多 worker 会重复加载模型，浪费内存   |
| `--timeout-keepalive` | 300     | 保持长连接                            |
| `--limit-max-requests` | 1000    | 防止长期累积导致的内存泄漏（可选）                |

### 2. 模型预加载

本服务在 FastAPI startup 事件中预加载 PaddleNLP Taskflow 模型，避免首次请求卡顿。若不需要预加载，可注释 `app.py` 中的 `@app.on_event("startup")` 块。

### 3. 批量分词

PaddleNLP Taskflow 本身支持批量输入，如需扩展批量接口，可在 `app.py` 中新增：

```python
from typing import List

class LacBatchRequest(BaseModel):
    base_words: List[str] = Field(..., description="待分词的文本列表")

@app.post("/p/lac/batch")
async def lac_batch(req: LacBatchRequest):
    lac = get_lac()
    # 批量输入返回 [[(word, tag), ...], ...]
    results = lac(req.base_words)
    output = []
    for result in results:
        if result:
            words, tags = zip(*result)
            output.append([list(words), list(tags)])
        else:
            output.append([[], []])
    return {"output": output}
```

***

## 与主项目集成

### 调用方式（已实现）

主项目 `server/chat/pipeline/lac_keywords.py` 通过异步 HTTP（httpx.AsyncClient）调用本微服务，用于 multi chat 路由阶段抽取查询关键词：

```python
# lac_keywords.py 关键逻辑
async def _extract_keywords_lac(query: str) -> list[str]:
    # POST {LAC_SERVICE_BASE_URL}/p/lac
    # 请求 {"base_word": query}，响应 {"output": [[词列表],[词性列表]]}
    # 按词性（名词/专名/动名词/英文/数词）过滤后返回关键词列表
    ...
```

由 `server/chat/multi_knowledge_base_chat.py` 路由阶段调用，失败时降级回退 LLM 关键词提取。

> 早期同步实现 `server/paddle_service/lac_service.py`（requests + 健康检查）已废弃并归档至 [lac_service_archived.md](lac_service_archived.md)。

### 配置项

`params/server_config.yaml` 末尾：

```yaml
# ==================== LAC 分词服务配置 ====================
LAC_SERVICE_BASE_URL: "http://127.0.0.1:5955"
```

由 `configs/server_loader.py` 导出 `LAC_SERVICE_BASE_URL`，`configs/__init__.py` 通过 `from .server_loader import *` 再导出，`lac_keywords.py` 直接 `from configs import LAC_SERVICE_BASE_URL` 使用。

### 启用步骤

1. 按本文档部署 LAC 微服务（`docker-compose up -d`）并验证 `curl http://<lac-host>:5955/health` 返回 200。
2. 修改 `params/server_config.yaml`：
   ```yaml
   LAC_SERVICE_BASE_URL: "http://<lac-host>:5955"
   ```
3. 重启主项目 API 服务（`lac_keywords.py` 为按需调用，无启动健康检查日志）。
4. 调用知识库聊天接口验证分词生效。

> 主项目镜像无需安装 paddlepaddle / paddlenlp，可移除主 Dockerfile 中的 `pip3 install LAC` 行。

***

## 版本历史

| **版本** | **日期**     | **更新内容**                  |
| ------ | ---------- | ------------------------ |
| v1.0.0 | 2026-08-05 | 初始版本，基于 paddle 2.1.3 + LAC 2.1.2 |
| v2.0.0 | 2026-08-05 | 切换到 paddle 2.6.1 + PaddleNLP Taskflow `pos_tagging`（LAC 包与 paddle 2.6+ 不兼容）；模型文件 COPY 进镜像 |
| v2.0.1 | 2026-08-05 | 锁定 `aistudio-sdk==0.2.6`，修复 paddle 2.6.1 镜像自带 0.3.0+ 导致的 `cannot import name 'download'` 错误 |

***

## 相关文档

* [Qwen3-Embedding 模型部署文档](../embedding/Qwen3-Embedding%20模型部署文档.md)
* [Coreb-Code-Reranker 模型部署文档](../reranker/Coreb-Code-Reranker模型部署文档.md)
* 主项目代码：[server/chat/pipeline/lac_keywords.py](../../../server/chat/pipeline/lac_keywords.py)
* 归档代码：[lac_service_archived.md](lac_service_archived.md)（原同步实现，已废弃）
