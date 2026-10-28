#!/usr/bin/env python3
"""
武侠知识图谱最小验证脚本

验证目标：
1. 本体论定义能否约束 LLM 抽取
2. 抽取的实体/关系能否存入 Neo4j
3. 能否进行简单的图查询

使用方法：
    python wuxia_minimal_verify.py

前置条件：
    - Neo4j 运行在 localhost:7687
    - 设置 OPENAI_API_KEY 环境变量（或直接修改代码中的 api_key）
"""

import asyncio
import json
import os
from dataclasses import dataclass
from enum import Enum
from typing import Any

import httpx
from neo4j import AsyncGraphDatabase
from pydantic import BaseModel, Field


# ======================
# 1. 本体论定义
# ======================

class EntityType(str, Enum):
    """武侠实体类型"""
    PERSON = "Person"           # 人物
    ORGANIZATION = "Organization"  # 门派/组织
    LOCATION = "Location"       # 地点
    SKILL = "Skill"            # 武功


class RelationType(str, Enum):
    """武侠关系类型"""
    MEMBER_OF = "member_of"     # 属于
    MENTOR_OF = "mentor_of"     # 师父
    PRACTICES = "practices"     # 修炼
    LOCATED_AT = "located_at"   # 位于


class WuxiaEntity(BaseModel):
    """抽取的实体"""
    entity_type: EntityType
    name: str = Field(..., description="实体名称")
    aliases: list[str] = Field(default_factory=list, description="别名列表")


class WuxiaRelation(BaseModel):
    """抽取的关系"""
    relation_type: RelationType
    source: str = Field(..., description="源实体名称")
    target: str = Field(..., description="目标实体名称")
    properties: dict[str, Any] = Field(default_factory=dict)


class ExtractionResult(BaseModel):
    """抽取结果"""
    entities: list[WuxiaEntity]
    relations: list[WuxiaRelation]


def get_ontology_schema_description() -> str:
    """获取本体论 Schema 描述，用于 LLM Prompt"""
    return """
允许的实体类型（EntityType）：
- Person: 人物角色（如：张无忌、郭靖、令狐冲）
- Organization: 门派组织（如：明教、少林派、武当派）
- Location: 地点场所（如：光明顶、武当山、桃花岛）
- Skill: 武功技能（如：九阳神功、降龙十八掌）

允许的关系类型（RelationType）：
- member_of: 人物 → 组织（人物属于某门派）
- mentor_of: 人物 → 人物（师父 → 徒弟）
- practices: 人物 → 技能（人物修炼某武功）
- located_at: 组织/人物 → 地点（位于某地）

严格限制：
1. 只能使用上述预定义的实体类型和关系类型
2. 禁止发明新的类型
3. 实体名称必须准确引用原文
"""


# ======================
# 2. LLM 抽取
# ======================

class EntityExtractor:
    """基于 LLM 的实体和关系抽取器"""

    def __init__(self, api_key: str | None = None, base_url: str | None = None, model: str = None):
        """
        初始化抽取器

        Args:
            api_key: OpenAI API Key（或兼容的 API Key）
            base_url: API Base URL（默认为 OpenAI，可设置为兼容服务）
            model: 模型名称（默认为 gpt-4o-mini，千问可用 glm-4-flash）
        """
        self.api_key = api_key or os.getenv("OPENAI_API_KEY", "")
        self.base_url = base_url or os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
        self.model = model or os.getenv("LLM_MODEL", "gpt-4o-mini")
        self.client = httpx.AsyncClient(timeout=60.0)

    async def extract(self, text: str) -> ExtractionResult:
        """
        从文本中抽取实体和关系

        Args:
            text: 输入文本

        Returns:
            ExtractionResult: 抽取结果
        """
        schema_desc = get_ontology_schema_description()

        prompt = f"""你是武侠小说知识图谱构建专家。从给定文本中抽取实体和关系。

{schema_desc}

文本：
{text}

请以 JSON 格式返回抽取结果，格式如下：
{{
  "entities": [
    {{"entity_type": "Person", "name": "张无忌", "aliases": ["无忌", "张教主"]}},
    {{"entity_type": "Organization", "name": "明教", "aliases": []}}
  ],
  "relations": [
    {{"relation_type": "member_of", "source": "张无忌", "target": "明教", "properties": {{}}}},
    {{"relation_type": "mentor_of", "source": "张三丰", "target": "张无忌", "properties": {{"formal": true}}}}
  ]
}}

只返回 JSON，不要有其他内容。"""

        try:
            response = await self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": self.model,
                    "messages": [
                        {
                            "role": "system",
                            "content": "你是武侠知识图谱抽取专家，严格遵循预定义的本体论Schema。"
                        },
                        {"role": "user", "content": prompt}
                    ],
                    "temperature": 0.1,
                    "response_format": {"type": "json_object"}
                }
            )
            response.raise_for_status()
            result = response.json()

            content = result["choices"][0]["message"]["content"]
            data = json.loads(content)

            # 验证并解析结果
            return ExtractionResult(**data)

        except Exception as e:
            print(f"❌ LLM 抽取失败: {e}")
            # 返回空结果
            return ExtractionResult(entities=[], relations=[])


    async def close(self):
        """关闭客户端"""
        await self.client.aclose()


