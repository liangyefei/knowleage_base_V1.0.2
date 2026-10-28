# Coreb-Code-Reranker 模型部署文档

## 📋 目录

* 概述
* 环境要求
* 项目结构
* 文件内容
* 部署步骤
* 接口说明
* 测试指南
* 故障排查
* 性能优化

***

## 概述

HF 模型权重
https://huggingface.co/hq-bench/coreb-code-reranker
https://hf-mirror.com/hq-bench/coreb-code-reranker

GitHub 项目源码
https://github.com/hq-bench/coreb

论文 & 项目主页
https://hq-bench.github.io/coreb-page/

Coreb-Code-Reranker 是一个基于大语言模型的代码重排序服务，用于计算**查询（Query）**&#x4E0E;**文档（Document）**&#x4E4B;间的相关性分数。支持三种任务类型：

| **任务类型**       | **说明**         | **Query 类型** | **Document 类型** |
| -------------- | -------------- | ------------ | --------------- |
| `TEXT_TO_CODE` | 用自然语言描述查找代码    | 自然语言         | 代码片段            |
| `CODE_TO_CODE` | 用代码查找语义相似的代码   | 代码片段         | 代码片段            |
| `CODE_TO_TEXT` | 用代码查找对应的自然语言描述 | 代码片段         | 自然语言            |

**典型应用场景**：

* 代码搜索引擎
* 代码推荐系统
* 代码-文档匹配
* RAG 系统中的精排阶段

***

## 环境要求

### 硬件要求

| **组件** | **最低要求**                | **推荐配置**                |
| ------ | ----------------------- | ----------------------- |
| GPU    | NVIDIA GPU (CUDA 12.8+) | NVIDIA GeForce RTX 5090 |
| 显存     | ≥ 8GB                   | ≥ 16GB                  |
| 硬盘     | ≥ 20GB                  | ≥ 30GB                  |
| 内存     | ≥ 16GB                  | ≥ 32GB                  |

### 软件要求

| **组件**    | **版本要求**                   | **说明**         |
| --------- | -------------------------- | -------------- |
| 操作系统      | Windows 11 + WSL 2 / Linux | WSL 需启用 GPU 支持 |
| Docker    | ≥ 24.0                     | 支持 GPU 运行时     |
| NVIDIA 驱动 | ≥ 610.88                   | 需包含 WSL 支持     |
| CUDA      | 12.8                       | 由容器镜像提供        |

### WSL 环境检查

bash

```
# 确认 WSL 版本为 2
wsl -l -v

# 如果版本不是 2，执行升级
wsl --set-version Ubuntu-22.04 2

# 确认 NVIDIA 驱动已安装
nvidia-smi
```

***

## 项目结构

text

```
Deploy coreb-reranker/
├── app.py                 # FastAPI 主应用
├── model_server.py        # 模型加载和推理逻辑
├── requirements.txt       # Python 依赖
├── Dockerfile             # Docker 镜像构建文件
├── docker-compose.yml     # Docker Compose 编排文件
├── start.sh               # 服务启动脚本
└── cache/                 # 缓存目录 (运行时创建)
```

***

## 文件内容

### 1. `Dockerfile`

dockerfile

```
# 使用 PyTorch 官方镜像（已包含 CUDA 12.8 和 PyTorch 2.7.0）
FROM pytorch/pytorch:2.7.0-cuda12.8-cudnn9-runtime

# 设置环境变量
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TRANSFORMERS_CACHE=/app/cache \
    HF_HOME=/app/cache \
    MODEL_PATH=/data/models/coreb-code-reranker \
    PORT=16603

# 安装系统依赖
RUN apt-get update && apt-get install -y \
    curl \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 创建缓存目录
RUN mkdir -p /app/cache

# 配置 pip 使用清华镜像源
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY app.py .
COPY model_server.py .
COPY start.sh .

# 赋予启动脚本执行权限
RUN chmod +x start.sh

# 暴露端口
EXPOSE 16603

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:16603/health || exit 1

# 启动命令
CMD ["./start.sh"]
```

***

### 2. `docker-compose.yml`

yaml

