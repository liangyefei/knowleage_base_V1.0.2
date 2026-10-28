#!/usr/bin/env python3
"""
武侠知识图谱查询 API

提供 Web 界面和 REST API 用于查询知识图谱
"""

import asyncio
import logging
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from neo4j import AsyncGraphDatabase
from pydantic import BaseModel

# 加载环境变量
load_dotenv()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# 创建 FastAPI 应用
app = FastAPI(
    title="武侠知识图谱 API",
    description="查询和可视化武侠小说知识图谱",
    version="1.0.0"
)

# Neo4j 连接配置
NEO4J_URI = "bolt://localhost:7687"
NEO4J_USER = "neo4j"
NEO4J_PASSWORD = "password"


# ======================
# 数据模型
# ======================

class QueryRequest(BaseModel):
    """查询请求"""
    question: str


class GraphNode(BaseModel):
    """图节点"""
    id: str
    label: str
    type: str
    description: str | None = None


class GraphEdge(BaseModel):
    """图边"""
    source: str
    target: str
    relation: str


class GraphResponse(BaseModel):
    """图响应"""
    nodes: list[GraphNode]
    edges: list[GraphEdge]
    answer: str | None = None


# ======================
# Neo4j 连接管理
# ======================

class GraphDatabase:
    """图数据库连接"""

    def __init__(self, uri: str, user: str, password: str):
        self.driver = AsyncGraphDatabase.driver(uri, (user, password))

    async def close(self):
        await self.driver.close()

    async def query_by_question(self, question: str) -> dict:
        """
        根据问题查询图谱

        简化版：提取问题中的实体名，返回相关子图
        """
        # 提取问题中的可能实体名（简化处理）
        # 实际应用中应该使用 LLM 进行实体链接

        # 查询所有节点（用于小规模数据）
        # 对于生产环境，应该先通过 NER 提取实体
        query = """
        MATCH (n)
        WITH n, size((n)-[:MEMBER_OF|MENTOR_OF|PRACTICES|LOCATED_AT|MENTOR_OF|MEMBER_OF|PARTICIPATED_IN]-()) as degree
        WHERE degree > 0
        RETURN n
        ORDER BY degree DESC
        LIMIT 50
        """

        async with self.driver.session() as session:
            result = await session.run(query)
            records = await result.data()

        nodes = {}
        edges = []

        for record in records:
            node = record['n']
            labels = list(node.labels)
            node_type = labels[0] if labels else 'Unknown'
            node_id = node.element_id

            nodes[node_id] = GraphNode(
                id=str(node_id),
                label=node.get('name', 'Unknown'),
                type=node_type,
                description=node.get('description')
            )

        # 获取这些节点之间的关系
        if nodes:
            node_ids = list(nodes.keys())
            rel_query = """
            MATCH (a)-[r]->(b)
            WHERE elementId(a) IN $node_ids AND elementId(b) IN $node_ids
            RETURN elementId(a) as source, elementId(b) as target,
                   type(r) as relation, a.name as source_name, b.name as target_name
            LIMIT 200
            """

            async with self.driver.session() as session:
                result = await session.run(rel_query, node_ids=node_ids)
                rel_records = await result.data()

            for record in rel_records:
                edges.append(GraphEdge(
                    source=str(record['source']),
                    target=str(record['target']),
                    relation=record['relation']
                ))

        return {
            'nodes': list(nodes.values()),
            'edges': edges
        }

    async def search_entity(self, entity_name: str) -> dict:
        """
        搜索特定实体及其关系
        """
        # 查找匹配的节点
        node_query = """
        MATCH (n)
        WHERE n.name CONTAINS $name OR n.name = $exact_name
        RETURN elementId(n) as id, labels(n) as labels, n.name as name, n.description as description
        LIMIT 10
        """

        async with self.driver.session() as session:
            result = await session.run(node_query, name=entity_name, exact_name=entity_name)
            records = await result.data()

        if not records:
            return {'nodes': [], 'edges': []}

        nodes = {}
        node_ids = []

        for record in records:
            node_id = str(record['id'])
            labels = record['labels']
            node_type = labels[0] if labels else 'Unknown'

            nodes[node_id] = GraphNode(
                id=node_id,
                label=record['name'],
                type=node_type,
                description=record.get('description')
            )
            node_ids.append(record['id'])

        # 获取这些节点的关系
        rel_query = """
        MATCH (a)-[r]-(b)
        WHERE elementId(a) IN $node_ids OR elementId(b) IN $node_ids
        RETURN elementId(a) as source, elementId(b) as target,
               type(r) as relation, properties(r) as props
        LIMIT 100
        """

        async with self.driver.session() as session:
            result = await session.run(rel_query, node_ids=node_ids)
            rel_records = await result.data()

        edges = []
        seen_edges = set()

        for record in rel_records:
            source = str(record['source'])
            target = str(record['target'])
            relation = record['relation']

            # 去重
            edge_key = f"{source}-{relation}-{target}"
            if edge_key not in seen_edges:
                seen_edges.add(edge_key)
                edges.append(GraphEdge(
                    source=source,
                    target=target,
                    relation=relation
                ))

        # 添加连接的节点
        connected_ids = set(node_ids)
        for edge in edges:
            connected_ids.add(edge.source)
            connected_ids.add(edge.target)

        # 获取所有相关节点
        if connected_ids:
            all_nodes_query = """
            MATCH (n)
            WHERE elementId(n) IN $node_ids
            RETURN elementId(n) as id, labels(n) as labels, n.name as name, n.description as description
            """

            async with self.driver.session() as session:
                result = await session.run(all_nodes_query, node_ids=list(connected_ids))
                node_records = await result.data()

            for record in node_records:
                node_id = str(record['id'])
                if node_id not in nodes:
                    labels = record['labels']
                    node_type = labels[0] if labels else 'Unknown'
                    nodes[node_id] = GraphNode(
                        id=node_id,
                        label=record['name'],
                        type=node_type,
                        description=record.get('description')
                    )

        return {
            'nodes': list(nodes.values()),
            'edges': edges
        }


