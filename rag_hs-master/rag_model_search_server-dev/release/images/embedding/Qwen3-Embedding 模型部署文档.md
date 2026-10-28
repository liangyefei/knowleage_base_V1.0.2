# Qwen3-Embedding 模型部署文档

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



Qwen3-Embedding-4B 是一个基于 Qwen3 架构的文本嵌入模型，用于将文本转换为稠密向量，支持语义搜索、文本相似度计算、信息检索等任务。

**核心功能**：

* 文本向量化（Embedding）
* 语义相似度计算
* 支持查询指令（Query Prompt）
* 支持批处理编码

**典型应用场景**：

* 语义搜索引擎
* 文本聚类与分类
* 推荐系统
* RAG（检索增强生成）系统中的召回阶段

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
Deploy qwen3-embedding-4b/
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
    MODEL_PATH=/data/models/Qwen3-Embedding-4B \
    PORT=16604

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
EXPOSE 16604

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:16604/health || exit 1

# 启动命令
CMD ["./start.sh"]
```

***

### 2. `docker-compose.yml`

yaml

```
version: '3.8'

services:
  qwen3-embedding:
    build: .
    container_name: qwen3-embedding
    ports:
      - "16604:16604"
    volumes:
      - /data/models/Qwen3-Embedding-4B:/data/models/Qwen3-Embedding-4B:ro
      - ./cache:/app/cache
    environment:
      - PORT=16604
      - MODEL_PATH=/data/models/Qwen3-Embedding-4B
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

txt

```
transformers>=4.51.0
sentence-transformers>=2.7.0
fastapi>=0.104.0
uvicorn[standard]>=0.24.0
pydantic>=2.0.0
python-multipart>=0.0.6
accelerate>=0.24.0
einops>=0.6.0
torch>=2.0.0
```

> **注意**：如果使用 FlashAttention2 加速，需要额外安装 `flash-attn>=2.6.0`，但需要 CUDA 开发环境（`-devel` 镜像）。默认配置不使用 FlashAttention2，以保持兼容性。

***

### 4. `start.sh`

bash

```
#!/bin/bash

echo "=========================================="
echo "Qwen3-Embedding Service Starting..."
echo "=========================================="
echo "Model path: ${MODEL_PATH:-/data/models/Qwen3-Embedding-4B}"
echo "Port: ${PORT:-16604}"
echo "=========================================="

# 检查模型目录
if [ -d "/data/models/Qwen3-Embedding-4B" ]; then
    echo "✓ Model found at /data/models/Qwen3-Embedding-4B"
else
    echo "⚠ Model directory /data/models/Qwen3-Embedding-4B not found!"
fi

echo "=========================================="
echo "Starting Uvicorn server..."
echo "=========================================="

exec python -m uvicorn app:app --host 0.0.0.0 --port ${PORT:-16604} --workers 1
```

***

### 5. `app.py`

python