# ======================
# 3. Neo4j 存储
# ======================

class WuxiaGraphStore:
    """武侠知识图谱存储"""

    def __init__(self, uri: str = "bolt://localhost:7687",
                 user: str = "neo4j", password: str = "password"):
        """
        初始化图存储

        Args:
            uri: Neo4j URI
            user: 用户名
            password: 密码
        """
        self.driver = AsyncGraphDatabase.driver(uri, (user, password))

    async def close(self):
        """关闭连接"""
        await self.driver.close()

    async def add_entities(self, entities: list[WuxiaEntity]) -> dict[str, str]:
        """
        添加实体到图数据库

        Returns:
            dict: 实体名称到内部 ID 的映射
        """
        name_to_id = {}

        async with self.driver.session() as session:
            for entity in entities:
                # 合并节点（如果存在则不创建）
                query = f"""
                MERGE (n:{entity.entity_type} {{name: $name}})
                ON CREATE SET n.aliases = $aliases
                ON MATCH SET n.aliases = COALESCE(n.aliases, []) + $aliases
                RETURN elementId(n) as id
                """
                result = await session.run(
                    query,
                    name=entity.name,
                    aliases=entity.aliases
                )
                record = await result.single()
                name_to_id[entity.name] = record["id"]

        return name_to_id

    async def add_relations(self, relations: list[WuxiaRelation]) -> int:
        """
        添加关系到图数据库

        Returns:
            int: 添加的关系数量
        """
        count = 0

        async with self.driver.session() as session:
            for relation in relations:
                # 根据关系类型构建查询
                query = f"""
                MATCH (s {{name: $source}})
                MATCH (t {{name: $target}})
                MERGE (s)-[r:{relation.relation_type}]->(t)
                SET r += $properties
                RETURN r
                """
                result = await session.run(
                    query,
                    source=relation.source,
                    target=relation.target,
                    properties=relation.properties
                )
                if await result.single():
                    count += 1

        return count

    async def query_entity_relations(self, entity_name: str) -> list[dict]:
        """
        查询实体的所有关系

        Args:
            entity_name: 实体名称

        Returns:
            list: 关系列表
        """
        query = """
        MATCH (s {name: $name})-[r]->(t)
        RETURN s.name as source, type(r) as relation, t.name as target, labels(t) as target_type
        UNION
        MATCH (s {name: $name})<-[r]-(t)
        RETURN t.name as source, type(r) as relation, s.name as target, labels(s) as target_type
        """

        async with self.driver.session() as session:
            result = await session.run(query, name=entity_name)
            records = await result.data()

        return [
            {
                "source": r["source"],
                "relation": r["relation"],
                "target": r["target"],
                "target_type": r["target_type"][0] if r["target_type"] else ""
            }
            for r in records
        ]

    async def clear_all(self):
        """清空所有数据（谨慎使用）"""
        async with self.driver.session() as session:
            await session.run("MATCH (n) DETACH DELETE n")


# ======================
# 4. 主验证流程
# ======================

async def verify_extraction_and_storage(
    sample_text: str,
    api_key: str | None = None,
    neo4j_uri: str = "bolt://localhost:7687",
    neo4j_password: str = "password"
):
    """
    验证抽取和存储流程

    Args:
        sample_text: 待处理的文本
        api_key: LLM API Key
        neo4j_uri: Neo4j URI
        neo4j_password: Neo4j 密码
    """
    print("=" * 60)
    print("武侠知识图谱最小验证")
    print("=" * 60)

    # Step 1: 初始化组件
    print("\n📦 初始化组件...")
    extractor = EntityExtractor(api_key=api_key)
    store = WuxiaGraphStore(uri=neo4j_uri, password=neo4j_password)

    try:
        # Step 2: 清空现有数据
        print("\n🧹 清空现有图数据...")
        await store.clear_all()

        # Step 3: LLM 抽取
        print(f"\n🤖 LLM 抽取中...")
        print(f"输入文本: {sample_text[:100]}...")
        result = await extractor.extract(sample_text)

        print(f"\n✅ 抽取结果:")
        print(f"  - 实体数量: {len(result.entities)}")
        for entity in result.entities:
            print(f"    • [{entity.entity_type}] {entity.name} (别名: {entity.aliases})")

        print(f"\n  - 关系数量: {len(result.relations)}")
        for relation in result.relations:
            props = f" {relation.properties}" if relation.properties else ""
            print(f"    • [{relation.relation_type}] {relation.source} → {relation.target}{props}")

        # Step 4: 存储到 Neo4j
        print(f"\n💾 存储到 Neo4j...")
        entity_ids = await store.add_entities(result.entities)
        print(f"  ✅ 存储了 {len(entity_ids)} 个实体")

        relation_count = await store.add_relations(result.relations)
        print(f"  ✅ 存储了 {relation_count} 个关系")

        # Step 5: 查询验证
        print(f"\n🔍 查询验证...")
        for entity in result.entities[:3]:  # 只验证前3个
            relations = await store.query_entity_relations(entity.name)
            print(f"\n  [{entity.name}] 的关系:")
            for rel in relations:
                print(f"    • {rel['source']} --[{rel['relation']}]--> {rel['target']} ({rel['target_type']})")

        print("\n" + "=" * 60)
        print("✅ 验证完成！")
        print("=" * 60)

        # 返回统计信息
        return {
            "entities_count": len(result.entities),
            "relations_count": len(result.relations),
            "entities": result.entities,
            "relations": result.relations
        }

    finally:
        await extractor.close()
        await store.close()


