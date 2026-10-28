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
    version="1.0.0",
    openapi_url=None,
    docs_url=None,
    redoc_url=None,
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

    except KeyError as err:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid task_type. Must be one of: TEXT_TO_CODE, CODE_TO_CODE, CODE_TO_TEXT"
        ) from err
    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e)) from e


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
