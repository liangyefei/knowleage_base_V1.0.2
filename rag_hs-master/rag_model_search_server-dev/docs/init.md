# HaiSI RAG 数据库初始化说明

## 概述

`--init-db` 是 HaiSI RAG 系统的数据库初始化命令，用于在系统首次部署或需要重建向量数据库时执行初始化操作。

**支持的数据库**：
- ✅ **PostgreSQL**（推荐，生产环境）
- ✅ **SQLite**（开发测试）

数据库类型通过 `params/kb_config.yaml` 中的 `SQLALCHEMY_DATABASE_URI` 配置。

---

## 使用方法

### 方式 1：通过 startup.py 启动参数

```bash
# 启动时自动初始化数据库
python startup.py --init-db
```

### 方式 2：直接运行 init_database.py

```bash
# 重建向量数据库（推荐）
python init_database.py --recreate-vs

# 仅创建数据库表
python init_database.py --create-tables

# 清空并重建数据库表
python init_database.py --clear-tables

# 增量更新向量数据库
python init_database.py --increment

# 从备份数据库导入数据
python init_database.py --import-db /path/to/backup.db
```

---

## 核心功能

### 1. `--init-db` 参数（startup.py）

**位置**：`startup.py` 第 536-542 行

```python
parser.add_argument(
    "-init",
    "--init-db",
    action="store_true",
    help="run init_database.py",
    dest="init_db",
)
```

**功能**：
- 删除现有的向量知识库目录
- 重建 SQLite 数据库表结构
- 初始化知识库根目录

**调用位置**：`startup.py` 第 669-670 行

```python
if args.init_db:
    init_db_recreate_vs()
```

---

### 2. `init_db_recreate_vs()` 函数

**位置**：`init_database.py` 第 21-32 行

```python
def init_db_recreate_vs():
    """删除向量数据库从本地文件重建"""
    # 1. 删除历史向量知识库
    restore_kw_db(KB_ROOT_PATH)
    logger.info("recreating all vector path")

    # 2. 重建sqlite3数据库表
    create_tables()
    logger.info("recreating sqlite3 stores")

    # 3. 初始化知识库文件(本身为空)
    # folder2db(kb_names=[], mode="recreate_vs", embed_model=EMBEDDING_MODEL)
    # logger.info("recreating all vector stores")
```

**执行流程**：
1. **删除旧数据**：调用 `restore_kw_db()` 删除 `KB_ROOT_PATH` 目录
2. **重建目录**：创建空的 `KB_ROOT_PATH` 目录
3. **创建表结构**：调用 `create_tables()` 创建 SQLite 数据库表

---

### 3. `restore_kw_db()` 函数

**位置**：`init_database.py` 第 34-40 行

```python
def restore_kw_db(kb_root_path):
    try:
        shutil.rmtree(kb_root_path)  # 删除整个目录树
    except Exception as e:
        logger.info(f"delete base_temp_dir error:{e}")
    os.makedirs(kb_root_path, exist_ok=True)  # 重新创建空目录
```

**功能**：
- 删除整个知识库根目录及其所有内容
- 重新创建空的知识库根目录

---

## 数据库表管理

### `create_tables()` - 创建表

**位置**：`migrate.py` 第 24-25 行

```python
def create_tables():
    Base.metadata.create_all(bind=engine)
```

**功能**：
- 根据 SQLAlchemy 模型定义创建所有数据库表
- 如果表已存在则不执行任何操作

**涉及的模型**：
- `ConversationModel` - 对话记录表
- `MessageModel` - 消息记录表
- `KnowledgeFileModel` - 知识文件表
- `KnowledgeMetadataModel` - 知识元数据表

---

### `reset_tables()` - 重置表

**位置**：`migrate.py` 第 28-30 行

```python
def reset_tables():
    Base.metadata.drop_all(bind=engine)  # 删除所有表
    create_tables()  # 重新创建表
```

**功能**：
- 删除所有数据库表（危险操作，会丢失所有数据）
- 重新创建空的数据库表

---

## 向量数据库操作

### `folder2db()` - 从文件重建向量库

**位置**：`migrate.py` 第 85-100 行

```python
def folder2db(
        kb_names: List[str],
        mode: Literal["recreate_vs", "update_in_db", "increment"],
        vs_type: Literal["faiss", "milvus", "pg", "chromadb"] = DEFAULT_VS_TYPE,
        embed_model: str = EMBEDDING_MODEL,
        chunk_size: int = CHUNK_SIZE,
        chunk_overlap: int = OVERLAP_SIZE,
        zh_title_enhance: bool = ZH_TITLE_ENHANCE,
):
    """
    use existed files in local folder to populate database and/or vector store.
    set parameter `mode` to:
        recreate_vs: recreate all vector store and fill info to database using existed files in local folder
        update_in_db: update vector store and database info using local files that existed in database only
        increment: create vector store and database info for local files that not existed in database only
    """
```