async def process_novel(
    novel_text: str,
    api_key: str | None = None,
    neo4j_uri: str = "bolt://localhost:7687",
    neo4j_password: str = "password"
):
    """
    处理整本小说

    Args:
        novel_text: 小说文本
        api_key: LLM API Key
        neo4j_uri: Neo4j URI
        neo4j_password: Neo4j 密码
    """
    print("=" * 60)
    print("武侠小说知识图谱构建")
    print("=" * 60)

    extractor = EntityExtractor(api_key=api_key)
    store = WuxiaGraphStore(uri=neo4j_uri, password=neo4j_password)

    try:
        # 简单分割：按段落处理
        paragraphs = [p.strip() for p in novel_text.split("\n\n") if p.strip()]
        print(f"\n📖 共 {len(paragraphs)} 个段落")

        total_entities = set()
        total_relations = 0

        for i, paragraph in enumerate(paragraphs, 1):
            print(f"\n处理段落 {i}/{len(paragraphs)}...")
            result = await extractor.extract(paragraph)

            if result.entities:
                await store.add_entities(result.entities)
                for e in result.entities:
                    total_entities.add((e.entity_type, e.name))
                print(f"  ✅ 实体: {len(result.entities)} 个")

            if result.relations:
                count = await store.add_relations(result.relations)
                total_relations += count
                print(f"  ✅ 关系: {count} 个")

        print(f"\n" + "=" * 60)
        print(f"📊 处理完成!")
        print(f"  - 总实体数: {len(total_entities)}")
        print(f"  - 总关系数: {total_relations}")
        print("=" * 60)

    finally:
        await extractor.close()
        await store.close()


# ======================
# 5. 主入口
# ======================

async def main():
    """主入口"""

    # 从环境变量获取配置
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        print("⚠️  警告: 未设置 OPENAI_API_KEY 环境变量")
        print("   请设置: export OPENAI_API_KEY=your_key")
        print("   使用默认 API Key 进行演示...")

    neo4j_uri = os.getenv("NEO4J_URI", "bolt://localhost:7687")
    neo4j_password = os.getenv("NEO4J_PASSWORD", "password")

    # 示例文本（用于快速验证）
    sample_text = """
    张无忌是明教教主，师承武当派张三丰。他修炼九阳神功，武功盖世。
    明教总部位于光明顶，教主张无忌深受教众爱戴。张三丰是武当派创始人，
    弟子众多，张无忌是他最得意的关门弟子。赵敏是汝阳王府的郡主，
    与张无忌情投意合。周芷若本是峨眉派弟子，后成为峨眉派掌门。
    """

    print("\n选择模式:")
    print("1. 快速验证（使用示例文本）")
    print("2. 处理小说文件")

    # 默认使用快速验证
    mode = "1"

    if mode == "1":
        # 快速验证
        await verify_extraction_and_storage(
            sample_text=sample_text,
            api_key=api_key,
            neo4j_uri=neo4j_uri,
            neo4j_password=neo4j_password
        )
    else:
        # 处理小说文件
        novel_file = input("请输入小说文件路径: ")
        if not os.path.exists(novel_file):
            print(f"❌ 文件不存在: {novel_file}")
            return

        with open(novel_file, "r", encoding="utf-8") as f:
            novel_text = f.read()

        await process_novel(
            novel_text=novel_text,
            api_key=api_key,
            neo4j_uri=neo4j_uri,
            neo4j_password=neo4j_password
        )


if __name__ == "__main__":
    asyncio.run(main())
