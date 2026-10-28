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
