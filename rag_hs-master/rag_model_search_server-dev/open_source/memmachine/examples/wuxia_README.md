# 武侠知识图谱最小验证

## 快速开始

### 1. 启动 Neo4j

```bash
# 使用 Docker
docker run -d \
  --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/password \
  neo4j:latest
```

### 2. 设置 API Key

```bash
# 使用 OpenAI 或兼容服务
export OPENAI_API_KEY="your-api-key"

# 或者使用阿里云通义千问（兼容 OpenAI 格式）
export OPENAI_API_KEY="your-aliyun-key"
export OPENAI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
```

### 3. 运行验证脚本

```bash
cd /root/MemMachine/examples
python wuxia_minimal_verify.py
```

---

## 脚本说明

### wuxia_minimal_verify.py

最小验证脚本，包含以下功能：

| 功能 | 说明 |
|------|------|
| **本体论定义** | 4种实体类型、4种关系类型 |
| **LLM 抽取** | 基于 GPT-4o-mini 或兼容模型 |
| **Neo4j 存储** | 异步存储实体和关系 |
| **图查询** | 查询实体的所有关系 |

### 数据流

```
文本输入
  ↓
LLM 抽取（受 Schema 约束）
  ↓
ExtractionResult (entities + relations)
  ↓
Neo4j 存储
  ↓
图查询验证
```

---

## 验证标准

| 检查项 | 通过条件 |
|--------|----------|
| 本体论约束 | LLM 只返回预定义的实体/关系类型 |
| 抽取准确 | 实体名称正确，关系方向正确 |
| 存储成功 | Neo4j 中可查询到数据 |
| 查询正确 | 关系查询返回正确结果 |

---

## 测试样本

内置测试文本：

```
张无忌是明教教主，师承武当派张三丰。他修炼九阳神功，武功盖世。
明教总部位于光明顶，教主张无忌深受教众爱戴。张三丰是武当派创始人，
弟子众多，张无忌是他最得意的关门弟子。赵敏是汝阳王府的郡主，
与张无忌情投意合。周芷若本是峨眉派弟子，后成为峨眉派掌门。
```

预期抽取结果：

**实体**：
- Person: 张无忌、张三丰、赵敏、周芷若
- Organization: 明教、武当派、峨眉派、汝阳王府
- Location: 光明顶
- Skill: 九阳神功

**关系**：
- 张无忌 -[member_of]-> 明教
- 张三丰 -[mentor_of]-> 张无忌
- 张无忌 -[practices]-> 九阳神功
- 明教 -[located_at]-> 光明顶
- 张无忌 -[member_of]-> 武当派
- 周芷若 -[member_of]-> 峨眉派

---

## 处理完整小说

修改脚本中的 `mode` 变量或直接调用：

```bash
python -c "
import asyncio
from wuxia_minimal_verify import process_novel

asyncio.run(process_novel(
    novel_text=open('your_novel.txt').read(),
    api_key='your-api-key'
))
"
```

---

## Neo4j 查询示例

```cypher
# 查询所有人物
MATCH (p:Person) RETURN p.name LIMIT 20

# 查询张无忌的所有关系
MATCH (p:Person {name: '张无忌'})-[r]-(other)
RETURN p, type(r) as relation, other

# 查询明教的所有成员
MATCH (o:Organization {name: '明教'})<-[r:member_of]-(p:Person)
RETURN p.name

# 查询修炼九阳神功的人物
MATCH (p:Person)-[r:practices]->(s:Skill {name: '九阳神功'})
RETURN p.name

# 查询两跳关系：师父的师父
MATCH path = (p:Person {name: '张无忌'})<-[:mentor_of]-()-[:mentor_of]->(grandmentor)
RETURN grandmentor.name
```

---

## 故障排查

### 问题：LLM 抽取失败

```
❌ LLM 抽取失败: 401 Unauthorized
```

解决：检查 API Key 是否正确

### 问题：Neo4j 连接失败

```
❌ Failed to establish connection to Neo4j
```

解决：确认 Neo4j 正在运行，端口正确

### 问题：抽取结果为空

```
✅ 抽取结果:
  - 实体数量: 0
```

解决：检查 LLM 返回的 JSON 格式，可能是模型输出不符合 schema