```
version: '3.8'

services:
  coreb-reranker:
    build: .
    container_name: coreb-reranker
    ports:
      - "16603:16603"
    volumes:
      - /data/models/coreb-code-reranker:/data/models/coreb-code-reranker:ro
      - ./cache:/app/cache
    environment:
      - PORT=16603
      - MODEL_PATH=/data/models/coreb-code-reranker
      - CUDA_VISIBLE_DEVICES=0
      - TORCH_CUDA_ARCH_LIST=8.0;8.6;8.9;9.0
    restart: unless-stopped
    runtime: nvidia
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu, compute, utility]
```

***

### 3. `requirements.txt`

text

```
transformers>=4.36.0
fastapi>=0.104.0
uvicorn[standard]>=0.24.0
pydantic>=2.0.0
python-multipart>=0.0.6
sentencepiece>=0.1.99
protobuf>=3.20.0
accelerate>=0.24.0
einops>=0.6.0
```

***

### 4. `start.sh`

bash

```
#!/bin/bash

echo "=========================================="
echo "Coreb-Code-Reranker Service Starting..."
echo "=========================================="
echo "Model path: ${MODEL_PATH:-/data/models/coreb-code-reranker}"
echo "Port: ${PORT:-16603}"
echo "=========================================="

# 检查模型目录
if [ -d "/data/models/coreb-code-reranker" ]; then
    echo "✓ Model found at /data/models/coreb-code-reranker"
else
    echo "⚠ Model directory /data/models/coreb-code-reranker not found!"
fi

echo "=========================================="
echo "Starting Uvicorn server..."
echo "=========================================="

exec python -m uvicorn app:app --host 0.0.0.0 --port ${PORT:-16603} --workers 1
```

***

### 5. `app.py`

python

```
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import Optional, Dict
import uvicorn
import os
import logging
import torch
from model_server import get_reranker, Task, reranker

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Coreb-Code-Reranker API",
    description="API for code reranking using coreb-code-reranker model",
    version="1.0.0"
)


# ============ 请求/响应模型 ============
class ScoreRequest(BaseModel):
    query: str = Field(..., description="Query text (code or natural language)")
    document: str = Field(..., description="Document to score against query")
    task_type: str = Field(
        ...,
        description="Task type: 'TEXT_TO_CODE', 'CODE_TO_CODE', or 'CODE_TO_TEXT'",
        pattern="^(TEXT_TO_CODE|CODE_TO_CODE|CODE_TO_TEXT)$"
    )


class ScoreResponse(BaseModel):
    score: float
    query: str
    document: str
    task_type: str


class SystemInfoResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    gpu_available: bool
    gpu_count: int
    gpu_info: Optional[Dict] = None
    model_path: str


# ============ 接口 ============
@app.get("/health", response_model=SystemInfoResponse)
async def health_check():
    """健康检查端点"""
    try:
        gpu_info = None
        if torch.cuda.is_available():
            gpu_info = {
                "name": torch.cuda.get_device_name(0),
                "memory_allocated": f"{torch.cuda.memory_allocated(0) / 1024**3:.2f} GB",
                "memory_reserved": f"{torch.cuda.memory_reserved(0) / 1024**3:.2f} GB"
            }

        return SystemInfoResponse(
            status="healthy" if reranker.model is not None else "loading",
            model_loaded=reranker.model is not None,
            device=str(reranker.device) if reranker.device else "unknown",
            gpu_available=torch.cuda.is_available(),
            gpu_count=torch.cuda.device_count() if torch.cuda.is_available() else 0,
            gpu_info=gpu_info,
            model_path=reranker.model_path
        )
    except Exception as e:
        logger.error(f"Health check error: {e}")
        return SystemInfoResponse(
            status="unhealthy",
            model_loaded=False,
            device="unknown",
            gpu_available=False,
            gpu_count=0,
            model_path=reranker.model_path
        )


@app.post("/score", response_model=ScoreResponse)
async def get_score(request: ScoreRequest):
    """计算查询和文档之间的相关性分数"""
    try:
        if reranker.model is None:
            raise HTTPException(status_code=503, detail="Model not loaded yet")

        task_map = {
            "TEXT_TO_CODE": Task.TEXT_TO_CODE,
            "CODE_TO_CODE": Task.CODE_TO_CODE,
            "CODE_TO_TEXT": Task.CODE_TO_TEXT
        }
        task = task_map[request.task_type]

        logger.info(f"Scoring: task={request.task_type}")
        score_value = reranker.score(
            query=request.query,
            document=request.document,
            task=task
        )

        return ScoreResponse(
            score=score_value,
            query=request.query,
            document=request.document,
            task_type=request.task_type
        )

    except KeyError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid task_type. Must be one of: TEXT_TO_CODE, CODE_TO_CODE, CODE_TO_TEXT"
        )
    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


# ============ 启动事件 ============
@app.on_event("startup")
async def startup_event():
    """应用启动时加载模型"""
    logger.info("Starting up application...")
    try:
        if reranker.model is None:
            reranker.load_model()
        logger.info("Model loaded successfully on startup")
    except Exception as e:
        logger.error(f"Failed to load model on startup: {str(e)}")


if __name__ == "__main__":
    port = int(os.getenv("PORT", 16603))
    uvicorn.run(app, host="0.0.0.0", port=port)
```

