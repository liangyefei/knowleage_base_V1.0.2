# Redis 部署文档

## 环境要求

- Docker 20.10+
- 宿主机已安装 Docker 并正常运行
- 确保端口 16379 未被占用

## 部署步骤

### 1. 拉取镜像

```bash
sudo docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/redis:7-alpine
```

> 使用华为云镜像加速，国内访问速度更快

### 2. 创建数据目录

```bash
sudo mkdir -p /data/redis/data
sudo chmod 777 /data/redis/data
```

### 3. 启动 Redis 容器

```bash
sudo docker run -d \
--name haisi-redis-server \
--restart always \
-p 16379:6379 \
-v /data/redis/data:/data \
-e TZ=Asia/Shanghai \
swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/redis:7-alpine \
redis-server \
--requirepass "farai123" \
--appendonly yes \
--appendfsync everysec
```

## 参数说明

| 参数 | 说明 |
|------|------|
| `--name haisi-redis-server` | 容器名称 |
| `--restart always` | 容器异常退出或 Docker 重启后自动启动 |
| `-p 16379:6379` | 端口映射，宿主机 16379 映射到容器 6379 |
| `-v /data/redis/data:/data` | 数据持久化，将 Redis 数据存储到宿主机 |
| `-e TZ=Asia/Shanghai` | 设置时区为上海时区 |
| `--requirepass "farai123"` | 设置 Redis 访问密码 |
| `--appendonly yes` | 开启 AOF 持久化 |
| `--appendfsync everysec` | AOF 持久化策略，每秒同步一次 |

## 验证部署

### 1. 检查容器状态

```bash
sudo docker ps | grep haisi-redis-server
```

### 2. 测试连接

```bash
# 进入容器内部测试
sudo docker exec -it haisi-redis-server redis-cli -a farai123 ping

# 或使用 redis-cli 远程连接
redis-cli -h 127.0.0.1 -p 16379 -a farai123 ping
```

预期输出：`PONG`

### 3. 查看日志

```bash
sudo docker logs haisi-redis-server
```

## 配置信息

| 配置项 | 值 |
|--------|-----|
| 宿主机 IP | 192.168.80.250 |
| 端口 | 16379 |
| 密码 | farai123 |
| 数据目录 | /data/redis/data |

## 连接方式

### Python 连接示例

```python
import redis

r = redis.Redis(
    host='192.168.80.250',
    port=16379,
    password='farai123',
    decode_responses=True
)

# 测试连接
print(r.ping())
```

### 应用配置

在项目的配置文件中添加 Redis 连接信息：

```yaml
# basic_config.yaml
REDIS_HOST: "192.168.80.250"
REDIS_PORT: 16379
REDIS_PASSWORD: "farai123"
```

## 常用命令

### 容器管理

```bash
# 启动容器
sudo docker start haisi-redis-server

# 停止容器
sudo docker stop haisi-redis-server

# 重启容器
sudo docker restart haisi-redis-server

# 删除容器（需先停止）
sudo docker rm haisi-redis-server
```

### Redis 操作

```bash
# 进入 Redis CLI
sudo docker exec -it haisi-redis-server redis-cli -a farai123

# 查看所有 key
KEYS *

# 清空数据库（慎用）
FLUSHALL

# 查看 Redis 信息
INFO
```

## 持久化说明

### AOF 持久化

当前配置使用 AOF (Append Only File) 持久化方式：

- `--appendonly yes`：开启 AOF
- `--appendfsync everysec`：每秒同步一次

数据文件位置：`/data/redis/data/appendonlydir/appendonly.aof`

### RDB 快照（可选）

如需开启 RDB 快照，可在启动命令中添加：

```bash
--save 60 1000  # 60秒内有1000次写入则生成快照
```

## 性能优化建议

### 内存管理

```bash
# 设置最大内存（根据服务器配置调整）
--maxmemory 2gb

# 内存达到上限时的淘汰策略
--maxmemory-policy allkeys-lru
```

### 连接数配置

```bash
# 最大连接数
--maxclients 10000
```

## 安全建议

1. **修改默认端口**：已修改为 16379
2. **设置强密码**：已设置密码 `farai123`
3. **限制访问 IP**：建议通过防火墙限制访问来源
4. **定期备份数据**：定期备份 `/data/redis/data` 目录

## 常见问题

### 1. 无法连接 Redis

**检查步骤：**
1. 确认容器正常运行：`sudo docker ps`
2. 检查端口是否开放：`netstat -tlnp | grep 16379`
3. 验证密码是否正确

### 2. 内存占用过高

**解决方案：**
- 检查是否有大量 key 未设置过期时间
- 调整 `maxmemory` 配置
- 使用 `MEMORY USAGE key` 分析大 key

### 3. 持久化文件过大

**解决方案：**
```bash
# 在 Redis CLI 中执行 BGREWRITEAOF 压缩 AOF 文件
BGREWRITEAOF
```

## 参考资料

- [Redis 官方文档](https://redis.io/documentation)
- [Docker Hub Redis](https://hub.docker.com/_/redis)