```
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import List, Optional, Union
import uvicorn
import os
import logging
import torch
from model_server import embedding_model, get_embedding_model

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Qwen3-Embedding API",
    description="API for text embedding using Qwen3-Embedding-4B",
    version="1.0.0"
)


# ============ 请求/响应模型 ============
class EmbedRequest(BaseModel):
    texts: Union[str, List[str]] = Field(..., description="Text or list of texts to embed")
    prompt_name: Optional[str] = Field(None, description="Prompt name for query encoding (e.g., 'query')")


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    text_count: int
    embedding_dim: Optional[int] = None


class SimilarityRequest(BaseModel):
    queries: List[str] = Field(..., description="Query texts")
    documents: List[str] = Field(..., description="Document texts")
    query_prompt: Optional[str] = Field("query", description="Prompt name for queries")


class SimilarityResponse(BaseModel):
    similarities: List[List[float]]
    query_count: int
    document_count: int


class SystemInfoResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    gpu_available: bool
    gpu_count: int
    gpu_info: Optional[dict] = None
    model_path: str
    use_sentence_transformers: bool


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
            status="healthy" if embedding_model.model is not None else "loading",
            model_loaded=embedding_model.model is not None,
            device=str(embedding_model.device) if embedding_model.device else "unknown",
            gpu_available=torch.cuda.is_available(),
            gpu_count=torch.cuda.device_count() if torch.cuda.is_available() else 0,
            gpu_info=gpu_info,
            model_path=embedding_model.model_path,
            use_sentence_transformers=embedding_model.use_sentence_transformers if embedding_model.model else False
        )
    except Exception as e:
        logger.error(f"Health check error: {e}")
        return SystemInfoResponse(
            status="unhealthy",
            model_loaded=False,
            device="unknown",
            gpu_available=False,
            gpu_count=0,
            model_path=embedding_model.model_path,
            use_sentence_transformers=False
        )


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest):
    """将文本编码为向量"""
    try:
        if embedding_model.model is None:
            raise HTTPException(status_code=503, detail="Model not loaded yet")

        texts = request.texts if isinstance(request.texts, list) else [request.texts]
        embeddings = embedding_model.encode(texts, prompt_name=request.prompt_name)

        return EmbedResponse(
            embeddings=embeddings,
            text_count=len(texts),
            embedding_dim=len(embeddings[0]) if embeddings else None
        )

    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/similarity", response_model=SimilarityResponse)
async def compute_similarity(request: SimilarityRequest):
    """计算查询和文档之间的相似度"""
    try:
        if embedding_model.model is None:
            raise HTTPException(status_code=503, detail="Model not loaded yet")

        # 编码查询（使用 prompt）
        query_embeddings = embedding_model.encode(
            request.queries,
            prompt_name=request.query_prompt if request.query_prompt else "query"
        )

        # 编码文档（不使用 prompt）
        document_embeddings = embedding_model.encode(request.documents)

        # 计算相似度
        similarities = embedding_model.similarity(query_embeddings, document_embeddings)

        return SimilarityResponse(
            similarities=similarities,
            query_count=len(request.queries),
            document_count=len(request.documents)
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
        if embedding_model.model is None:
            embedding_model.load_model()
        logger.info("Model loaded successfully on startup")
    except Exception as e:
        logger.error(f"Failed to load model on startup: {str(e)}")


if __name__ == "__main__":
    port = int(os.getenv("PORT", 16604))
    uvicorn.run(app, host="0.0.0.0", port=port)
```

***

### 6. `model_server.py`

python