***

### 6. `model_server.py`

python

```
import torch
import os
import logging
from enum import Enum
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class Task(Enum):
    TEXT_TO_CODE = "Given a natural language programming task, retrieve code that correctly solves or implements the task."
    CODE_TO_CODE = "Given a code snippet, retrieve code that is semantically equivalent or solves the same task."
    CODE_TO_TEXT = "Given a code snippet, retrieve the natural language description or problem statement that best matches the code."


class RerankerModel:
    def __init__(self, model_path=None):
        self.model_path = model_path or os.getenv("MODEL_PATH", "/data/models/coreb-code-reranker")
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model = None
        self.tokenizer = None
        self.PREFIX = '<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>\n<|im_start|>user\n'
        self.SUFFIX = "<|im_end|>\n<|im_start|>assistant\n"
        self.max_length = 4096

    def load_model(self):
        """加载模型"""
        try:
            logger.info(f"Loading model from: {self.model_path}")
            logger.info(f"Using device: {self.device}")

            if not os.path.exists(self.model_path):
                logger.warning(f"Model directory {self.model_path} does not exist!")

            logger.info("Loading tokenizer...")
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_path,
                trust_remote_code=True
            )

            logger.info("Loading model...")
            load_kwargs = {
                "dtype": torch.bfloat16 if torch.cuda.is_available() else torch.float32,
                "trust_remote_code": True,
                "device_map": "auto" if torch.cuda.is_available() else None,
                "torch_dtype": torch.bfloat16 if torch.cuda.is_available() else torch.float32
            }

            self.model = AutoModelForCausalLM.from_pretrained(
                self.model_path,
                **load_kwargs
            )

            if not torch.cuda.is_available():
                self.model = self.model.to(self.device)

            self.model.eval()
            logger.info("Model loaded successfully!")

            total_params = sum(p.numel() for p in self.model.parameters())
            logger.info(f"Total parameters: {total_params:,}")

            if torch.cuda.is_available():
                logger.info(f"GPU memory allocated: {torch.cuda.memory_allocated() / 1024**3:.2f} GB")
                logger.info(f"Model is on GPU: {next(self.model.parameters()).device}")

            return True

        except Exception as e:
            logger.error(f"Error loading model: {str(e)}")
            import traceback
            logger.error(traceback.format_exc())
            raise

    def _get_yes_no_ids(self):
        """获取 yes 和 no 的 token ID，支持多种格式"""
        yes_id = self.tokenizer.convert_tokens_to_ids("yes")
        no_id = self.tokenizer.convert_tokens_to_ids("no")
        
        if yes_id == self.tokenizer.unk_token_id:
            yes_id = self.tokenizer.convert_tokens_to_ids(" yes")
        if no_id == self.tokenizer.unk_token_id:
            no_id = self.tokenizer.convert_tokens_to_ids(" no")
        
        if yes_id == self.tokenizer.unk_token_id:
            yes_id = self.tokenizer.convert_tokens_to_ids("Yes")
        if no_id == self.tokenizer.unk_token_id:
            no_id = self.tokenizer.convert_tokens_to_ids("No")
        
        return yes_id, no_id

    def score(self, query: str, document: str, task: Task) -> float:
        """计算相关性分数"""
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("Model not loaded. Call load_model() first.")

        prompt = f"{self.PREFIX}<Instruct>: {task.value}\n<Query>: {query}\n<Document>: {document}{self.SUFFIX}"

        inputs = self.tokenizer(
            prompt,
            return_tensors="pt",
            truncation=True,
            max_length=self.max_length
        )

        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            logits = self.model(**inputs).logits[0, -1, :]

        yes_id, no_id = self._get_yes_no_ids()
        score_value = (logits[yes_id] - logits[no_id]).item()
        return score_value


# 全局实例
reranker = RerankerModel()


def get_reranker():
    return reranker
```