**模式说明**：

| 模式 | 功能 | 使用场景 |
|------|------|---------|
| `recreate_vs` | 重建所有向量库和数据库信息 | 系统首次部署、更换 Embedding 模型 |
| `update_in_db` | 更新数据库中已有文件的向量 | 文件内容更新后重新向量化 |
| `increment` | 增量添加新文件的向量 | 新增文件后增量更新 |

**支持的向量数据库**：
- `faiss` - Facebook AI Similarity Search
- `milvus` - 云原生向量数据库（默认）
- `pg` - PostgreSQL + pgvector
- `chromadb` - Chroma 向量数据库

---

## 完整调用关系

```
startup.py --init-db
    │
    ├─> parse_args()
    │       └─> args.init_db = True
    │
    └─> start_main_server()
            │
            └─> init_db_recreate_vs()  (init_database.py)
                    │
                    ├─> restore_kw_db(KB_ROOT_PATH)
                    │       ├─> shutil.rmtree(KB_ROOT_PATH)  # 删除整个目录
                    │       └─> os.makedirs(KB_ROOT_PATH)    # 重建空目录
                    │
                    └─> create_tables()  (migrate.py)
                            └─> Base.metadata.create_all(bind=engine)
                                    │
                                    ├─> 创建 conversation 表
                                    ├─> 创建 message 表
                                    ├─> 创建 knowledge_file 表
                                    └─> 创建 knowledge_metadata 表
```

---

## init_database.py 完整参数

### 基础参数

| 参数 | 简写 | 说明 | 示例 |
|------|------|------|------|
| `--recreate-vs` | `-r` | 重建向量数据库 | `python init_database.py -r` |
| `--create-tables` | - | 创建数据库表（如不存在） | `python init_database.py --create-tables` |
| `--clear-tables` | - | 清空并重建数据库表 | `python init_database.py --clear-tables` |
| `--import-db` | - | 从备份数据库导入数据 | `python init_database.py --import-db backup.db` |

### 更新参数

| 参数 | 简写 | 说明 |
|------|------|------|
| `--update-in-db` | `-u` | 更新数据库中已有文件的向量 |
| `--increment` | `-i` | 增量添加新文件的向量 |

### 清理参数

| 参数 | 说明 |
|------|------|
| `--prune-db` | 删除数据库中不存在于本地的文件记录 |
| `--prune-folder` | 删除本地不存在于数据库的文件 |

### 可选参数

| 参数 | 简写 | 默认值 | 说明 |
|------|------|--------|------|
| `--kb-name` | `-n` | 所有知识库 | 指定操作的知识库名称 |
| `--embed-model` | `-e` | EMBEDDING_MODEL | 指定向量化模型 |

---

## 使用场景

### 场景 1：首次部署系统

```bash
# 方式 1：使用 startup.py
python startup.py --init-db

# 方式 2：使用 init_database.py
python init_database.py --create-tables
python init_database.py --recreate-vs
```

**效果**：
- 创建所有必要的数据库表
- 初始化知识库根目录
- 准备好向量数据库环境

---

### 场景 2：更换 Embedding 模型

```bash
# 使用新的 Embedding 模型重建向量库
python init_database.py -r -e Qwen3-Embedding
```

**效果**：
- 删除旧的向量数据库
- 使用新的 Embedding 模型重新向量化所有文档
- 更新数据库中的向量信息

---

### 场景 3：增量添加新文档

```bash
# 仅处理新增的文档
python init_database.py --increment
```

**效果**：
- 扫描知识库目录
- 仅对数据库中不存在的文件进行向量化
- 节省计算资源和时间

---

### 场景 4：更新已有文档

```bash
# 重新向量化数据库中已有文档（适用于文档内容更新）
python init_database.py --update-in-db
```

**效果**：
- 仅重新处理数据库中已记录的文件
- 跳过新增文件

---

### 场景 5：清理无效数据

```bash
# 清理数据库中不存在于本地的文件记录
python init_database.py --prune-db

# 清理本地不存在于数据库的文件
python init_database.py --prune-folder
```

**效果**：
- 保持数据库与文件系统的一致性
- 释放磁盘空间

---

### 场景 6：版本升级数据迁移

```bash
# 从旧版本数据库导入数据（仅支持 SQLite 备份文件）
python init_database.py --import-db /path/to/old_info.db
```

**效果**：
- 从旧版本 SQLite 数据库导入数据到新版本
- 适用于数据库结构变更但无需重新向量化的情况

**注意**：`--import-db` 参数目前仅支持 SQLite 备份文件，不支持从 PostgreSQL 备份导入。