# 全局图数据库实例
graph_db = GraphDatabase(NEO4J_URI, NEO4J_USER, NEO4J_PASSWORD)


# ======================
# 路由
# ======================

@app.get("/", response_class=HTMLResponse)
async def root():
    """返回主页 HTML"""
    with open("/root/MemMachine/examples/wuxia_graph_viewer.html", "r", encoding="utf-8") as f:
        return f.read()


@app.post("/api/wuxia/query")
async def query_knowledge_graph(request: QueryRequest) -> GraphResponse:
    """
    查询知识图谱

    简化实现：提取问题中的关键词进行搜索
    """
    question = request.question.strip()

    if not question:
        raise HTTPException(status_code=400, detail="问题不能为空")

    logger.info(f"查询问题: {question}")

    try:
        # 简单的关键词提取（实际应该用 LLM）
        # 提取中文关键词
        import re
        keywords = re.findall(r'[一-鿿]+', question)

        nodes = []
        edges = []
        seen_nodes = {}
        seen_edges = set()

        # 对每个关键词进行搜索
        for keyword in keywords:
            if len(keyword) < 2:  # 跳过单字
                continue

            result = await graph_db.search_entity(keyword)

            # 合并节点
            for node in result['nodes']:
                if node.id not in seen_nodes:
                    seen_nodes[node.id] = node
                    nodes.append(node)

            # 合并边
            for edge in result['edges']:
                edge_key = f"{edge.source}-{edge.relation}-{edge.target}"
                if edge_key not in seen_edges:
                    seen_edges.add(edge_key)
                    edges.append(edge)

        return GraphResponse(
            nodes=nodes,
            edges=edges
        )

    except Exception as e:
        logger.error(f"查询错误: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/wuxia/entity/{entity_name}")
async def get_entity(entity_name: str) -> GraphResponse:
    """获取特定实体的关系图"""
    try:
        result = await graph_db.search_entity(entity_name)
        return GraphResponse(**result)
    except Exception as e:
        logger.error(f"查询错误: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/health")
async def health_check():
    """健康检查"""
    try:
        # 测试 Neo4j 连接
        async with graph_db.driver.session() as session:
            await session.run("RETURN 1")
        return {"status": "healthy", "neo4j": "connected"}
    except Exception as e:
        return {"status": "unhealthy", "error": str(e)}


# ======================
# 启动和关闭
# ======================

@app.on_event("startup")
async def startup_event():
    """启动时的初始化"""
    logger.info("启动武侠知识图谱 API 服务")


@app.on_event("shutdown")
async def shutdown_event():
    """关闭时的清理"""
    await graph_db.close()
    logger.info("关闭武侠知识图谱 API 服务")


# ======================
# 主入口
# ======================

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8080,
        log_level="info"
    )