***

## 部署步骤

### 1. 环境准备

#### Windows + WSL 2

bash

```
# 确认 WSL 版本
wsl -l -v

# 安装 NVIDIA WSL 驱动
# 访问 https://developer.nvidia.com/cuda/wsl 下载并安装
# 安装后重启 Windows
```

#### 安装 Docker Desktop

1. 下载并安装 Docker Desktop
2. 启用 WSL 2 集成：Settings → Resources → WSL Integration
3. 重启 Docker Desktop

### 2. 准备模型文件

bash

```
# 确保模型文件在正确路径
ls -la /data/models/coreb-code-reranker/
# 应包含: config.json, model.safetensors, tokenizer.json 等
```

### 3. 创建项目目录

bash

```
mkdir -p /data/Deploy\ coreb-reranker
cd /data/Deploy\ coreb-reranker
```

### 4. 创建所有文件

将上述所有文件内容复制到对应文件中。

### 5. 构建镜像

bash

```
docker build --no-cache -t coreb-reranker .
```

### 6. 运行容器

bash

```
docker run --gpus all -d \
    --name coreb-reranker \
    -p 16603:16603 \
    -v /data/models/coreb-code-reranker:/data/models/coreb-code-reranker:ro \
    -v ./cache:/app/cache \
    -e CUDA_VISIBLE_DEVICES=0 \
    -e TORCH_CUDA_ARCH_LIST="8.0;8.6;8.9;9.0" \
    coreb-reranker
```

### 7. 验证部署

bash

```
# 查看日志
docker logs -f coreb-reranker

# 健康检查
curl http://192.168.80.250:16603/health
```

***

## 接口说明

### 1. 健康检查

**请求**：

bash

```
curl http://192.168.80.250:16603/health
```

**响应**：

json

```
{
  "status": "healthy",
  "model_loaded": true,
  "device": "cuda",
  "gpu_available": true,
  "gpu_count": 1,
  "gpu_info": {
    "name": "NVIDIA GeForce RTX 5090",
    "memory_allocated": "7.49 GB",
    "memory_reserved": "7.51 GB"
  },
  "model_path": "/data/models/coreb-code-reranker"
}
```

### 2. 评分接口

**请求**：

bash

```
curl -X POST http://192.168.80.250:16603/score \
    -H "Content-Type: application/json" \
    -d '{
        "query": "add two numbers",
        "document": "def add(a, b): return a + b",
        "task_type": "TEXT_TO_CODE"
    }'
```

**参数说明**：

| **参数**      | **类型** | **必填** | **说明**                                            |
| ----------- | ------ | ------ | ------------------------------------------------- |
| `query`     | string | ✅      | 查询文本（代码或自然语言）                                     |
| `document`  | string | ✅      | 要评分的文档                                            |
| `task_type` | string | ✅      | 任务类型：`TEXT_TO_CODE`、`CODE_TO_CODE`、`CODE_TO_TEXT` |

**响应**：

json

```
{
  "score": 6.46875,
  "query": "add two numbers",
  "document": "def add(a, b): return a + b",
  "task_type": "TEXT_TO_CODE"
}
```

**分数含义**：

* **> 0**：正相关，数值越高相关性越强
* **\< 0**：负相关，内容不匹配
* **≈ 0**：相关性不强

***

## 测试指南

### 1. 使用 curl 测试

#### TEXT\_TO\_CODE（文本→代码）

bash

```
curl -X POST http://192.168.80.250:16603/score \
    -H "Content-Type: application/json" \
    -d '{
        "query": "Write a function to calculate the factorial of a number",
        "document": "def factorial(n): return 1 if n <= 1 else n * factorial(n-1)",
        "task_type": "TEXT_TO_CODE"
    }'
```

#### CODE\_TO\_CODE（代码→代码）

bash