---

## PostgreSQL 配置（推荐）

### 配置说明

生产环境推荐使用 PostgreSQL 数据库，提供更好的性能和可靠性。

**配置文件**：`params/kb_config.yaml`

```yaml
# PostgreSQL 数据库连接
SQLALCHEMY_DATABASE_URI: "postgresql://用户名:密码@主机:端口/数据库名"
```

**示例配置**：
```yaml
SQLALCHEMY_DATABASE_URI: "postgresql://farai:farai123@192.168.80.250:16602/faraidb"
```

### 连接参数说明

| 参数 | 说明 | 示例值 |
|------|------|--------|
| 用户名 | PostgreSQL 用户 | `farai` |
| 密码 | PostgreSQL 密码 | `farai123` |
| 主机 | PostgreSQL 服务器地址 | `192.168.80.250` |
| 端口 | PostgreSQL 端口 | `16602` |
| 数据库名 | 数据库名称 | `faraidb` |

### 验证连接

运行测试脚本验证 PostgreSQL 连接：

```bash
python test_postgres_connection.py
```

**预期输出**：
```
数据库连接字符串: postgresql://farai:farai123@192.168.80.250:16602/faraidb
✅ PostgreSQL 连接成功!
数据库版本: PostgreSQL 15.x

现有数据表 (x 个):
  - conversation
  - message
  - knowledge_file
  - knowledge_metadata
```

### PostgreSQL 初始化流程

```bash
# 步骤 1：验证数据库连接
python test_postgres_connection.py

# 步骤 2：初始化数据库表（在 PostgreSQL 中创建）
python init_database.py --create-tables

# 步骤 3：验证表是否创建成功
python test_postgres_connection.py
# 应该看到 conversation、message 等表

# 步骤 4：重建向量库（如果需要）
python init_database.py --recreate-vs
```

### PostgreSQL vs SQLite 对比

| 特性 | PostgreSQL | SQLite |
|------|-----------|--------|
| 性能 | ⭐⭐⭐⭐⭐ 高性能 | ⭐⭐⭐ 中等 |
| 并发支持 | ✅ 多用户并发 | ❌ 单用户 |
| 数据安全 | ✅ ACID 事务 | ✅ 基本 ACID |
| 备份恢复 | ✅ pg_dump 工具 | ✅ 文件复制 |
| 生产环境 | ✅ 推荐 | ❌ 不推荐 |
| 部署难度 | 🟡 需要安装配置 | 🟢 开箱即用 |

---

## 数据库结构

### 主要数据表

#### 1. `conversation` 表
- 存储对话会话信息
- 字段：`id`, `name`, `create_time`, `llm_model` 等

#### 2. `message` 表
- 存储对话消息记录
- 字段：`id`, `conversation_id`, `role`, `content`, `create_time` 等

#### 3. `knowledge_file` 表
- 存储知识库文件信息
- 字段：`id`, `kb_name`, `file_name`, `file_ext`, `file_size`, `create_time` 等

#### 4. `knowledge_metadata` 表
- 存储文档切片元数据
- 字段：`id`, `kb_name`, `file_name`, `doc_id`, `chunk_index`, `content` 等

---

## 配置依赖

### 必需配置项

```python
# configs/__init__.py 导出的配置
KB_ROOT_PATH = "/path/to/knowledge_base"  # 知识库根目录
EMBEDDING_MODEL = "Qwen3-Embedding"       # Embedding 模型
DEFAULT_VS_TYPE = "milvus"                # 默认向量数据库类型
CHUNK_SIZE = 500                          # 文档切片大小
OVERLAP_SIZE = 50                         # 切片重叠大小
```

---

## 注意事项

### ⚠️ 警告

1. **数据丢失风险**：
   - `--init-db` 会删除整个知识库目录及其所有内容
   - `--clear-tables` 会删除所有数据库表和数据
   - 执行前请确保已备份重要数据

2. **执行时机**：
   - 建议在系统首次部署时使用 `--init-db`
   - 生产环境谨慎使用重建操作

3. **性能考虑**：
   - `--recreate-vs` 需要重新向量化所有文档，耗时较长
   - 对于大型知识库，建议使用 `--increment` 增量更新

---

## 最佳实践

### 1. 生产环境部署流程

```bash
# 步骤 1：首次部署初始化
python startup.py --init-db

# 步骤 2：添加知识库文档到 KB_ROOT_PATH/content/

# 步骤 3：构建向量数据库
python init_database.py --recreate-vs

# 步骤 4：启动服务
python startup.py --all-api
```

### 2. 定期维护流程

```bash
# 每周执行：清理无效数据
python init_database.py --prune-db

# 每月执行：检查数据一致性
python init_database.py --increment
```

### 3. 数据备份策略

