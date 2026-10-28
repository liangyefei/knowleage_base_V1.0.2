# 武侠知识图谱构建系统设计方案

## 1. 背景

本方案基于 MemMachine 的存储和检索能力，构建一个面向武侠小说的知识图谱系统。系统需要支持：
- 预定义本体论（实体类型、关系类型、约束规则）
- 多智能体协同知识抽取
- 混合检索增强问答
- 时间线与事件时序推理

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          应用层 (Application Layer)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ 知识构建API  │  │ 查询问答API   │  │ 时间线查询   │  │ 可视化接口   │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                        服务层 (Service Layer)                            │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    WuxiaKnowledgeGraphService                     │  │
│  │  - 知识构建流水线编排                                            │  │
│  │  - 查询路由与结果融合                                            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ 本体论管理器 │  │ 约束验证引擎 │  │ 时间推理引擎 │  │ 检索路由器   │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                        Agent 层 (Agent Layer)                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │结构分析Agent │  │实体抽取Agent │  │关系抽取Agent │  │知识对齐Agent │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   │
│  │逻辑校验Agent │  │时间线Agent   │  │问答Agent     │                   │
│  └──────────────┘  └──────────────┘  └──────────────┘                   │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                    MemMachine 核心层 (Core Layer)                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    VectorGraphStore (Neo4j)                       │  │
│  │  ┌──────────────────┐      ┌──────────────────┐                   │  │
│  │  │   Node (实体)    │──────│   Edge (关系)    │                   │  │
│  │  │ - properties     │      │ - relation       │                   │  │
│  │  │ - embeddings     │      │ - properties     │                   │  │
│  │  └──────────────────┘      └──────────────────┘                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## 3. 核心组件设计

### 3.1 本体论管理器 (OntologyManager)

**位置**: `packages/server/src/memmachine_server/wuxia_ontology/ontology_manager.py`

```python
class EntityType(Enum):
    """武侠领域实体类型"""
    PERSON = "Person"           # 人物
    ORGANIZATION = "Organization"  # 门派/组织
    LOCATION = "Location"       # 地点
    SKILL = "Skill"            # 武功/技能
    ARTIFACT = "Artifact"      # 道具/兵器
    EVENT = "Event"            # 事件
    TIME = "Time"              # 时间节点

class RelationType(Enum):
    """武侠领域关系类型"""
    # 师承关系
    MENTOR_OF = "mentor_of"           # 师父→徒弟
    STUDENT_OF = "student_of"         # 徒弟→师父
    LEARNED_FROM = "learned_from"     # 非正式习得

    # 家族关系
    FATHER_OF = "father_of"
    MOTHER_OF = "mother_of"
    SIBLING_OF = "sibling_of"
    SPOUSE_OF = "spouse_of"
    SWORN_SIBLING_OF = "sworn_sibling_of"  # 结义兄弟

    # 门派归属
    MEMBER_OF = "member_of"
    LEADER_OF = "leader_of"
    BETRAYED = "betrayed"
    LOYAL_TO = "loyal_to"

    # 情感关系
    LOVES = "loves"
    HATES = "hates"
    RESPECTS = "respects"

    # 武功传承
    PRACTICES = "practices"           # 修炼
    MASTERED = "mastered"             # 学全
    TAUGHT = "taught"                 # 传授
    STOLEN = "stolen"                 # 夺取

    # 兵器持有
    OWNS = "owns"
    WIELDED = "wielded"
    LOST = "lost"

    # 事件参与
    PARTICIPATED_IN = "participated_in"
    LED = "led"
    WITNESSED = "witnessed"

    # 地点关联
    LOCATED_AT = "located_at"
    OCCURRED_AT = "occurred_at"

    # 武功关系
    COUNTERS = "counters"             # 克制
    SYNERGIZES_WITH = "synergizes_with"  # 相生

    # 时间关系
    OCCURRED_BEFORE = "occurred_before"
    OCCURRED_AFTER = "occurred_after"
    OCCURRED_DURING = "occurred_during"
```

### 3.2 Agent 系统设计