```
curl -X POST http://192.168.80.250:16603/score \
    -H "Content-Type: application/json" \
    -d '{
        "query": "def add(a, b): return a + b",
        "document": "def sum(x, y): return x + y",
        "task_type": "CODE_TO_CODE"
    }'
```

#### CODE\_TO\_TEXT（代码→文本）

bash

```
curl -X POST http://192.168.80.250:16603/score \
    -H "Content-Type: application/json" \
    -d '{
        "query": "def bubble_sort(arr):\n    n = len(arr)\n    for i in range(n):\n        for j in range(0, n-i-1):\n            if arr[j] > arr[j+1]:\n                arr[j], arr[j+1] = arr[j+1], arr[j]\n    return arr",
        "document": "Sort an array using bubble sort algorithm",
        "task_type": "CODE_TO_TEXT"
    }'
```

### 2. 使用 Python 测试

python

```
import requests
import json

url = "http://192.168.80.250:16603/score"

# 测试 TEXT_TO_CODE
data = {
    "query": "add two numbers",
    "document": "def add(a, b): return a + b",
    "task_type": "TEXT_TO_CODE"
}

response = requests.post(url, json=data)
print(f"Status: {response.status_code}")
print(f"Result: {response.json()}")
```

### 3. 批量测试脚本

python

```
import requests
import time

url = "http://192.168.80.250:16603/score"

test_cases = [
    {
        "query": "add two numbers",
        "document": "def add(a, b): return a + b",
        "task_type": "TEXT_TO_CODE"
    },
    {
        "query": "sort a list",
        "document": "def bubble_sort(arr): return sorted(arr)",
        "task_type": "TEXT_TO_CODE"
    },
    {
        "query": "def add(a, b): return a + b",
        "document": "def sum(x, y): return x + y",
        "task_type": "CODE_TO_CODE"
    }
]

for i, data in enumerate(test_cases):
    start = time.time()
    response = requests.post(url, json=data)
    elapsed = time.time() - start
    print(f"Test {i+1}: score={response.json()['score']:.4f}, time={elapsed:.2f}s")
```

***

## 故障排查

### 常见问题及解决方案

| **问题**  | **可能原因**           | **解决方案**               |
| ------- | ------------------ | ---------------------- |
| GPU 不可用 | WSL 驱动未正确安装        | 安装 NVIDIA WSL 驱动并重启    |
| 端口被占用   | 其他进程占用 16603       | 修改端口或停止占用进程            |
| 模型加载失败  | 模型文件不完整            | 检查模型文件，重新下载            |
| 分数固定不变  | yes/no token ID 错误 | 使用最新版 model\_server.py |
| 内存不足    | 模型太大               | 增加系统内存或使用量化版本          |

### 日志查看

bash

```
# 实时日志
docker logs -f coreb-reranker

# 最近 100 行
docker logs --tail 100 coreb-reranker

# 带时间戳
docker logs -t coreb-reranker
```

### 容器管理

bash

```
# 查看容器状态
docker ps | grep coreb-reranker

# 重启容器
docker restart coreb-reranker

# 停止容器
docker stop coreb-reranker

# 删除容器
docker rm coreb-reranker

# 进入容器调试
docker exec -it coreb-reranker bash
```

***

## 性能优化

### 1. GPU 优化

bash

```
# 指定使用特定 GPU
-e CUDA_VISIBLE_DEVICES=0

# 针对 RTX 5090 优化
-e TORCH_CUDA_ARCH_LIST="8.0;8.6;8.9;9.0"
```

### 2. 模型加载优化

python

```
# 使用 bfloat16 减少显存
load_kwargs["torch_dtype"] = torch.bfloat16

# 自动分配设备
load_kwargs["device_map"] = "auto"
```

### 3. 服务优化

| **参数**                 | **推荐值** | **说明**          |
| ---------------------- | ------- | --------------- |
| `--workers`            | 1       | 多进程可能导致 CUDA 冲突 |
| `--limit-max-requests` | 1000    | 防止内存泄漏          |
| `--timeout-keep-alive` | 300     | 保持长连接           |

***

## 版本历史

| **版本** | **日期**     | **更新内容**      |
| ------ | ---------- | ------------- |
| v1.0.0 | 2026-07-31 | 初始版本，支持三种任务类型 |

***

## 相关文档

* Qwen3-Embedding 部署文档
* 混合场景检索方案
