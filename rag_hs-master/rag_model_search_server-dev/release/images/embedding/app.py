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
    version="1.0.0",
    openapi_url=None,
    docs_url=None,
    redoc_url=None,
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
        raise HTTPException(status_code=500, detail=str(e)) from e


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
        raise HTTPException(status_code=500, detail=str(e)) from e


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
