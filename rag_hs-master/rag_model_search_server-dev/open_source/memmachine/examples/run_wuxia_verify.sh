#!/bin/bash
# 武侠知识图谱验证脚本

set -e

echo "=================================================="
echo "武侠知识图谱最小验证"
echo "=================================================="

# 检查 Python
if ! command -v python3 &> /dev/null; then
    echo "❌ 未找到 Python3"
    exit 1
fi

# 检查 Neo4j
echo ""
echo "检查 Neo4j..."
if ! nc -z localhost 7687 2>/dev/null; then
    echo "⚠️  Neo4j 未运行"
    echo ""
    echo "启动 Neo4j:"
    echo "  docker run -d --name neo4j -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/password neo4j:latest"
    echo ""
    read -p "是否继续？(y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 检查 API Key
echo ""
echo "检查 LLM 配置..."
if [ -z "$OPENAI_API_KEY" ]; then
    echo "⚠️  未设置 OPENAI_API_KEY 环境变量"
    echo ""
    echo "请选择 LLM 提供商:"
    echo "  1. 阿里云千问 (推荐)"
    echo "  2. OpenAI"
    echo "  3. 其他兼容服务"
    read -p "请选择 (1-3): " llm_choice

    case $llm_choice in
        1)
            echo ""
            read -p "请输入千问 API Key: " api_key
            export OPENAI_API_KEY="$api_key"
            export OPENAI_BASE_URL="https://open.bigmodel.cn/api/paas/v4/"
            export LLM_MODEL="glm-4-flash"
            echo "✅ 已配置千问模型"
            ;;
        2)
            echo ""
            read -p "请输入 OpenAI API Key: " api_key
            export OPENAI_API_KEY="$api_key"
            export OPENAI_BASE_URL="https://api.openai.com/v1"
            export LLM_MODEL="gpt-4o-mini"
            echo "✅ 已配置 OpenAI 模型"
            ;;
        3)
            echo ""
            read -p "请输入 API Key: " api_key
            read -p "请输入 Base URL: " base_url
            read -p "请输入模型名称: " model
            export OPENAI_API_KEY="$api_key"
            export OPENAI_BASE_URL="$base_url"
            export LLM_MODEL="$model"
            echo "✅ 已配置自定义模型"
            ;;
        *)
            echo "❌ 无效选择"
            exit 1
            ;;
    esac
fi

# 显示当前配置
echo ""
echo "当前配置:"
echo "  API URL: ${OPENAI_BASE_URL:-未设置}"
echo "  模型: ${LLM_MODEL:-gpt-4o-mini}"

# 选择模式
echo ""
echo "选择模式:"
echo "  1. 快速验证（内置样本文本）"
echo "  2. 处理测试样本文件"
echo "  3. 处理自定义文件"
read -p "请选择 (1-3): " mode

# 设置参数
NEO4J_URI=${NEO4J_URI:-bolt://localhost:7687}
NEO4J_PASSWORD=${NEO4J_PASSWORD:-password}

case $mode in
    1)
        echo ""
        echo "运行快速验证..."
        python3 wuxia_minimal_verify.py
        ;;
    2)
        echo ""
        echo "处理测试样本文件..."
        python3 -c "
import asyncio
import sys
sys.path.insert(0, '.')

from wuxia_minimal_verify import process_novel

asyncio.run(process_novel(
    novel_text=open('sample_wuxia_text.txt').read(),
    api_key='$OPENAI_API_KEY',
    neo4j_uri='$NEO4J_URI',
    neo4j_password='$NEO4J_PASSWORD'
))
"
        ;;
    3)
        read -p "请输入文件路径: " filepath
        if [ ! -f "$filepath" ]; then
            echo "❌ 文件不存在: $filepath"
            exit 1
        fi
        echo ""
        echo "处理文件: $filepath..."
        python3 -c "
import asyncio
import sys
sys.path.insert(0, '.')

from wuxia_minimal_verify import process_novel

asyncio.run(process_novel(
    novel_text=open('$filepath').read(),
    api_key='$OPENAI_API_KEY',
    neo4j_uri='$NEO4J_URI',
    neo4j_password='$NEO4J_PASSWORD'
))
"
        ;;
    *)
        echo "❌ 无效选择"
        exit 1
        ;;
esac

echo ""
echo "=================================================="
echo "验证完成！"
echo "=================================================="
echo ""
echo "查看 Neo4j 图数据:"
echo "  http://localhost:7474"
echo "  用户名: neo4j"
echo "  密码: password"
echo ""