**位置**: `packages/server/src/memmachine_server/wuxia_agents/`

| Agent | 职责 | 输入 | 输出 |
|-------|------|------|------|
| 结构分析Agent | 章节分割、场景标注、时间标记提取 | 原始文本 | ChapterStructure |
| 实体抽取Agent | 基于Ontology约束抽取实体 | 文本 + 已知实体 | ExtractedEntity[] |
| 关系抽取Agent | 抽取关系三元组 | 文本 + 实体列表 | ExtractedRelation[] |
| 知识对齐Agent | 实体消歧、共指消解、冲突检测 | 新实体 + 现有实体 | 对齐映射 + 冲突报告 |
| 逻辑校验Agent | 时间悖论、关系矛盾检测 | 实体 + 关系 | ValidationResult |
| 时间线Agent | 构建时间线、处理倒叙插叙 | 章节结构 + 事件 | TimeNode[] |

### 3.3 知识构建流水线

**位置**: `packages/server/src/memmachine_server/wuxia_pipeline/`

```
输入：武侠小说文本
  ↓
步骤1: 文本预处理与结构分析 (StructureAnalysisAgent)
  ↓
步骤2: 实体抽取 (EntityExtractionAgent) - 受Schema约束
  ↓
步骤3: 关系三元组抽取 (RelationExtractionAgent)
  ↓
步骤4: 知识对齐与去噪 (KnowledgeAlignmentAgent)
  ↓
步骤5: 逻辑一致性校验 (LogicalValidationAgent)
  ↓
步骤6: 置信度分级与处理
  - 高置信(>85%): 直接入库
  - 中置信(70-85%): 抽检
  - 低置信(<70%): 人工审核
  ↓
步骤7: 添加关系到Neo4j
  ↓
步骤8: 质检反馈闭环 (错误案例转化为新规则)
  ↓
输出: PipelineResult (统计 + 报告)
```

### 3.4 混合检索与问答

**位置**: `packages/server/src/memmachine_server/wuxia_qa/`

| 问题类型 | 典型示例 | 检索策略 | 答案来源 |
|----------|----------|----------|----------|
| 事实型 | 张无忌的师父是谁？ | Cypher图查询 | Neo4j精确返回 |
| 关系型 | 明教四大法王有哪些？ | 图遍历 + 向量相似度 | 图查询 + 向量召回融合 |
| 推理型 | 谢逊师父的徒弟有哪些？ | 多跳图搜索 + CoQ Agent | 多跳Cypher路径结果 |
| 主观型 | 为什么张无忌没有娶周芷若？ | 事实检索 + LLM推理 | 基于图谱事实的LLM推理 |
| 宏观型 | 概述明教与六大派的关系 | 全局摘要检索 + 社区聚合 | 图谱摘要 + 事件链融合 |

## 4. 数据存储设计

### 4.1 Neo4j 图存储结构

```
# 节点标签（按实体类型）
(:Person {name, courtesy_name, gender, sect, ...})
(:Organization {name, founder, location, ...})
(:Location {name, type, geo_affiliation, ...})
(:Skill {name, type, founder, sect, ...})
(:Artifact {name, type, origin, owner, ...})
(:Event {name, time, location, participants, ...})
(:Time {era, relative_expr, story_time, narrative_time})

# 关系类型
(:Person)-[:MENTOR_OF {since, formal}]->(:Person)
(:Person)-[:MEMBER_OF {since, rank}]->(:Organization)
(:Person)-[:PRACTICES {mastery_level}]->(:Skill)
(:Event)-[:OCCURRED_AT]->(:Location)
(:Event)-[:OCCURRED_DURING]->(:Time)

# 向量索引（用于语义检索）
CREATE VECTOR INDEX wuxia_person_embedding FOR (p:Person) ON (p.embedding)
CREATE VECTOR INDEX wuxia_event_embedding FOR (e:Event) ON (e.embedding)
```

## 5. 文件结构

