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