```
import torch
import os
import logging
from typing import List, Optional, Union
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer, AutoModel
import torch.nn.functional as F

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class EmbeddingModel:
    def __init__(self, model_path=None):
        self.model_path = model_path or os.getenv("MODEL_PATH", "/data/models/Qwen3-Embedding-4B")
        self.model = None
        self.tokenizer = None
        self.device = None
        self.use_sentence_transformers = True

    def load_model(self):
        """加载模型"""
        try:
            logger.info(f"Loading embedding model from: {self.model_path}")

            if torch.cuda.is_available():
                self.device = torch.device("cuda")
                logger.info(f"Using GPU: {torch.cuda.get_device_name(0)}")
            else:
                self.device = torch.device("cpu")
                logger.info("CUDA not available, using CPU")

            # 使用 SentenceTransformer 加载
            try:
                logger.info("Loading with SentenceTransformer...")
                self.model = SentenceTransformer(
                    self.model_path,
                    model_kwargs={
                        "device_map": "auto"
                    } if torch.cuda.is_available() else {},
                    tokenizer_kwargs={"padding_side": "left"},
                    device=self.device
                )
                self.use_sentence_transformers = True
                logger.info("Model loaded successfully with SentenceTransformer")

            except Exception as e:
                logger.warning(f"SentenceTransformer loading failed: {e}")
                logger.info("Falling back to Transformers loading...")

                # 备用方案：使用 Transformers
                self.tokenizer = AutoTokenizer.from_pretrained(
                    self.model_path,
                    padding_side="left",
                    trust_remote_code=True
                )
                self.model = AutoModel.from_pretrained(
                    self.model_path,
                    trust_remote_code=True,
                    torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
                    device_map="auto" if torch.cuda.is_available() else None
                )
                if not torch.cuda.is_available():
                    self.model = self.model.to(self.device)
                self.use_sentence_transformers = False
                logger.info("Model loaded successfully with Transformers")

            # 获取 prompts
            self.prompts = getattr(self.model, "prompts", {}) if self.use_sentence_transformers else {}
            logger.info(f"Available prompts: {list(self.prompts.keys()) if self.prompts else 'None'}")

            total_params = sum(p.numel() for p in self.model.parameters())
            logger.info(f"Total parameters: {total_params:,}")

            if torch.cuda.is_available():
                logger.info(f"GPU memory allocated: {torch.cuda.memory_allocated() / 1024**3:.2f} GB")

            return True

        except Exception as e:
            logger.error(f"Error loading model: {str(e)}")
            import traceback
            logger.error(traceback.format_exc())
            raise

    def encode(self, texts: Union[str, List[str]], prompt_name: Optional[str] = None) -> List[List[float]]:
        """编码文本为向量"""
        if self.model is None:
            raise RuntimeError("Model not loaded. Call load_model() first.")

        if isinstance(texts, str):
            texts = [texts]

        logger.info(f"Encoding {len(texts)} texts...")

        if self.use_sentence_transformers:
            embeddings = self.model.encode(
                texts,
                prompt_name=prompt_name,
                convert_to_tensor=True,
                normalize_embeddings=True
            )
            return embeddings.detach().cpu().numpy().tolist()
        else:
            return self._encode_with_transformers(texts)

    def _encode_with_transformers(self, texts: List[str]) -> List[List[float]]:
        """使用 Transformers 编码（备用方案）"""
        batch_dict = self.tokenizer(
            texts,
            padding=True,
            truncation=True,
            max_length=8192,
            return_tensors="pt"
        )
        batch_dict = {k: v.to(self.model.device) for k, v in batch_dict.items()}

        with torch.no_grad():
            outputs = self.model(**batch_dict)

        attention_mask = batch_dict['attention_mask']
        last_hidden_states = outputs.last_hidden_state

        # last_token_pool
        left_padding = (attention_mask[:, -1].sum() == attention_mask.shape[0])
        if left_padding:
            embeddings = last_hidden_states[:, -1]
        else:
            sequence_lengths = attention_mask.sum(dim=1) - 1
            batch_size = last_hidden_states.shape[0]
            embeddings = last_hidden_states[torch.arange(batch_size, device=last_hidden_states.device), sequence_lengths]

        # 归一化
        embeddings = F.normalize(embeddings, p=2, dim=1)
        return embeddings.cpu().numpy().tolist()

    def similarity(self, query_embeddings: List[List[float]], document_embeddings: List[List[float]]) -> List[List[float]]:
        """计算查询和文档向量之间的相似度"""
        import numpy as np
        q = np.array(query_embeddings)
        d = np.array(document_embeddings)
        similarity_matrix = np.dot(q, d.T)
        return similarity_matrix.tolist()


# 全局实例
embedding_model = EmbeddingModel()


def get_embedding_model():
    return embedding_model
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
ls -la /data/models/Qwen3-Embedding-4B/
# 应包含: config.json, model.safetensors, tokenizer.json 等
```

### 3. 创建项目目录

bash

```
mkdir -p /data/Deploy\ qwen3-embedding-4b
cd /data/Deploy\ qwen3-embedding-4b
```

### 4. 创建所有文件

将上述所有文件内容复制到对应文件中。

### 5. 构建镜像

bash

```
docker build --no-cache -t qwen3-embedding .
```

### 6. 运行容器

bash

```
docker run --gpus all -d \
    --name qwen3-embedding \
    -p 16604:16604 \
    -v /data/models/Qwen3-Embedding-4B:/data/models/Qwen3-Embedding-4B:ro \
    -v ./cache:/app/cache \
    -e CUDA_VISIBLE_DEVICES=0 \
    -e TORCH_CUDA_ARCH_LIST="8.0;8.6;8.9;9.0" \
    qwen3-embedding
```

### 7. 验证部署

bash

