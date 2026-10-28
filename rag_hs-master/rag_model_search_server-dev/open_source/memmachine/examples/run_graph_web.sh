#!/bin/bash
# 武侠知识图谱 Web 服务启动脚本

set -e

echo "=================================================="
echo "武侠知识图谱 Web 服务"
echo "=================================================="

# 检查 Python 依赖
echo ""
echo "检查依赖..."

if ! python3 -c "import fastapi" 2>/dev/null; then
    echo "安装 FastAPI 依赖..."
    pip3 install fastapi uvicorn python-multipart
fi

if ! python3 -c "import neo4j" 2>/dev/null; then
    echo "安装 Neo4j 驱动..."
    pip3 install neo4j
fi

# 检查 Neo4j
echo ""
echo "检查 Neo4j..."
if ! nc -z localhost 7687 2>/dev/null; then
    echo "⚠️  Neo4j 未运行"
    echo ""
    echo "启动 Neo4j:"
    echo "  docker start memmachine-neo4j"
    echo ""
    read -p "是否启动 Neo4j? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker start memmachine-neo4j
        sleep 3
    fi
fi

# 设置端口
PORT=${PORT:-8080}

echo ""
echo "=================================================="
echo "启动 Web 服务..."
echo "=================================================="
echo ""
echo "服务地址: http://localhost:$PORT"
echo "按 Ctrl+C 停止服务"
echo ""

# 启动服务
cd /root/MemMachine/examples
python3 wuxia_graph_api.py
