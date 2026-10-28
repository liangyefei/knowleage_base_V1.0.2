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