```
# 查看日志
docker logs -f qwen3-embedding

# 健康检查
curl http://192.168.80.250:16604/health
```

***

## 接口说明

### 1. 健康检查

**请求**：

bash

```
curl http://192.168.80.250:16604/health
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
  "model_path": "/data/models/Qwen3-Embedding-4B",
  "use_sentence_transformers": false
}
```

***

### 2. 文本编码接口

将文本转换为向量。

**请求**：

bash

```
curl -X POST http://192.168.80.250:16604/embed \
    -H "Content-Type: application/json" \
    -d '{
        "texts": ["What is the capital of China?", "Explain gravity"]
    }'
```

**参数说明**：

| **参数**        | **类型**             | **必填** | **说明**              |
| ------------- | ------------------ | ------ | ------------------- |
| `texts`       | string 或 string\[] | ✅      | 要编码的文本              |
| `prompt_name` | string             | ❌      | 查询指令名称（如 `"query"`） |

**响应**：

json

```
{
  "embeddings": [
    [0.123, -0.456, 0.789, ...],
    [0.234, -0.567, 0.890, ...]
  ],
  "text_count": 2,
  "embedding_dim": 4096
}
```

***

### 3. 相似度计算接口

计算查询和文档之间的语义相似度。

**请求**：

bash

```
curl -X POST http://192.168.80.250:16604/similarity \
    -H "Content-Type: application/json" \
    -d '{
        "queries": ["What is the capital of China?", "Explain gravity"],
        "documents": ["The capital of China is Beijing.", "Gravity is a force that attracts two bodies towards each other."]
    }'
```

**参数说明**：

| **参数**         | **类型**    | **必填** | **说明**              |
| -------------- | --------- | ------ | ------------------- |
| `queries`      | string\[] | ✅      | 查询文本列表              |
| `documents`    | string\[] | ✅      | 文档文本列表              |
| `query_prompt` | string    | ❌      | 查询指令名称，默认 `"query"` |

**响应**：

json

```
{
  "similarities": [
    [0.8589, 0.2488],
    [0.1519, 0.7461]
  ],
  "query_count": 2,
  "document_count": 2
}
```

**结果解读**：

* `similarities[i][j]`：第 i 个查询与第 j 个文档的相似度
* 数值范围 0\~1，越高表示越相关

***

## 测试指南

### 1. 使用 curl 测试

#### 健康检查

bash

```
curl http://192.168.80.250:16604/health
```

#### 文本编码

bash

```
curl -X POST http://192.168.80.250:16604/embed \
    -H "Content-Type: application/json" \
    -d '{
        "texts": ["What is the capital of China?", "Explain gravity"]
    }'
```

#### 相似度计算

bash

```
curl -X POST http://192.168.80.250:16604/similarity \
    -H "Content-Type: application/json" \
    -d '{
        "queries": ["What is the capital of China?", "Explain gravity"],
        "documents": ["The capital of China is Beijing.", "Gravity is a force that attracts two bodies towards each other."]
    }'
```

#### 带查询指令的编码

bash

```
curl -X POST http://192.168.80.250:16604/embed \
    -H "Content-Type: application/json" \
    -d '{
        "texts": ["What is the capital of China?"],
        "prompt_name": "query"
    }'
```

### 2. 使用 Python 测试

python

```
import requests
import json

url = "http://192.168.80.250:16604"

# 测试相似度
data = {
    "queries": ["What is the capital of China?", "Explain gravity"],
    "documents": ["The capital of China is Beijing.", "Gravity is a force that attracts two bodies towards each other."]
}

response = requests.post(f"{url}/similarity", json=data)
print(json.dumps(response.json(), indent=2))
```

### 3. 性能测试

python

```
import requests
import time
import numpy as np

url = "http://192.168.80.250:16604"

# 测试编码性能
texts = ["测试文本 " + str(i) for i in range(10)]
data = {"texts": texts}

start = time.time()
response = requests.post(f"{url}/embed", json=data)
elapsed = time.time() - start

print(f"编码 10 条文本耗时: {elapsed:.2f}s")
print(f"向量维度: {len(response.json()['embeddings'][0])}")
```