```bash
# 备份数据库
cp /path/to/info.db /backup/info_$(date +%Y%m%d).db

# 备份知识库目录
tar -czf knowledge_base_$(date +%Y%m%d).tar.gz KB_ROOT_PATH/
```

---

## 故障排查

### 问题 1：初始化失败 - 权限不足

**错误信息**：
```
PermissionError: [Errno 13] Permission denied: '/path/to/KB_ROOT_PATH'
```

**解决方案**：
```bash
# 检查目录权限
ls -ld /path/to/KB_ROOT_PATH

# 修改权限
chmod 755 /path/to/KB_ROOT_PATH
chown -R user:user /path/to/KB_ROOT_PATH
```

---

### 问题 2：向量数据库连接失败

**错误信息**：
```
pymilvus.exceptions.MilvusException: <MilvusException: (code=2, message=Fail connecting to server)>
```

**解决方案**：
```bash
# 检查 Milvus 服务状态
docker ps | grep milvus

# 重启 Milvus 服务
docker restart milvus-standalone

# 或使用 FAISS 替代
export DEFAULT_VS_TYPE=faiss
python init_database.py --recreate-vs
```

---

### 问题 3：Embedding 模型加载失败

**错误信息**：
```
ValueError: Path /data/models/embedding not found
```

**解决方案**：
```bash
# 检查 Embedding 模型配置
cat params/kb_config.yaml | grep EMBEDDING_MODEL

# 使用 API Embedding 模型
# 修改 params/kb_config.yaml
EMBEDDING_MODEL: "Qwen3-Embedding"
EMBEDDING_API_URL: "http://192.168.80.250:8000/v1"
```

---

### 问题 4：PostgreSQL 连接失败

**错误信息**：
```
psycopg2.OperationalError: could not connect to server: Connection refused
```

**解决方案**：
```bash
# 1. 检查 PostgreSQL 服务状态
docker ps | grep postgres
# 或
systemctl status postgresql

# 2. 验证连接参数
python test_postgres_connection.py

# 3. 检查防火墙
# 在 PostgreSQL 服务器上执行
sudo ufw allow 16602/tcp

# 4. 检查 PostgreSQL 配置
# 编辑 postgresql.conf
listen_addresses = '*'
# 编辑 pg_hba.conf
host    all             all             0.0.0.0/0               md5

# 5. 重启 PostgreSQL
docker restart postgres
# 或
sudo systemctl restart postgresql
```

---

### 问题 5：PostgreSQL 数据库不存在

**错误信息**：
```
psycopg2.OperationalError: FATAL: database "faraidb" does not exist
```

**解决方案**：
```bash
# 1. 连接到 PostgreSQL
psql -h 192.168.80.250 -p 16602 -U farai -d postgres

# 2. 创建数据库
CREATE DATABASE faraidb OWNER farai;

# 3. 授予权限
GRANT ALL PRIVILEGES ON DATABASE faraidb TO farai;

# 4. 退出
\q

# 5. 重新初始化
python init_database.py --create-tables
```

---

## 相关文件

| 文件 | 路径 | 说明 |
|------|------|------|
| startup.py | `haisi_rag_models_server/startup.py` | 主启动脚本，包含 `--init-db` 参数 |
| init_database.py | `haisi_rag_models_server/init_database.py` | 数据库初始化脚本 |
| migrate.py | `haisi_rag_models_server/server/knowledge_base/migrate.py` | 数据迁移和向量化核心函数 |
| base.py | `haisi_rag_models_server/server/knowledge_base/kb_service/base.py` | 知识库服务基类 |
| models.py | `haisi_rag_models_server/server/db/models/*.py` | 数据库模型定义 |
| kb_config.yaml | `haisi_rag_models_server/params/kb_config.yaml` | 知识库配置，包含 `SQLALCHEMY_DATABASE_URI` |
| test_postgres_connection.py | `haisi_rag_models_server/test_postgres_connection.py` | PostgreSQL 连接测试脚本 |

---

## 总结

`--init-db` 是 HaiSI RAG 系统的重要初始化命令，主要用于：

1. ✅ **首次部署**：创建必要的数据库表和目录结构
2. ✅ **重建向量库**：删除旧数据，重新初始化系统
3. ✅ **更换模型**：切换 Embedding 模型时重建向量数据库

**核心操作**：
- 删除知识库根目录
- 重建数据库表结构
- 初始化系统环境

**建议使用场景**：
- 系统首次部署时使用一次
- 更换 Embedding 模型或向量数据库时使用
- 系统需要完全重置时使用

**避免使用场景**：
- 生产环境日常维护（使用 `--increment` 替代）
- 仅需更新部分文档（使用 `--update-in-db` 替代）
- 不了解操作后果时（先备份再操作）