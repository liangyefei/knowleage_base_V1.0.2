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
    version="2.0.0",
    openapi_url=None,
    docs_url=None,
    redoc_url=None,
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
    响应体: {_OUTPUT_KEY: [[词列表], [词性列表]]}

    Taskflow("pos_tagging") 对单条输入返回 [('word', 'tag'), ...] 元组列表，
    这里转为 [[词列表], [词性列表]] 以对齐主项目 lac_service.py 的契约。
    """
    _OUTPUT_KEY = 'output'
    # 空文本短路：PaddleNLP Taskflow 不接受空输入（会抛 "input text should not be empty"），
    # 直接返回 [[], []] 以对齐契约，避免 500
    if not req.base_word:
        return {_OUTPUT_KEY: [[], []]}

    try:
        lac = get_lac()
        result = lac(req.base_word)
        if result:
            words, tags = zip(*result)
            return {_OUTPUT_KEY: [list(words), list(tags)]}
        return {_OUTPUT_KEY: [[], []]}
    except Exception as e:
        logger.error(f"LAC 处理失败: {e}")
        raise HTTPException(status_code=500, detail=str(e)) from e


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