```
packages/server/src/memmachine_server/
├── wuxia_ontology/
│   ├── __init__.py
│   ├── ontology_manager.py      # 本体论管理器
│   ├── schema.py                # Schema 定义
│   └── constraints.py           # 约束规则
├── wuxia_agents/
│   ├── __init__.py
│   ├── base_agent.py            # Agent 基类
│   ├── structure_analysis_agent.py
│   ├── entity_extraction_agent.py
│   ├── relation_extraction_agent.py
│   ├── knowledge_alignment_agent.py
│   ├── logical_validation_agent.py
│   └── timeline_agent.py
├── wuxia_pipeline/
│   ├── __init__.py
│   ├── knowledge_build_pipeline.py
│   └── quality_feedback.py
├── wuxia_qa/
│   ├── __init__.py
│   ├── query_router.py
│   ├── answer_generator.py
│   └── fusion.py
├── server/api_v2/
│   └── wuxia_router.py          # REST API
└── wuxia_service.py             # 服务入口
```

## 6. 实现优先级

### Phase 1: 基础设施（1-2周）
1. 实现 OntologyManager
2. 定义武侠领域 Schema
3. 扩展 VectorGraphStore 支持 Cypher 查询

### Phase 2: Agent 开发（2-3周）
1. 实现 WuxiaAgentBase 基类
2. 实现结构分析 Agent
3. 实现实体抽取 Agent
4. 实现关系抽取 Agent

### Phase 3: 流水线与质量（1-2周）
1. 实现知识对齐 Agent
2. 实现逻辑校验 Agent
3. 实现时间线 Agent
4. 编排完整流水线

### Phase 4: 检索与问答（1-2周）
1. 实现问题路由器
2. 实现多跳推理
3. 实现结果融合
4. 实现置信度标注

### Phase 5: API 与集成（1周）
1. REST API 实现
2. 与 MemMachine 主系统集成
3. 文档与测试

## 7. API 设计

### 7.1 知识构建 API

```http
POST /api/v2/wuxia/knowledge/build
Content-Type: application/json

{
  "novel_text": "第一章 纪晓芙出场...",
  "parallel": true
}

Response:
{
  "success": true,
  "entities_added": 150,
  "relations_added": 320,
  "errors": [],
  "warnings": ["低置信度实体需要审核: 纪晓芙的师父"]
}
```

### 7.2 问答 API

```http
POST /api/v2/wuxia/query
Content-Type: application/json

{
  "question": "张无忌的师父是谁？"
}

Response:
{
  "answer": "张无忌的师父包括：张三丰（武当派）、胡青牛（医术）...",
  "confidence": 0.95,
  "sources": [
    {"chapter": "第10章", "segment": "张三丰收徒"},
    {"chapter": "第15章", "segment": "胡青牛传授医术"}
  ],
  "query_type": "factual"
}
```

### 7.3 本体论查询 API

```http
GET /api/v2/wuxia/ontology/schema

Response:
{
  "entity_types": ["Person", "Organization", "Location", ...],
  "relation_types": ["MENTOR_OF", "MEMBER_OF", "PRACTICES", ...],
  "constraints": [
    {"id": "single_sect_membership", "name": "单一门派约束", ...}
  ]
}
```

### 7.4 时间线查询 API

```http
GET /api/v2/wuxia/timeline/{character_name}

Response:
{
  "character_name": "张无忌",
  "timeline": [
    {
      "event": "出生",
      "story_time": "1345年",
      "narrative_time": "第1章",
      "location": "冰火岛"
    },
    {
      "event": "拜师张三丰",
      "story_time": "1357年",
      "narrative_time": "第10章",
      "location": "武当山"
    }
  ]
}
```

## 8. 评估指标

| 指标 | 目标 | 测量方式 |
|------|------|----------|
| 实体抽取准确率 | >90% | 与人工标注对比 |
| 实体抽取召回率 | >85% | 覆盖率测试 |
| 关系抽取准确率 | >85% | 三元组验证 |
| 问答准确率 | >80% | 金标准问答集 |
| 约束违规检测率 | >95% | 故意注入错误案例 |
| 时间线正确率 | >90% | 事件顺序验证 |