***

## 故障排查

### 常见问题及解决方案

| **问题**                   | **可能原因**     | **解决方案**               |
| ------------------------ | ------------ | ---------------------- |
| GPU 不可用                  | WSL 驱动未正确安装  | 安装 NVIDIA WSL 驱动并重启    |
| 端口被占用                    | 其他进程占用 16604 | 修改端口或停止占用进程            |
| 模型加载失败                   | 模型文件不完整      | 检查模型文件，重新下载            |
| `Can't call numpy()`     | 张量未 detach   | 使用最新版 model\_server.py |
| SentenceTransformer 加载失败 | 依赖缺失或版本不兼容   | 自动回退到 Transformers     |

### 日志查看

bash

```
# 实时日志
docker logs -f qwen3-embedding

# 最近 100 行
docker logs --tail 100 qwen3-embedding

# 带时间戳
docker logs -t qwen3-embedding
```

### 容器管理

bash

```
# 查看容器状态
docker ps | grep qwen3-embedding

# 重启容器
docker restart qwen3-embedding

# 停止容器
docker stop qwen3-embedding

# 删除容器
docker rm qwen3-embedding

# 进入容器调试
docker exec -it qwen3-embedding bash
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
# 使用 float16 减少显存
torch_dtype=torch.float16

# 自动分配设备
device_map="auto"
```

### 3. 批处理优化

| **场景**         | **推荐批次大小** | **说明**   |
| -------------- | ---------- | -------- |
| 短文本（\< 100 字符） | 64-128     | 显存充足时可增大 |
| 长文本（> 500 字符）  | 16-32      | 避免 OOM   |
| 相似度计算          | 根据查询和文档数量  | 矩阵计算效率高  |

### 4. 服务优化

| **参数**                 | **推荐值** | **说明**          |
| ---------------------- | ------- | --------------- |
| `--workers`            | 1       | 多进程可能导致 CUDA 冲突 |
| `--limit-max-requests` | 1000    | 防止内存泄漏          |

***

## 与 Reranker 配合使用

### 混合检索架构

text

```
用户查询
    │
    ▼
┌─────────────────────────────────────────────┐
│ 第一阶段：召回 (Qwen3-Embedding)            │
│ - 将所有文档编码为向量                       │
│ - 计算查询与所有文档的相似度                 │
│ - 召回 Top-K 候选文档                       │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│ 第二阶段：精排 (Coreb-Code-Reranker)        │
│ - 对候选文档逐一计算相关性分数               │
│ - 重新排序，输出最终结果                     │
└─────────────────────────────────────────────┘
```

### 代码示例

python

```
import requests

# 1. 召回阶段：使用 Qwen3-Embedding
def recall(query, documents, top_k=10):
    response = requests.post(
        "http://192.168.80.250:16604/similarity",
        json={"queries": [query], "documents": documents}
    )
    similarities = response.json()["similarities"][0]
    # 返回 Top-K 的索引
    return sorted(range(len(similarities)), key=lambda i: similarities[i], reverse=True)[:top_k]

# 2. 精排阶段：使用 Coreb-Code-Reranker
def rerank(query, documents, candidate_indices):
    candidates = [documents[i] for i in candidate_indices]
    scores = []
    for doc in candidates:
        response = requests.post(
            "http://192.168.80.250:16603/score",
            json={
                "query": query,
                "document": doc,
                "task_type": "TEXT_TO_CODE"
            }
        )
        scores.append(response.json()["score"])
    # 按分数重新排序
    sorted_results = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return sorted_results

# 使用示例
documents = [...]  # 文档列表
query = "写一个函数读取CSV文件"
candidates = recall(query, documents, top_k=10)
results = rerank(query, documents, candidates)
```

***

## 版本历史

| **版本** | **日期**     | **更新内容**        |
| ------ | ---------- | --------------- |
| v1.0.0 | 2026-07-31 | 初始版本，支持编码和相似度计算 |

***

## 相关文档

* Coreb-Code-Reranker 部署文档
* 混合场景检索方案
