# FROM registry.baidubce.com/paddlepaddle/paddle:2.1.3
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
