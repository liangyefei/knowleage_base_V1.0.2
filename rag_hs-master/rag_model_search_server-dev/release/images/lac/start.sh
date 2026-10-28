#!/bin/bash

echo "=========================================="
echo "LAC 中文分词服务 Starting..."
echo "=========================================="
echo "Port: ${PORT:-5955}"
echo "Mode: pos_tagging (分词 + 词性标注)"
echo "=========================================="

exec python -m uvicorn app:app --host 0.0.0.0 --port ${PORT:-5955} --workers 1
