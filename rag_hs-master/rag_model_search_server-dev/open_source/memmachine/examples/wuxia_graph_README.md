# 武侠知识图谱 Web 界面

## 功能

- 📝 输入问题查询武侠知识
- 🕸️ 可视化展示知识图谱
- 🔍 点击节点查看详情
- 🎨 按实体类型着色
- 📱 响应式设计

## 快速开始

### 1. 启动 Neo4j

```bash
docker start memmachine-neo4j
```

### 2. 启动 Web 服务

```bash
cd /root/MemMachine/examples
./run_graph_web.sh
```

### 3. 访问界面

浏览器打开: http://localhost:8080

---

## 使用示例

输入问题后点击"查询"：

| 问题 | 说明 |
|------|------|
| 张无忌 | 显示张无忌相关的所有关系 |
| 明教 | 显示明教组织及其成员 |
| 九阳神功 | 显示修炼此武功的人物 |
| 光明顶 | 显示光明顶相关的事件 |

---

## API 接口

### 查询知识图谱

```http
POST /api/wuxia/query
Content-Type: application/json

{
  "question": "张无忌的师父是谁？"
}

Response:
{
  "nodes": [
    {"id": "1", "label": "张无忌", "type": "Person"},
    {"id": "2", "label": "张三丰", "type": "Person"}
  ],
  "edges": [
    {"source": "2", "target": "1", "relation": "MENTOR_OF"}
  ]
}
```

### 获取实体关系

```http
GET /api/wuxia/entity/{entity_name}
```

### 健康检查

```http
GET /api/health
```

---

## 图例

| 颜色 | 实体类型 |
|------|----------|
| 🔵 蓝色 | Person (人物) |
| 🟢 绿色 | Organization (门派) |
| 🟠 橙色 | Location (地点) |
| 🟣 紫色 | Skill (武功) |
| 🔴 红色 | Artifact (兵器) |
| 🩷 粉色 | Event (事件) |

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | HTML + CSS + JavaScript |
| 图可视化 | vis.js |
| 后端 | FastAPI (Python) |
| 数据库 | Neo4j |

---

## 故障排查

### 问题：图谱为空

解决：先运行 `wuxia_minimal_verify.py` 构建知识图谱

### 问题：Neo4j 连接失败

```bash
# 检查容器状态
docker ps | grep neo4j

# 重启容器
docker restart memmachine-neo4j
```

### 问题：端口被占用

```bash
# 使用其他端口
PORT=8081 ./run_graph_web.sh
```
